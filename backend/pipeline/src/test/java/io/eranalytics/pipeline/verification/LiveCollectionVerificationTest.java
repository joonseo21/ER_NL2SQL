package io.eranalytics.pipeline.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.eranalytics.pipeline.collection.*;
import io.eranalytics.pipeline.collection.mapping.RawFieldPolicy;
import io.eranalytics.pipeline.erapi.ApiException;
import io.eranalytics.pipeline.erapi.BattleUserResultReader;
import io.eranalytics.pipeline.erapi.ErApiClient;
import io.eranalytics.pipeline.erapi.dto.UserGamesPage;
import io.eranalytics.pipeline.migration.DatabaseMigration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.util.*;

import static io.eranalytics.pipeline.verification.Stage5Reference.require;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;

/** Opt-in only. At most three users, two pages each and two game response targets, at 0.5 RPS. */
@Tag("live")
@SpringBootTest(properties = {
        "er.collection.enabled=false", "er.collection.max-pages-per-user=2", "er.api.requests-per-second=0.5",
        "logging.level.org.hibernate.engine.jdbc.spi.SqlExceptionHelper=OFF"
})
class LiveCollectionVerificationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_USERS = 3;
    private static final int MAX_GAMES = 2;
    @MockitoSpyBean ErApiClient api;
    @Autowired CollectorRepository collector;
    @Autowired UserFrontierRepository frontier;
    @Autowired UserGameCrawler crawler;
    @Autowired GameJobProcessor processor;
    @Autowired CharacterMetadataCollector metadata;
    @Autowired CollectionRetry retry;
    @Autowired JdbcTemplate source;
    private final Map<Long,List<JsonNode>> inputs = new LinkedHashMap<>();
    private final Set<Long> seenGames = new LinkedHashSet<>();
    private final Set<String> attemptedGames = new LinkedHashSet<>();
    private final List<String> resolvedIds = new ArrayList<>();
    private int pages;
    private int nextRequests;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws Exception {
        String url = Stage5DatabaseGuard.copyUrl();
        Stage5DatabaseGuard.captures();
        try (var connection = Stage5DatabaseGuard.copyOwner(); var sql = connection.createStatement()) {
            var result = sql.executeQuery("SELECT max(version::integer) FROM flyway_schema_history "
                    + "WHERE success AND version IS NOT NULL");
            result.next();
            require(result.getInt(1) == 4, "Prepare and validate the copied DB with V4 first");
        }
        try (var ignored = Stage5DatabaseGuard.referenceOwner()) { /* Verify before any reference writes. */ }
        DatabaseMigration.configure(url, Stage5DatabaseGuard.OWNER, "").load().validate();
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> "collector");
        properties.add("spring.datasource.password", () -> "");
    }

    @Test
    @SuppressWarnings("unchecked")
    void verifiesBoundedCollectionV4ParityFreshInsertAndReplayOnProductionCopy() throws Exception {
        require(apiKeyConfigured(), "Set ER_API_KEY privately before live verification");
        long originalGames = count("games");
        long originalParticipants = count("participants");
        long firstLog = source.queryForObject("SELECT coalesce(max(id),0) FROM api_call_log", Long.class);
        // Isolate copied work from the validation budget. Only copy statuses change; source data stays intact.
        int pausedJobs;
        try (var owner = Stage5DatabaseGuard.copyOwner(); var sql = owner.createStatement()) {
            pausedJobs = sql.executeUpdate("UPDATE collect_queue SET status='DONE' "
                    + "WHERE status IN ('PENDING','RETRY')");
        }
        var privateDirectory = Stage5DatabaseGuard.captures();
        doAnswer(invocation -> {
            String uid = (String) invocation.callRealMethod();
            resolvedIds.add(uid);
            return uid;
        }).when(api).resolveUser(anyString());
        doAnswer(invocation -> {
            if (invocation.getArgument(1) != null) nextRequests++;
            UserGamesPage page = (UserGamesPage) invocation.callRealMethod();
            pages++;
            page.userGames().stream().filter(game -> game.seasonId() == 41 && game.matchingMode() == 3
                    && game.matchingTeamMode() == 3 && game.versionMajor() >= 4)
                    .forEach(game -> seenGames.add(game.gameId()));
            return page;
        }).when(api).userGames(anyString(), nullable(Long.class));
        doAnswer(invocation -> {
            String target = invocation.getArgument(0);
            require(attemptedGames.contains(target) || attemptedGames.size() < MAX_GAMES,
                    "Game request target budget exceeded");
            attemptedGames.add(target);
            List<JsonNode> rows = (List<JsonNode>) invocation.callRealMethod();
            long gameId = rows.getFirst().path("gameId").longValue();
            require(inputs.containsKey(gameId) || inputs.size() < MAX_GAMES, "Game response budget exceeded");
            inputs.put(gameId, rows);
            var payload = JSON.createObjectNode();
            var array = payload.putArray("userGames");
            rows.forEach(array::add);
            Files.writeString(privateDirectory.resolve("game-" + gameId + ".json"), payload.toString());
            return rows;
        }).when(api).gameParticipants(anyString());

        metadata.refreshIfDue();
        require(frontier.hasUsers(41), "The production copy must seed users through V4");
        int usersVisited = 0;
        while (attemptedGames.size() < MAX_GAMES && usersVisited < MAX_USERS) {
            var user = frontier.selectNext(41, 4, 3600, OffsetDateTime.now().minusHours(4));
            if (user.isEmpty()) break;
            crawler.crawl(user.get());
            usersVisited++;
            drainBoundedQueue();
        }
        // If all observed games already existed in the snapshot, validate their real responses as explicit
        // replay jobs on the copy. This does not change the production discovery/enqueue policy.
        int replayJobs = 0;
        for (long id : seenGames) {
            if (attemptedGames.size() >= MAX_GAMES) break;
            if (inputs.containsKey(id)) continue;
            try (var owner = Stage5DatabaseGuard.copyOwner(); var statement = owner.prepareStatement(
                    "DELETE FROM collect_queue WHERE job_type='GAME' AND target_key=?")) {
                statement.setString(1, Long.toString(id));
                statement.executeUpdate();
            }
            collector.enqueueGame(id);
            drainBoundedQueue();
            replayJobs++;
        }
        require(!inputs.isEmpty(), "No bounded real game responses collected");
        require(pages <= MAX_USERS * 2, "User page budget exceeded");
        var ids = List.copyOf(inputs.keySet());
        for (long id : ids) {
            require(collector.gameExists(id), "Captured game was not persisted; inspect sanitized queue status");
            require(source.queryForObject("SELECT count(*) FROM collect_queue WHERE job_type='GAME' AND target_key=?",
                    Integer.class, Long.toString(id)) == 0, "Successful game job was not deleted");
        }

        Stage5Reference.prepare(inputs, source);
        var expected = Stage5Reference.snapshot(Stage5Reference.referenceJdbc(), ids, false);
        Stage5Reference.compare(expected, Stage5Reference.snapshot(source, ids, false));
        Stage5Reference.verifyUserStatistics(source, ids);
        var beforeReplay = Stage5Reference.snapshot(source, ids, true);
        for (var rows : inputs.values()) collector.upsertGame(rows);
        Stage5Reference.compare(beforeReplay, Stage5Reference.snapshot(source, ids, true));
        Stage5Reference.verifyUserStatistics(source, ids);

        // Exercise brand-new participant/child insertion using the same real API payloads.
        // Only these <=2 games in the isolated copy are removed; users and unrelated copied games stay.
        for (long id : ids) {
            try (var owner = Stage5DatabaseGuard.copyOwner(); var statement = owner.prepareStatement(
                    "DELETE FROM games WHERE game_id=?")) {
                statement.setLong(1, id);
                statement.executeUpdate();
            }
            collector.upsertGame(inputs.get(id));
        }
        // The obsolete ranker flag defaults false on a new row; preserve it in the independent V1 reference.
        Stage5Reference.referenceJdbc().update("UPDATE participants SET is_ranker=false");
        expected = Stage5Reference.snapshot(Stage5Reference.referenceJdbc(), ids, false);
        Stage5Reference.compare(expected, Stage5Reference.snapshot(source, ids, false));
        Stage5Reference.verifyUserStatistics(source, ids);

        var moved = new RawFieldPolicy(new BattleUserResultReader(JSON)).movedKeys();
        require(moved.size() == 161, "Moved raw key contract changed");
        int participantRows = 0;
        for (long id : ids) {
            for (String raw : source.queryForList("SELECT raw::text FROM participants WHERE game_id=?", String.class, id)) {
                JsonNode node = JSON.readTree(raw);
                require(moved.stream().noneMatch(node::has), "A promoted key remains in fresh raw");
                participantRows++;
            }
        }
        boolean end404 = false;
        if (!resolvedIds.isEmpty()) {
            try {
                retry.call("stage5 list end probe", () -> api.userGames(resolvedIds.getFirst(), 1L));
            } catch (ApiException error) {
                require(error.getStatusCode() == 404, "Unexpected list end probe API code");
                end404 = true;
            }
        }
        var report = JSON.createObjectNode();
        report.put("status", "PASS");
        report.put("original_games", originalGames);
        report.put("original_participants", originalParticipants);
        report.put("copied_pending_jobs_paused", pausedJobs);
        report.put("users_visited", usersVisited);
        report.put("pages_returned", pages);
        report.put("next_requests", nextRequests);
        report.put("list_end_404", end404);
        report.put("explicit_existing_game_replay_jobs", replayJobs);
        report.put("games_verified", ids.size());
        report.put("game_targets_attempted", attemptedGames.size());
        report.put("participants_verified", participantRows);
        report.put("moved_raw_keys", moved.size());
        report.put("v4_parity", true);
        report.put("identity_preserving_replay", true);
        report.put("fresh_insert_parity", true);
        report.put("user_statistics", true);
        report.put("final_games", count("games"));
        report.put("final_participants", count("participants"));
        report.set("calls", JSON.valueToTree(source.queryForList("""
                SELECT endpoint,status_code,count(*) AS calls FROM api_call_log WHERE id>?
                GROUP BY endpoint,status_code ORDER BY endpoint,status_code
                """, firstLog)));
        Files.writeString(privateDirectory.resolve("verification-summary.json"), report.toPrettyString());
        System.out.println(report.toPrettyString());
    }

    private boolean apiKeyConfigured() {
        // Spring may read a private API-key file as well as environment/.env.
        return System.getenv("ER_API_KEY") != null && !System.getenv("ER_API_KEY").isBlank();
    }

    private void drainBoundedQueue() {
        while (attemptedGames.size() < MAX_GAMES) {
            var job = collector.claimNextGame();
            if (job.isEmpty()) return;
            processor.process(job.get());
        }
    }

    private long count(String table) {
        // Fixed test identifiers only, never user input.
        return source.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}
