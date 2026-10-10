package io.eranalytics.pipeline.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.eranalytics.pipeline.migration.DatabaseMigration;
import java.nio.file.Files;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Independent SQL V4 reference, not a second invocation of the Java mapper. */
final class Stage5Reference {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> CHILDREN = List.of("participant_equipment", "participant_traits",
            "participant_mastery", "participant_matchups", "participant_deaths");

    static JdbcTemplate referenceJdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(Stage5DatabaseGuard.referenceUrl(),
                Stage5DatabaseGuard.OWNER, ""));
    }

    static void prepare(Map<Long, List<JsonNode>> inputs, JdbcTemplate source) throws Exception {
        // Only the marker-checked reference DB is reset; the full production copy is preserved.
        try (Connection owner = Stage5DatabaseGuard.referenceOwner(); var sql = owner.createStatement()) {
            sql.execute("DROP SCHEMA public CASCADE");
            sql.execute("CREATE SCHEMA public");
            sql.execute(Files.readString(Stage5DatabaseGuard.ROOT.resolve("db/migrations/V1__initial_schema.sql")));
            sql.execute(Files.readString(Stage5DatabaseGuard.ROOT.resolve("db/migrations/V2__games_start_dtm_timestamptz.sql")));
        }
        JdbcTemplate reference = referenceJdbc();
        // V4 character fallback must see the same public character dictionary as JPA.
        for (var character : source.queryForList("SELECT character_code,name_ko,name_en FROM characters")) {
            reference.update("INSERT INTO characters(character_code,name_ko,name_en) VALUES (?,?,?)",
                    character.get("character_code"), character.get("name_ko"), character.get("name_en"));
        }
        for (var entry : inputs.entrySet()) {
            long id = entry.getKey();
            JsonNode game = entry.getValue().getFirst();
            reference.update("""
                    INSERT INTO games(game_id,season_id,matching_mode,matching_team_mode,
                        version_season,version_major,version_minor,start_dtm,server_name)
                    VALUES (?,?,?,?,?,?,?,?::timestamptz,?)
                    """, id, integer(game,"seasonId"), integer(game,"matchingMode"),
                    integer(game,"matchingTeamMode"), integer(game,"versionSeason"),
                    integer(game,"versionMajor"), integer(game,"versionMinor"),
                    text(game,"startDtm"), text(game,"serverName"));
            for (JsonNode participant : entry.getValue()) {
                // Populate only V1 fields and full raw, as the old writer did. SQL V3/V4 does the rest.
                Boolean ranker = source.queryForObject("SELECT is_ranker FROM participants WHERE game_id=? AND nickname=?",
                        Boolean.class, id, text(participant,"nickname"));
                reference.update("""
                        INSERT INTO participants(game_id,nickname,team_number,character_num,best_weapon,
                            best_weapon_level,game_rank,player_kill,player_assistant,monster_kill,damage_to_player,
                            mmr_before,mmr_gain,mmr_after,play_time,is_ranker,raw)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)
                        """, id, text(participant,"nickname"), integer(participant,"teamNumber"),
                        integer(participant,"characterNum"), integer(participant,"bestWeapon"),
                        integer(participant,"bestWeaponLevel"), integer(participant,"gameRank"),
                        integer(participant,"playerKill"), integer(participant,"playerAssistant"),
                        integer(participant,"monsterKill"), integer(participant,"damageToPlayer"),
                        integer(participant,"mmrBefore"), integer(participant,"mmrGain"),
                        integer(participant,"mmrAfter"), integer(participant,"playTime"), ranker,
                        JSON.writeValueAsString(participant));
            }
        }
        var flyway = DatabaseMigration.configure(Stage5DatabaseGuard.referenceUrl(), Stage5DatabaseGuard.OWNER, "")
                .baselineVersion("2").load();
        flyway.baseline();
        flyway.migrate();
        flyway.validate();
    }

    static Map<String, List<JsonNode>> snapshot(JdbcTemplate database, List<Long> gameIds,
                                               boolean includeIdentities) throws Exception {
        Map<String, List<JsonNode>> snapshot = new LinkedHashMap<>();
        List<JsonNode> games = new ArrayList<>();
        List<JsonNode> participants = new ArrayList<>();
        for (long id : gameIds) {
            games.add(JSON.readTree(database.queryForObject("SELECT (to_jsonb(g)-'fetched_at')::text FROM games g "
                    + "WHERE game_id=?", String.class, id)));
            String projection = includeIdentities ? "to_jsonb(p)" : "to_jsonb(p)-'participant_id'-'user_id'";
            for (String json : database.queryForList("SELECT (" + projection + ")::text FROM participants p "
                    + "WHERE game_id=? ORDER BY nickname", String.class, id)) {
                participants.add(JSON.readTree(json));
            }
        }
        snapshot.put("games", sorted(games));
        snapshot.put("participants", sorted(participants));
        for (String table : CHILDREN) {
            List<JsonNode> rows = new ArrayList<>();
            for (long id : gameIds) {
                String join = "participant_deaths".equals(table) ?
                        "LEFT JOIN participants killer ON killer.participant_id=c.killer_participant_id" : "";
                String killer = "participant_deaths".equals(table) ?
                        "-'killer_participant_id'||jsonb_build_object('killer_game_id',killer.game_id,'killer_nickname',killer.nickname)" : "";
                // Normalize generated IDs by natural relationships only for the cross-DB comparison.
                String projection = includeIdentities ? "to_jsonb(c)" :
                        "(to_jsonb(c)-'participant_id'" + killer + ")||jsonb_build_object('game_id',p.game_id,'nickname',p.nickname)";
                for (String json : database.queryForList("SELECT (" + projection + ")::text FROM " + table
                        + " c JOIN participants p ON p.participant_id=c.participant_id " + join
                        + " WHERE p.game_id=?", String.class, id)) rows.add(JSON.readTree(json));
            }
            snapshot.put(table, sorted(rows));
        }
        return snapshot;
    }

    static void compare(Map<String,List<JsonNode>> expected, Map<String,List<JsonNode>> actual) {
        for (String table : expected.keySet()) {
            var wanted = expected.get(table);
            var received = actual.get(table);
            require(wanted.size() == received.size(), "Row count mismatch: " + table);
            for (int index = 0; index < wanted.size(); index++) {
                JsonNode left = wanted.get(index);
                JsonNode right = received.get(index);
                if (!left.equals(right)) {
                    var fields = new java.util.TreeSet<String>();
                    left.fieldNames().forEachRemaining(fields::add);
                    right.fieldNames().forEachRemaining(fields::add);
                    fields.removeIf(field -> java.util.Objects.equals(left.get(field), right.get(field)));
                    // Values, row keys and nicknames are deliberately never included in test failures.
                    throw new AssertionError("V4 comparison mismatch: " + table + " columns=" + fields);
                }
            }
        }
    }

    static void verifyUserStatistics(JdbcTemplate source, List<Long> gameIds) {
        var ids = new java.util.HashSet<Long>();
        for (long gameId : gameIds) ids.addAll(source.queryForList(
                "SELECT user_id FROM participants WHERE game_id=?", Long.class, gameId));
        for (long userId : ids) {
            var actual = source.queryForMap("SELECT games_seen,last_game_id,last_game_at,last_mmr,tier FROM users WHERE user_id=?", userId);
            var latest = source.queryForMap("""
                    SELECT p.game_id,g.start_dtm,p.mmr_after,g.season_id FROM participants p
                    JOIN games g ON g.game_id=p.game_id WHERE p.user_id=?
                    ORDER BY g.start_dtm DESC NULLS LAST,p.game_id DESC LIMIT 1
                    """, userId);
            long count = source.queryForObject("SELECT count(*) FROM participants WHERE user_id=?", Long.class, userId);
            require(((Number)actual.get("games_seen")).longValue() == count, "User games_seen mismatch");
            require(java.util.Objects.equals(actual.get("last_game_id"), latest.get("game_id")), "User last_game_id mismatch");
            require(java.util.Objects.equals(actual.get("last_game_at"), latest.get("start_dtm")), "User last_game_at mismatch");
            require(java.util.Objects.equals(actual.get("last_mmr"), latest.get("mmr_after")), "User last_mmr mismatch");
            var tiers = source.queryForList("SELECT tier FROM tiers WHERE season_id=? AND min_mmr<=? ORDER BY min_mmr DESC LIMIT 1",
                    String.class, latest.get("season_id"), latest.get("mmr_after"));
            require(java.util.Objects.equals(actual.get("tier"), tiers.isEmpty() ? null : tiers.getFirst()), "User tier mismatch");
        }
    }

    private static List<JsonNode> sorted(List<JsonNode> rows) {
        rows.sort(Comparator.comparing(JsonNode::toString));
        return List.copyOf(rows);
    }

    static void require(boolean condition, String safeMessage) {
        if (!condition) throw new AssertionError(safeMessage);
    }

    private static Integer integer(JsonNode node, String key) {
        return node.hasNonNull(key) ? node.get(key).intValue() : null;
    }
    private static String text(JsonNode node, String key) {
        return node.hasNonNull(key) ? node.get(key).asText() : null;
    }
    private Stage5Reference() {}
}
