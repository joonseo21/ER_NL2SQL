package io.eranalytics.pipeline.collection.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.eranalytics.pipeline.collection.BattleUserResultMapper;
import io.eranalytics.pipeline.collection.CollectorRepository;
import io.eranalytics.pipeline.collection.model.DeathRow;
import io.eranalytics.pipeline.collection.model.MappedMatch;
import io.eranalytics.pipeline.collection.model.MappedParticipant;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag("postgres")
@SpringBootTest(properties = {
        "er.collection.enabled=false", "spring.config.import=",
        "spring.datasource.hikari.maximum-pool-size=6"
})
class GamePersistenceIntegrationTest {
    private static final Path ROOT = Path.of("../..").toAbsolutePath().normalize();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> CHILDREN = List.of(
            "participant_equipment", "participant_traits", "participant_mastery",
            "participant_matchups", "participant_deaths");

    @Autowired CollectorRepository collector;
    @Autowired GamePersistenceService service;
    @Autowired BattleUserResultMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws Exception {
        String url = testUrl();
        try (Connection connection = admin(); Statement sql = connection.createStatement()) {
            var identity = sql.executeQuery("SELECT current_database(), current_user");
            identity.next();
            if (!"er_stage2_test".equals(identity.getString(1))
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
            var exists = sql.executeQuery("SELECT to_regclass('public.users') IS NOT NULL");
            exists.next();
            if (exists.getBoolean(1)) {
                sql.execute("TRUNCATE games, users, characters, rankers RESTART IDENTITY CASCADE");
            }
            for (String file : List.of("docs/schema_v1.sql",
                    "db/migrations/V2__games_start_dtm_timestamptz.sql",
                    "db/migrations/V3__mvp1_users_and_participant_columns.sql",
                    "db/migrations/V4__mvp1_move_raw_to_columns.sql")) {
                sql.execute(Files.readString(ROOT.resolve(file)));
            }
            sql.execute("GRANT USAGE ON SCHEMA public TO collector, agent_ro");
            sql.execute("""
                    GRANT SELECT, INSERT, UPDATE, DELETE ON
                        rankers, collect_queue, games, participants, characters, weapon_types, api_call_log,
                        users, tiers, participant_equipment, participant_traits, participant_mastery,
                        participant_matchups, participant_deaths TO collector
                    """);
            sql.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO collector");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> "collector");
        properties.add("spring.datasource.password", () -> "");
    }

    private static String testUrl() {
        String url = System.getenv("STAGE2_TEST_DATABASE_URL");
        if (url == null || !url.matches(
                "jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/er_stage2_test")) {
            throw new IllegalStateException("STAGE2_TEST_DATABASE_URL must target a dedicated local er_stage2_test DB");
        }
        return url;
    }

    private static Connection admin() throws Exception {
        return DriverManager.getConnection(testUrl(), "stage2_test", "");
    }

    @BeforeEach
    void resetTestDatabase() throws Exception {
        try (Connection connection = admin(); Statement sql = connection.createStatement()) {
            sql.execute("TRUNCATE games, users, characters, rankers RESTART IDENTITY CASCADE");
        }
        jdbc.update("INSERT INTO characters(character_code,name_en) VALUES (?,?),(?,?)",
                137, "syntheticranger", 700, "SYNTHETICRANGER");
    }

    @Test
    void persistsEveryV4ColumnArraysJsonAndLinkedChildrenUsingCollectorRole() throws Exception {
        var input = fixture();
        collector.upsertGame(input);
        JsonNode participant = participant("synthetic_alpha");
        String v4 = Files.readString(ROOT.resolve("db/migrations/V4__mvp1_move_raw_to_columns.sql"));
        var integers = Pattern.compile("(?m)^\\s*(\\w+) = \\(s\\.\"([^\"]+)\"\\)::integer,").matcher(v4);
        int integerCount = 0;
        while (integers.find()) {
            assertThat(participant.get(integers.group(1)).intValue())
                    .as(integers.group(2)).isEqualTo(input.getFirst().get(integers.group(2)).intValue());
            integerCount++;
        }
        assertThat(integerCount).isEqualTo(89);
        var doubles = Pattern.compile(
                "(?m)^\\s*(\\w+) = \\(s\\.\"([^\"]+)\"\\)::double precision,").matcher(v4);
        int doubleCount = 0;
        while (doubles.find()) {
            assertThat(participant.get(doubles.group(1)).doubleValue())
                    .as(doubles.group(2)).isEqualTo(input.getFirst().get(doubles.group(2)).doubleValue());
            doubleCount++;
        }
        assertThat(doubleCount).isEqualTo(8);
        assertThat(participant.get("total_vf_credits")).isEqualTo(JSON.readTree("[10,null,30]"));
        assertThat(participant.get("item_transferred_console")).isEqualTo(JSON.readTree("[]"));
        assertThat(participant.get("skill_order")).isEqualTo(JSON.readTree("[1001,1002,1003]"));
        for (var pair : Map.of("skill_level_info", "skillLevelInfo",
                "credit_source", "creditSource", "kill_monsters", "killMonsters").entrySet()) {
            assertThat(participant.get(pair.getKey())).isEqualTo(input.getFirst().get(pair.getValue()));
        }
        ObjectNode residual = (ObjectNode) input.getFirst().deepCopy();
        residual.remove(mapper.movedRawKeys());
        assertThat(participant.get("raw")).isEqualTo(residual);
        assertThat(participant.get("tier").asText()).isEqualTo("플래티넘");
        assertThat(participant.get("tier_division").intValue()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("collector");
        assertThat(count("users")).isEqualTo(3);
        assertThat(count("participant_equipment")).isEqualTo(5);
        assertThat(count("participant_traits")).isEqualTo(5);
        assertThat(count("participant_mastery")).isEqualTo(2);
        assertThat(count("participant_matchups")).isEqualTo(3);
        assertThat(count("participant_deaths")).isEqualTo(3);
        long bravoId = participant("synthetic_bravo").get("participant_id").longValue();
        assertThat(jdbc.queryForObject("""
                SELECT killer_participant_id FROM participant_deaths
                WHERE participant_id=? AND death_seq=1
                """, Long.class, participant.get("participant_id").longValue())).isEqualTo(bravoId);
        assertThat(jdbc.queryForObject("SELECT killer_character_num FROM participant_deaths WHERE death_seq=2",
                Integer.class)).isEqualTo(137);
        assertThat(jdbc.queryForObject("SELECT killer_participant_id IS NULL AND killer_character_num IS NULL "
                + "FROM participant_deaths WHERE death_seq=3", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT user_id IS NOT NULL FROM participants WHERE nickname='synthetic_alpha'
                """, Boolean.class)).isTrue();
    }

    @Test
    void repeatedGameKeepsIdsAndCountsStable() throws Exception {
        var rows = fixture();
        collector.upsertGame(rows);
        var before = snapshot();
        collector.upsertGame(rows);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT games_seen FROM users", Integer.class)).containsOnly(1);
    }

    @Test
    void resyncUpdatesAndRemovesChildrenWhileKeepingParticipantIdentity() throws Exception {
        var rows = fixture();
        collector.upsertGame(rows);
        long id = participant("synthetic_alpha").get("participant_id").longValue();
        ObjectNode first = (ObjectNode) rows.getFirst();
        first.put("victory", 2);
        first.set("equipment", JSON.readTree("{\"0\":110009}"));
        first.set("equipmentGrade", JSON.readTree("{\"0\":5}"));
        first.set("equipFirstItemForLog", JSON.readTree("{}"));
        first.put("traitFirstCore", 700102);
        first.set("traitFirstSub", JSON.readTree("[]"));
        first.set("traitSecondSub", JSON.readTree("[]"));
        first.set("masteryLevel", JSON.readTree("{\"16\":25}"));
        first.put("killDetails", "{}").put("deathDetails", "{}");
        for (String suffix : List.of("2", "3")) {
            for (String key : List.of("killer", "killerCharacter", "killerWeapon", "causeOfDeath", "placeOfDeath")) {
                first.put(key + suffix, "");
            }
        }
        first.put("killDetail", "synthetic_charlie");
        collector.upsertGame(rows);
        assertThat(participant("synthetic_alpha").get("participant_id").longValue()).isEqualTo(id);
        assertThat(participant("synthetic_alpha").get("victory").intValue()).isEqualTo(2);
        assertThat(count("participant_equipment")).isEqualTo(1);
        assertThat(count("participant_traits")).isEqualTo(1);
        assertThat(count("participant_mastery")).isEqualTo(1);
        assertThat(count("participant_matchups")).isZero();
        assertThat(count("participant_deaths")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT grade FROM participant_equipment", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT mastery_level FROM participant_mastery", Integer.class)).isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT killer_participant_id FROM participant_deaths", Long.class))
                .isEqualTo(participant("synthetic_charlie").get("participant_id").longValue());
    }

    @Test
    void failingFinalChildRollsBackAllNewRows() throws Exception {
        assertThatThrownBy(() -> service.store(withInvalidDeath(mapper.mapMatch(fixture(), Map.of()))))
                .isInstanceOf(RuntimeException.class);
        for (String table : List.of("games", "users", "participants",
                "participant_equipment", "participant_traits", "participant_mastery",
                "participant_matchups", "participant_deaths")) {
            assertThat(count(table)).as(table).isZero();
        }
    }

    @Test
    void failingReplayRestoresExistingRowsAndChildren() throws Exception {
        var rows = fixture();
        collector.upsertGame(rows);
        var before = snapshot();
        ((ObjectNode) rows.getFirst()).put("victory", 2);
        assertThatThrownBy(() -> service.store(withInvalidDeath(mapper.mapMatch(rows, Map.of()))))
                .isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void userStatsFollowGameTimeThenGameIdAndPreserveNullOrdering() throws Exception {
        collector.upsertGame(fixture());
        collector.upsertGame(game(410010, "2026-09-01T00:00:00Z", 7000));
        assertUserStats(2, 410001, 3975);
        collector.upsertGame(game(410011, "2026-10-08T12:10:50.050Z", 5100));
        assertUserStats(3, 410011, 5100);
        collector.upsertGame(game(410012, null, 7600));
        assertUserStats(4, 410011, 5100);
        assertThat(jdbc.queryForObject("SELECT tier FROM users WHERE nickname='synthetic_alpha'", String.class))
                .isEqualTo("다이아몬드");
    }

    @Test
    void usesDatabaseTierBoundariesForBeforeAndAfterMmr() throws Exception {
        var rows = fixture();
        ((ObjectNode) rows.getFirst()).put("mmrBefore", 3550).put("mmrAfter", 3550);
        ((ObjectNode) rows.get(1)).putNull("mmrBefore").putNull("mmrAfter");
        ((ObjectNode) rows.get(2)).put("mmrBefore", 7600).putNull("mmrAfter");
        collector.upsertGame(rows);
        assertThat(participant("synthetic_alpha").get("tier").asText()).isEqualTo("플래티넘 미만");
        assertThat(participant("synthetic_alpha").get("tier_division").intValue()).isZero();
        assertThat(participant("synthetic_bravo").get("tier").isNull()).isTrue();
        assertThat(participant("synthetic_charlie").get("tier").asText()).isEqualTo("미스릴 이상");
        assertThat(jdbc.queryForObject("SELECT tier FROM users WHERE nickname='synthetic_alpha'", String.class))
                .isEqualTo("플래티넘 미만");
        assertThat(jdbc.queryForObject("SELECT tier FROM users WHERE nickname='synthetic_charlie'", String.class))
                .isNull();
    }

    @Test
    void replayPreservesCrawlState() throws Exception {
        collector.upsertGame(fixture());
        jdbc.update("""
                UPDATE users SET crawl_status='DONE',last_crawled_at='2026-10-09T00:00:00Z',
                    crawled_newest_game_id=410001,crawl_error='synthetic_marker'
                """);
        var before = jdbc.queryForList("SELECT user_id,crawl_status,last_crawled_at,"
                + "crawled_newest_game_id,crawl_error FROM users ORDER BY user_id");
        collector.upsertGame(fixture());
        assertThat(jdbc.queryForList("SELECT user_id,crawl_status,last_crawled_at,"
                + "crawled_newest_game_id,crawl_error FROM users ORDER BY user_id")).isEqualTo(before);
    }

    @Test
    void sameNicknameInAnotherSeasonGetsSeparateUser() throws Exception {
        collector.upsertGame(fixture());
        var next = game(420001, "2026-10-09T00:00:00Z", 5000);
        next.forEach(row -> ((ObjectNode) row).put("seasonId", 42));
        collector.upsertGame(next);
        assertThat(count("users")).isEqualTo(6);
        assertThat(jdbc.queryForList("SELECT games_seen FROM users", Integer.class)).containsOnly(1);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT user_id) FROM participants "
                + "WHERE nickname='synthetic_alpha'", Integer.class)).isEqualTo(2);
    }

    @Test
    void simultaneousReplayDoesNotCreateDuplicateParentsChildrenOrCounters() throws Exception {
        var rows = fixture();
        concurrently(() -> collector.upsertGame(rows), () -> collector.upsertGame(rows));
        assertThat(count("games")).isEqualTo(1);
        assertThat(count("participants")).isEqualTo(3);
        assertThat(count("participant_equipment")).isEqualTo(5);
        assertThat(jdbc.queryForList("SELECT games_seen FROM users", Integer.class)).containsOnly(1);
    }

    @Test
    void simultaneousGamesWithSharedUsersAndReversedOrderKeepAccurateStats() throws Exception {
        var first = fixture();
        var second = new ArrayList<>(game(410002, "2026-10-09T00:00:00Z", 5100));
        java.util.Collections.reverse(second);
        concurrently(() -> collector.upsertGame(first), () -> collector.upsertGame(second));
        assertThat(count("games")).isEqualTo(2);
        assertThat(count("participants")).isEqualTo(6);
        assertThat(count("users")).isEqualTo(3);
        assertUserStats(2, 410002, 5100);
    }

    @Test
    void invalidApiFieldFailsBeforeAnyWrites() throws Exception {
        var rows = fixture();
        ((ObjectNode) rows.getFirst()).put("victory", "private_synthetic_value");
        assertThatThrownBy(() -> collector.upsertGame(rows)).hasMessageContaining("victory")
                .hasMessageNotContaining("private_synthetic_value");
        assertThat(count("games")).isZero();
        assertThat(count("users")).isZero();
    }

    @Test
    void nullableAndEmptyValuesKeepTheirDatabaseMeaning() throws Exception {
        var minimal = JSON.readTree("""
                {"gameId":42,"seasonId":41,"nickname":"synthetic_minimal",
                 "totalVFCredits":[],"skillOrderInfo":{},"skillLevelInfo":null}
                """);
        collector.upsertGame(List.of(minimal));
        var row = participant("synthetic_minimal");
        assertThat(row.get("total_vf_credits")).isEqualTo(JSON.readTree("[]"));
        assertThat(row.get("skill_order")).isEqualTo(JSON.readTree("[]"));
        assertThat(row.get("used_vf_credits").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT skill_level_info IS NULL AND mmr_before IS NULL "
                + "FROM participants", Boolean.class)).isTrue();
        for (String child : CHILDREN) {
            assertThat(count(child)).isZero();
        }
    }

    @Test
    void jpaStorageMatchesActualV4BackfillOfTheSameRawFixture() throws Exception {
        var rows = fixture();
        jdbc.update("""
                INSERT INTO games(game_id,season_id,matching_mode,matching_team_mode,
                    version_season,version_major,version_minor,start_dtm,server_name)
                VALUES (410001,41,3,3,12,5,0,'2026-10-08T12:10:50.050Z','synthetic_server')
                """);
        for (var row : rows) {
            jdbc.update("INSERT INTO participants(game_id,nickname,raw) VALUES (410001,?,?::jsonb)",
                    row.get("nickname").textValue(), JSON.writeValueAsString(row));
        }
        // V4 assumes V1 columns have already been populated by the old writer.
        for (var row : rows) {
            jdbc.update("""
                    UPDATE participants SET team_number=?,character_num=?,best_weapon=?,best_weapon_level=?,
                        game_rank=?,player_kill=?,player_assistant=?,monster_kill=?,damage_to_player=?,
                        mmr_before=?,mmr_gain=?,mmr_after=?,play_time=? WHERE nickname=?
                    """, integer(row,"teamNumber"),integer(row,"characterNum"),integer(row,"bestWeapon"),
                    integer(row,"bestWeaponLevel"),integer(row,"gameRank"),integer(row,"playerKill"),
                    integer(row,"playerAssistant"),integer(row,"monsterKill"),integer(row,"damageToPlayer"),
                    integer(row,"mmrBefore"),integer(row,"mmrGain"),integer(row,"mmrAfter"),
                    integer(row,"playTime"),row.get("nickname").textValue());
        }
        try (Connection connection = admin(); Statement sql = connection.createStatement()) {
            sql.execute(Files.readString(ROOT.resolve("db/migrations/V4__mvp1_move_raw_to_columns.sql")));
        }
        var migrated = snapshot();
        resetTestDatabase();
        collector.upsertGame(rows);
        assertThat(snapshot()).isEqualTo(migrated);
    }

    private static Integer integer(JsonNode row, String key) {
        return row.hasNonNull(key) ? row.get(key).intValue() : null;
    }

    private void assertUserStats(int count, long lastGameId, int mmr) {
        var row = jdbc.queryForMap("SELECT games_seen,last_game_id,last_mmr FROM users "
                + "WHERE season_id=41 AND nickname='synthetic_alpha'");
        assertThat(row).containsEntry("games_seen", count).containsEntry("last_game_id", lastGameId)
                .containsEntry("last_mmr", mmr);
    }

    private JsonNode participant(String name) throws Exception {
        return JSON.readTree(jdbc.queryForObject("SELECT to_jsonb(p)::text FROM participants p WHERE nickname=?",
                String.class, name));
    }

    private int count(String table) {
        // Identifiers are fixed test constants, never API input.
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private Map<String,List<String>> snapshot() {
        Map<String,List<String>> rows = new LinkedHashMap<>();
        rows.put("games", jdbc.queryForList("SELECT (to_jsonb(g)-'fetched_at')::text FROM games g ORDER BY game_id",
                String.class));
        rows.put("participants", jdbc.queryForList("SELECT to_jsonb(p)::text FROM participants p "
                + "ORDER BY participant_id", String.class));
        rows.put("users", jdbc.queryForList("SELECT (to_jsonb(u)-'first_seen_at')::text FROM users u ORDER BY user_id",
                String.class));
        for (String child : CHILDREN) {
            var values = new ArrayList<>(jdbc.queryForList("SELECT to_jsonb(c)::text FROM " + child + " c", String.class));
            values.sort(String::compareTo);
            rows.put(child, List.copyOf(values));
        }
        return rows;
    }

    private static void concurrently(Runnable first, Runnable second) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(first);
            var two = pool.submit(second);
            one.get(20, TimeUnit.SECONDS);
            two.get(20, TimeUnit.SECONDS);
        }
    }

    private static MappedMatch withInvalidDeath(MappedMatch match) {
        var rows = new ArrayList<>(match.participants());
        var first = rows.getFirst();
        rows.set(0, new MappedParticipant(first.data(),first.equipment(),first.traits(),
                first.mastery(),first.matchups(),List.of(new DeathRow((short)4,"player",1,12,
                "SyntheticCharacter",null,null,null)),first.remainingRaw()));
        return new MappedMatch(match.game(),List.copyOf(rows));
    }

    private static List<JsonNode> game(long id, String startedAt, int mmrAfter) throws Exception {
        var rows = fixture();
        rows.forEach(row -> {
            ObjectNode object = (ObjectNode) row;
            object.put("gameId", id);
            if (startedAt == null) {
                object.putNull("startDtm");
            } else {
                object.put("startDtm", startedAt);
            }
        });
        ((ObjectNode)rows.getFirst()).put("mmrAfter",mmrAfter);
        return rows;
    }

    private static List<JsonNode> fixture() throws Exception {
        try (InputStream resource = GamePersistenceIntegrationTest.class
                .getResourceAsStream("/fixtures/battle_user_result_v4.json")) {
            List<JsonNode> rows = new ArrayList<>();
            JSON.readTree(resource).get("userGames").forEach(rows::add);
            return rows;
        }
    }
}
