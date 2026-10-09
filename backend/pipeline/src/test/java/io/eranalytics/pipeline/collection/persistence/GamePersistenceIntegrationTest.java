package io.eranalytics.pipeline.collection.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.eranalytics.pipeline.collection.BattleUserResultMapper;
import io.eranalytics.pipeline.collection.CollectorRepository;
import io.eranalytics.pipeline.collection.UserFrontierRepository;
import io.eranalytics.pipeline.collection.model.CrawlUser;
import io.eranalytics.pipeline.collection.model.SeedUser;
import java.time.OffsetDateTime;
import java.util.stream.IntStream;
import io.eranalytics.pipeline.collection.model.DeathRow;
import io.eranalytics.pipeline.collection.model.MappedMatch;
import io.eranalytics.pipeline.collection.model.MappedParticipant;
import io.eranalytics.pipeline.migration.DatabaseMigration;
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
    @Autowired UserFrontierRepository frontier;

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
            // Only after the dedicated database identity check above.
            sql.execute("DROP SCHEMA public CASCADE");
            sql.execute("CREATE SCHEMA public");
        }
        DatabaseMigration.configure(url, "stage2_test", "").load().migrate();
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
            sql.execute("TRUNCATE games, users, characters, rankers, collect_queue, api_call_log RESTART IDENTITY CASCADE");
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

    @Test
    void seedsAll1000UsersOnlyForEmptySeasonAndPreservesExistingCrawlState() {
        var seeds = IntStream.range(0, 1000).mapToObj(i -> new SeedUser("synthetic_seed_" + i, 8000)).toList();
        frontier.seedIfEmpty(41, seeds);
        assertThat(count("users")).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE crawl_status='NEW' "
                + "AND last_mmr=8000 AND tier='미스릴 이상'", Integer.class)).isEqualTo(1000);
        assertThat(count("rankers")).isZero();
        jdbc.update("UPDATE users SET crawl_status='NOT_FOUND' WHERE nickname='synthetic_seed_0'");
        frontier.seedIfEmpty(41, List.of(new SeedUser("synthetic_added", 5000)));
        assertThat(count("users")).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT crawl_status FROM users WHERE nickname='synthetic_seed_0'",
                String.class)).isEqualTo("NOT_FOUND");
        assertThat(frontier.hasUsers(40)).isFalse();
    }

    @Test
    void selectsByLatestPatchParticipantCountsWithinConfiguredSeasonRatherThanAllTime() {
        seedCandidates();
        sample(101, 41, 4, 1, "다이아몬드", 10);
        sample(102, 41, 5, 1, "플래티넘", 4);
        sample(103, 41, 5, 1, "다이아몬드", 1);
        sample(104, 41, 5, 1, "메테오라이트", 2);
        sample(105, 41, 5, 1, "미스릴 이상", 3);
        sample(106, 40, 99, 1, "다이아몬드", 30);
        sample(107, 41, 5, 0, "다이아몬드", 20);
        assertThat(nextCandidate().nickname()).isEqualTo("synthetic_diamond");
    }

    @Test
    void includesZeroSampleTierAndSkipsIneligibleCandidatesBeforeMovingToNextTier() {
        seedCandidates();
        sample(101, 41, 5, 1, "플래티넘", 1);
        assertThat(nextCandidate().nickname()).isEqualTo("synthetic_diamond");
        jdbc.update("UPDATE users SET crawl_status='NOT_FOUND' WHERE tier='다이아몬드'");
        jdbc.update("UPDATE users SET crawl_status='ERROR' WHERE tier='메테오라이트'");
        assertThat(nextCandidate().nickname()).isEqualTo("synthetic_mithril");
        jdbc.update("UPDATE users SET crawl_status='DONE', last_crawled_at=now() WHERE tier='미스릴 이상'");
        assertThat(nextCandidate().nickname()).isEqualTo("synthetic_platinum");
        jdbc.update("UPDATE users SET last_mmr=3599 WHERE tier='플래티넘'");
        assertThat(frontier.selectNext(41, 4, 3600, OffsetDateTime.now().minusHours(4))).isEmpty();
    }

    @Test
    void choosesNewBeforeOldDoneAndThenOldestEligibleDone() {
        frontier.seedIfEmpty(41, List.of(new SeedUser("synthetic_new", 5000),
                new SeedUser("synthetic_old", 5000), new SeedUser("synthetic_recent", 5000)));
        jdbc.update("UPDATE users SET crawl_status='DONE', last_crawled_at=now()-interval '8 hours' "
                + "WHERE nickname='synthetic_old'");
        jdbc.update("UPDATE users SET crawl_status='DONE', last_crawled_at=now()-interval '5 hours' "
                + "WHERE nickname='synthetic_recent'");
        assertThat(nextCandidate().nickname()).isEqualTo("synthetic_new");
        jdbc.update("UPDATE users SET crawl_status='DONE', last_crawled_at=now() WHERE nickname='synthetic_new'");
        assertThat(nextCandidate().nickname()).isEqualTo("synthetic_old");
        jdbc.update("UPDATE users SET last_crawled_at=now() WHERE nickname='synthetic_old'");
        assertThat(nextCandidate().nickname()).isEqualTo("synthetic_recent");
        jdbc.update("UPDATE users SET last_crawled_at=now()");
        assertThat(frontier.selectNext(41, 4, 3600, OffsetDateTime.now().minusHours(4))).isEmpty();
    }

    @Test
    void crawlCompletionAndFailureKeepStatisticsAndPreviousMarkerWhenIncomplete() throws Exception {
        collector.upsertGame(fixture());
        jdbc.update("UPDATE users SET crawled_newest_game_id=100 WHERE nickname='synthetic_alpha'");
        var row = jdbc.queryForMap("SELECT user_id,season_id,nickname FROM users WHERE nickname='synthetic_alpha'");
        CrawlUser user = new CrawlUser(((Number)row.get("user_id")).longValue(), 41, "synthetic_alpha", 100L);
        int mmr = jdbc.queryForObject("SELECT last_mmr FROM users WHERE user_id=?", Integer.class, user.userId());
        frontier.complete(user, 200L, false);
        assertThat(jdbc.queryForObject("SELECT crawled_newest_game_id FROM users WHERE user_id=?",
                Long.class, user.userId())).isEqualTo(100L);
        frontier.complete(user, 200L, true);
        frontier.fail(user, false, "ER API code 503");
        var result = jdbc.queryForMap("SELECT crawl_status,crawl_error,crawled_newest_game_id,last_mmr,games_seen "
                + "FROM users WHERE user_id=?", user.userId());
        assertThat(result).containsEntry("crawl_status", "ERROR").containsEntry("crawl_error", "ER API code 503")
                .containsEntry("crawled_newest_game_id", 200L).containsEntry("last_mmr", mmr)
                .containsEntry("games_seen", 1);
        frontier.complete(user, null, true);
        assertThat(jdbc.queryForObject("SELECT crawled_newest_game_id FROM users WHERE user_id=?",
                Long.class, user.userId())).isEqualTo(200L);
        frontier.fail(user, true, "ER API code 404");
        assertThat(jdbc.queryForObject("SELECT crawl_status FROM users WHERE user_id=?",
                String.class, user.userId())).isEqualTo("NOT_FOUND");
    }

    @Test
    void processesOnlyGameQueueAndDeletesSuccessAtomicallyWithPersistence() throws Exception {
        long gameId = fixture().getFirst().get("gameId").longValue();
        jdbc.update("INSERT INTO collect_queue(job_type,target_key) VALUES ('USER','legacy-uid')");
        collector.enqueueGame(gameId);
        collector.enqueueGame(gameId);
        var job = collector.claimNextGame().orElseThrow();
        assertThat(job.attempts()).isEqualTo(1);
        assertThat(job.jobType()).isEqualTo("GAME");
        collector.completeGame(job, fixture());
        assertThat(collector.gameExists(gameId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM collect_queue WHERE job_type='GAME'", Integer.class)).isZero();
        assertThat(collector.claimNextGame()).isEmpty();
        assertThat(count("collect_queue")).isEqualTo(1);
    }

    @Test
    void failedJobsKeepAttemptsAndReasonAndDoNotReenqueueOrBlockNextJob() {
        collector.enqueueGame(200);
        collector.enqueueGame(300);
        var first = collector.claimNextGame().orElseThrow();
        collector.recordAttempt(first, 5);
        collector.markFailure(new io.eranalytics.pipeline.collection.QueueJob(first.id(), "GAME", "200", 5),
                "ER API code 503", true);
        collector.enqueueGame(200);
        assertThat(jdbc.queryForMap("SELECT status,attempts,last_error FROM collect_queue WHERE id=?", first.id()))
                .containsEntry("status", "FAILED").containsEntry("attempts", 5).containsEntry("last_error", "ER API code 503");
        assertThat(collector.claimNextGame().orElseThrow().targetKey()).isEqualTo("300");
    }

    @Test
    void restartFinalizesExhaustedPendingAttemptAndDoesNotGrantSixthTry() {
        collector.enqueueGame(200);
        var job = collector.claimNextGame().orElseThrow();
        collector.recordAttempt(job, 5);
        assertThat(collector.claimNextGame()).isEmpty();
        assertThat(jdbc.queryForMap("SELECT status,attempts FROM collect_queue WHERE id=?", job.id()))
                .containsEntry("status", "FAILED").containsEntry("attempts", 5);
    }

    @Test
    void queueDeleteFailureRollsBackGameUsersParticipantsAndChildren() throws Exception {
        long gameId = fixture().getFirst().get("gameId").longValue();
        collector.enqueueGame(gameId);
        var job = collector.claimNextGame().orElseThrow();
        try (Connection connection = admin(); Statement sql = connection.createStatement()) {
            sql.execute("""
                    CREATE FUNCTION fail_test_queue_delete() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN RAISE EXCEPTION 'synthetic queue delete failure'; END $$
                    """);
            sql.execute("CREATE TRIGGER fail_test_queue_delete BEFORE DELETE ON collect_queue "
                    + "FOR EACH ROW EXECUTE FUNCTION fail_test_queue_delete()");
        }
        try {
            assertThatThrownBy(() -> collector.completeGame(job, fixture())).isInstanceOf(RuntimeException.class);
            assertThat(count("games")).isZero();
            assertThat(count("users")).isZero();
            assertThat(count("participants")).isZero();
            for (String child : CHILDREN) assertThat(count(child)).isZero();
            assertThat(count("collect_queue")).isEqualTo(1);
        } finally {
            try (Connection connection = admin(); Statement sql = connection.createStatement()) {
                sql.execute("DROP TRIGGER fail_test_queue_delete ON collect_queue");
                sql.execute("DROP FUNCTION fail_test_queue_delete()");
            }
        }
    }

    @Test
    void incorrectGameResponseCannotDeleteTheQueuedJob() throws Exception {
        long id = fixture().getFirst().get("gameId").longValue();
        collector.enqueueGame(id + 1);
        var job = collector.claimNextGame().orElseThrow();
        assertThatThrownBy(() -> collector.completeGame(job, fixture()))
                .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasMessage("Game response does not match queue target");
        assertThat(count("games")).isZero();
        assertThat(count("collect_queue")).isEqualTo(1);
    }

    @Test
    void missingLocalizationDoesNotEraseExistingKoreanName() {
        collector.upsertCharacter(1, "가상 실험체", "Synthetic");
        collector.upsertCharacter(1, null, "Synthetic revised");
        assertThat(jdbc.queryForMap("SELECT name_ko,name_en FROM characters WHERE character_code=1"))
                .containsEntry("name_ko", "가상 실험체").containsEntry("name_en", "Synthetic revised");
    }

    private CrawlUser nextCandidate() {
        return frontier.selectNext(41, 4, 3600, OffsetDateTime.now().minusHours(4)).orElseThrow();
    }

    private void seedCandidates() {
        frontier.seedIfEmpty(41, List.of(new SeedUser("synthetic_platinum", 3600),
                new SeedUser("synthetic_diamond", 5000), new SeedUser("synthetic_meteorite", 6400),
                new SeedUser("synthetic_mithril", 7600), new SeedUser("synthetic_below", 3599)));
    }

    private void sample(long gameId, int season, int major, int minor, String tier, int count) {
        jdbc.update("INSERT INTO games(game_id,season_id,matching_mode,matching_team_mode,version_major,version_minor) "
                + "VALUES (?,?,3,3,?,?)", gameId, season, major, minor);
        for (int i = 0; i < count; i++) {
            jdbc.update("INSERT INTO participants(game_id,nickname,tier,version_major,version_minor,raw) "
                    + "VALUES (?,?,?,?,?,'{}'::jsonb)", gameId, "synthetic_sample_" + i, tier, major, minor);
        }
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
