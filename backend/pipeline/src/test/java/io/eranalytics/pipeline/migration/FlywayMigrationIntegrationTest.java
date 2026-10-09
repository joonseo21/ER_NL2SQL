package io.eranalytics.pipeline.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("postgres")
class FlywayMigrationIntegrationTest {
    private static final Path ROOT = Path.of("../..").toAbsolutePath().normalize();
    private static final ObjectMapper JSON = new ObjectMapper();
    private String url;
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetDedicatedDatabase() throws Exception {
        url = System.getenv("FLYWAY_TEST_DATABASE_URL");
        if (url == null || !url.matches(
                "jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/er_flyway_test")) {
            throw new IllegalStateException("FLYWAY_TEST_DATABASE_URL must target a dedicated local er_flyway_test DB");
        }
        try (Connection connection = DriverManager.getConnection(url, "stage2_test", "");
             var sql = connection.createStatement()) {
            var identity = sql.executeQuery("SELECT current_database(), current_user");
            identity.next();
            if (!"er_flyway_test".equals(identity.getString(1))
                    || !"stage2_test".equals(identity.getString(2))) {
                throw new IllegalStateException("Dedicated test database identity required");
            }
            sql.execute("""
                    DO $$ BEGIN
                        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='collector') THEN
                            CREATE ROLE collector LOGIN;
                        END IF;
                        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='agent_ro') THEN
                            CREATE ROLE agent_ro LOGIN;
                        END IF;
                    END $$
                    """);
            sql.execute("DROP SCHEMA public CASCADE");
            sql.execute("CREATE SCHEMA public");
        }
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "stage2_test", ""));
    }

    @Test
    void initializesEmptyDatabaseAndDoesNotRepeatAppliedMigrations() {
        assertThat(flyway().migrate().migrationsExecuted).isEqualTo(5);
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE version IS NOT NULL "
                + "ORDER BY installed_rank", String.class)).containsExactly("1", "2", "3", "4");
        assertThat(flyway().info().current().getVersion().toString()).isEqualTo("4");
        flyway().validate();
        assertThat(flyway().migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tiers", Integer.class)).isEqualTo(14);
    }

    @Test
    void requiresExplicitBaselineAndMigratesExistingV2Data() throws Exception {
        applyOriginal("V1__initial_schema.sql");
        applyOriginal("V2__games_start_dtm_timestamptz.sql");
        seedLegacyFixture();
        assertThatThrownBy(() -> flyway().migrate()).isInstanceOf(FlywayException.class)
                .hasMessageContaining("baseline");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM participants", Integer.class)).isEqualTo(3);

        DatabaseMigration.configure(url, "stage2_test", "").baselineVersion("2").load().baseline();
        assertThat(flyway().migrate().migrationsExecuted).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT type FROM flyway_schema_history WHERE version='2'", String.class))
                .isEqualTo("BASELINE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM participants", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM participant_equipment", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM participant_deaths", Integer.class)).isEqualTo(3);
        var migrated = JSON.readTree(jdbc.queryForObject("SELECT to_jsonb(p)::text FROM participants p "
                + "WHERE nickname='synthetic_alpha'", String.class));
        assertThat(migrated.get("victory").intValue()).isEqualTo(1);
        assertThat(migrated.get("user_id").isNull()).isFalse();
        assertThat(migrated.get("raw").has("characterNum")).isFalse();
        assertThat(migrated.get("raw").get("killDetail").asText()).isEqualTo("synthetic_bravo");
        flyway().validate();
        assertThat(flyway().migrate().migrationsExecuted).isZero();
    }

    @Test
    void rollsBackFailedV4DataAndHistoryThenAllowsRetryWithoutRepair() {
        DatabaseMigration.configure(url, "stage2_test", "").target("3").load().migrate();
        jdbc.update("INSERT INTO games(game_id,season_id) VALUES (410001,41)");
        String invalid = "{\"gameId\":410001,\"nickname\":\"synthetic_alpha\",\"characterNum\":23,"
                + "\"seasonId\":41,\"matchSize\":24,\"victory\":\"invalid\"}";
        jdbc.update("INSERT INTO participants(game_id,nickname,character_num,raw) VALUES (410001,?,23,?::jsonb)",
                "synthetic_alpha", invalid);
        assertThatThrownBy(() -> flyway().migrate()).isInstanceOf(FlywayException.class);
        assertThat(jdbc.queryForObject("SELECT match_size IS NULL FROM games WHERE game_id=410001", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT raw = ?::jsonb FROM participants", Boolean.class, invalid)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='4'", Integer.class))
                .isZero();
        assertThat(flyway().info().current().getVersion().toString()).isEqualTo("3");
        jdbc.update("UPDATE participants SET raw=jsonb_set(raw,'{victory}','1')");
        flyway().migrate();
        assertThat(jdbc.queryForObject("SELECT match_size FROM games WHERE game_id=410001", Integer.class))
                .isEqualTo(24);
        flyway().validate();
    }

    @Test
    void rejectsChangedAppliedSql(@TempDir Path changed) throws Exception {
        flyway().migrate();
        for (String file : List.of("V1__initial_schema.sql", "V2__games_start_dtm_timestamptz.sql",
                "V3__mvp1_users_and_participant_columns.sql", "V4__mvp1_move_raw_to_columns.sql",
                "R__runtime_privileges.sql")) {
            try (var resource = getClass().getResourceAsStream("/db/migration/" + file)) {
                Files.copy(resource, changed.resolve(file));
            }
        }
        Path v3 = changed.resolve("V3__mvp1_users_and_participant_columns.sql");
        Files.writeString(v3, Files.readString(v3) + "\n-- Changed applied SQL\n");
        Flyway altered = DatabaseMigration.configure(url, "stage2_test", "")
                .locations("filesystem:" + changed).load();
        assertThatThrownBy(altered::validate).isInstanceOf(FlywayException.class)
                .hasMessageContaining("checksum mismatch");
        assertThatThrownBy(altered::migrate).isInstanceOf(FlywayException.class);
    }

    @Test
    void grantsOnlyRuntimeDataPrivilegesAndBlocksPrivateAgentColumns() throws Exception {
        flyway().migrate();
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('collector','games','INSERT')", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT has_schema_privilege('collector','public','CREATE')", Boolean.class))
                .isFalse();
        for (String role : List.of("collector", "agent_ro")) {
            assertThat(jdbc.queryForObject("SELECT has_table_privilege(?, 'flyway_schema_history', 'SELECT')",
                    Boolean.class, role)).isFalse();
        }
        try (Connection agent = DriverManager.getConnection(url, "agent_ro", "");
             var sql = agent.createStatement()) {
            sql.executeQuery("SELECT character_num,user_id,participant_id,tier FROM participants LIMIT 1");
            for (String blocked : List.of("SELECT nickname FROM participants", "SELECT raw FROM participants",
                    "SELECT is_ranker FROM participants", "SELECT * FROM users", "SELECT * FROM rankers")) {
                assertThatThrownBy(() -> sql.executeQuery(blocked)).isInstanceOf(SQLException.class)
                        .hasMessageContaining("permission denied");
            }
        }
    }

    private Flyway flyway() {
        return DatabaseMigration.configure(url, "stage2_test", "").load();
    }

    private void applyOriginal(String file) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, "stage2_test", "");
             var sql = connection.createStatement()) {
            sql.execute(Files.readString(ROOT.resolve("db/migrations").resolve(file)));
        }
    }

    private void seedLegacyFixture() throws Exception {
        jdbc.update("""
                INSERT INTO games(game_id,season_id,matching_mode,matching_team_mode,
                    version_season,version_major,version_minor,start_dtm,server_name)
                VALUES (410001,41,3,3,12,5,0,'2026-10-08T12:10:50.050Z','synthetic_server')
                """);
        JsonNode fixture;
        try (var resource = getClass().getResourceAsStream("/fixtures/battle_user_result_v4.json")) {
            fixture = JSON.readTree(resource);
        }
        for (JsonNode row : fixture.get("userGames")) {
            jdbc.update("""
                    INSERT INTO participants(game_id,nickname,team_number,character_num,best_weapon,best_weapon_level,
                        game_rank,player_kill,player_assistant,monster_kill,damage_to_player,mmr_before,mmr_gain,
                        mmr_after,play_time,raw) VALUES (410001,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)
                    """, row.get("nickname").textValue(), integer(row,"teamNumber"),integer(row,"characterNum"),
                    integer(row,"bestWeapon"),integer(row,"bestWeaponLevel"),integer(row,"gameRank"),
                    integer(row,"playerKill"),integer(row,"playerAssistant"),integer(row,"monsterKill"),
                    integer(row,"damageToPlayer"),integer(row,"mmrBefore"),integer(row,"mmrGain"),
                    integer(row,"mmrAfter"),integer(row,"playTime"), JSON.writeValueAsString(row));
        }
    }

    private static Integer integer(JsonNode row, String key) {
        return row.hasNonNull(key) ? row.get(key).intValue() : null;
    }
}
