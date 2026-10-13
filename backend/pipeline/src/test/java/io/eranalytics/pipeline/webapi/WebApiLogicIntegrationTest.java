package io.eranalytics.pipeline.webapi;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.eranalytics.pipeline.webapi.client.AgentClient;
import io.eranalytics.pipeline.webapi.client.MockAgentClient;
import io.eranalytics.pipeline.webapi.dto.WebApiRequests;
import io.eranalytics.pipeline.webapi.entity.DailyUsageEntity;
import io.eranalytics.pipeline.webapi.entity.DailyUsageId;
import io.eranalytics.pipeline.webapi.entity.InviteCodeEntity;
import io.eranalytics.pipeline.webapi.repository.DailyUsageRepository;
import io.eranalytics.pipeline.webapi.repository.FeedbackRepository;
import io.eranalytics.pipeline.webapi.repository.InviteCodeRepository;
import io.eranalytics.pipeline.webapi.repository.QuestionLogRepository;
import io.eranalytics.pipeline.webapi.repository.QuestionRequestRepository;
import io.eranalytics.pipeline.webapi.service.QuestionAccepted;
import io.eranalytics.pipeline.webapi.service.QuestionFailure;
import io.eranalytics.pipeline.webapi.service.QuestionService;
import io.eranalytics.pipeline.webapi.service.UsageService;
import io.eranalytics.pipeline.webapi.service.WebApiClock;
import io.eranalytics.pipeline.webapi.service.WebApiException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Tag("postgres")
@SpringBootTest(
        properties = {
            "er.collection.enabled=false",
            "er.web-api.enabled=true",
            "er.web-api.agent.mode=mock",
            "spring.config.import="
        })
@AutoConfigureMockMvc
@Import(WebApiLogicIntegrationTest.TimeConfiguration.class)
class WebApiLogicIntegrationTest {
    private static final String CODE = "logic-test-invite";
    private static final Instant NOW = Instant.parse("2026-10-12T14:59:59Z");
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired InviteCodeRepository invites;
    @Autowired DailyUsageRepository usage;
    @Autowired QuestionLogRepository logs;
    @Autowired FeedbackRepository feedback;
    @Autowired QuestionService questions;
    @Autowired QuestionRequestRepository requests;
    @Autowired UsageService usageService;
    @Autowired ApplicationEventPublisher events;
    @Autowired TestClock clock;
    @MockitoBean AgentClient agent;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws Exception {
        String url = testUrl();
        try (Connection connection = DriverManager.getConnection(url, "stage2_test", "");
                Statement sql = connection.createStatement()) {
            sql.execute(
                    "DO $$ BEGIN IF current_database() <> 'er_webapi_logic_test' THEN RAISE EXCEPTION 'Wrong test database'; END IF; END $$");
            sql.execute("DROP SCHEMA public CASCADE");
            sql.execute("CREATE SCHEMA public");
        }
        Flyway.configure()
                .dataSource(url, "stage2_test", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        assertThat(
                        Flyway.configure()
                                .dataSource(url, "stage2_test", "")
                                .load()
                                .info()
                                .current()
                                .getVersion()
                                .getVersion())
                .isEqualTo("8");
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> "collector");
        properties.add("spring.datasource.password", () -> "");
        properties.add("er.web-api.datasource.url", () -> url);
        properties.add("er.web-api.datasource.username", () -> "api_server");
        properties.add("er.web-api.datasource.password", () -> "");
    }

    private static String testUrl() {
        String url = System.getenv("WEB_API_LOGIC_TEST_DATABASE_URL");
        if (url == null
                || !url.matches(
                        "jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/er_webapi_logic_test")) {
            throw new IllegalStateException(
                    "WEB_API_LOGIC_TEST_DATABASE_URL must target dedicated local er_webapi_logic_test");
        }
        return url;
    }

    @BeforeEach
    void prepare() throws Exception {
        await().atMost(Duration.ofSeconds(10))
                .until(() -> requests.countByStatusIn(List.of("accepted", "processing")) == 0);
        try (Connection connection = DriverManager.getConnection(testUrl(), "stage2_test", "");
                Statement sql = connection.createStatement()) {
            sql.execute(
                    "TRUNCATE question_requests,feedback,question_logs,daily_usage,invite_codes");
            sql.execute("TRUNCATE games,users CASCADE");
        }
        clock.now = NOW;
        invites.save(new InviteCodeEntity(CODE, "synthetic", NOW));
        MockAgentClient mock = new MockAgentClient(json, "answer");
        when(agent.query(anyString(), any()))
                .thenAnswer(call -> mock.query(call.getArgument(0), call.getArgument(1)));
    }

    @Test
    void summaryCountsCurrentUsersAndHistoricalAppearancesAndCachesSnapshot() throws Exception {
        try (Connection connection = DriverManager.getConnection(testUrl(), "stage2_test", "");
                Statement sql = connection.createStatement()) {
            sql.execute(
                    """
                    INSERT INTO games(game_id,season_id,matching_mode,matching_team_mode,version_major,version_minor,start_dtm,fetched_at)
                    VALUES(101,41,3,3,4,2,'2026-10-10T00:00:00Z','2026-10-12T00:00:00Z'),
                          (102,41,3,3,4,3,'2026-10-11T00:00:00Z','2026-10-13T00:00:00Z'),
                          (103,40,3,3,4,1,NULL,now()),(104,41,2,3,4,4,NULL,now())
                    """);
            sql.execute(
                    """
                    INSERT INTO users(season_id,nickname,tier)
                    VALUES(41,'synthetic-user','다이아몬드'),(40,'excluded-season','플래티넘')
                    """);
            sql.execute(
                    """
                    INSERT INTO participants(game_id,nickname,tier,raw)
                    VALUES(101,'synthetic-user','플래티넘','{}'),(101,'another','플래티넘','{}'),
                          (102,'synthetic-user','다이아몬드','{}'),(104,'excluded-mode','플래티넘','{}')
                    """);
        }
        http.perform(get("/api/collection/summary")).andExpect(status().isUnauthorized());
        JsonNode result = collectionSummary();
        assertThat(result.path("total_games").asInt()).isEqualTo(2);
        assertThat(result.path("latest_collected_at").asText()).isEqualTo("2026-10-13T00:00:00Z");
        assertThat(result.path("patches").size()).isEqualTo(2);
        assertThat(tier(result, "다이아몬드").path("users").asInt()).isEqualTo(1);
        assertThat(tier(result, "플래티넘").path("users").asInt()).isZero();
        assertThat(tier(result, "플래티넘").path("participant_appearances").asInt()).isEqualTo(2);
        assertThat(tier(result, "다이아몬드").path("participant_appearances").asInt()).isEqualTo(1);
        String conditions =
                http.perform(get("/api/conditions").header("X-Invite-Code", CODE))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(result.path("patches")).isEqualTo(json.readTree(conditions).path("patches"));
        try (Connection connection = DriverManager.getConnection(testUrl(), "stage2_test", "");
                Statement sql = connection.createStatement()) {
            sql.execute("DELETE FROM games WHERE game_id=102");
        }
        clock.now = NOW.plusSeconds(299);
        assertThat(collectionSummary()).isEqualTo(result);
        clock.now = NOW.plusSeconds(300);
        assertThat(collectionSummary().path("total_games").asInt()).isEqualTo(1);
    }

    private JsonNode collectionSummary() throws Exception {
        String body =
                http.perform(get("/api/collection/summary").header("X-Invite-Code", CODE))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    private static JsonNode tier(JsonNode summary, String name) {
        for (JsonNode bucket : summary.path("by_tier")) {
            if (name.equals(bucket.path("tier").asText())) return bucket;
        }
        throw new AssertionError("Missing tier: " + name);
    }

    @Test
    void persistsAgentFailuresWithoutResendingOrLeakingDetails() throws Exception {
        for (QuestionFailure failure :
                List.of(
                        QuestionFailure.AGENT_AUTH_FAILED,
                        QuestionFailure.AGENT_REQUEST_REJECTED,
                        QuestionFailure.AGENT_INTERNAL_ERROR,
                        QuestionFailure.AGENT_BUSY,
                        QuestionFailure.AGENT_CONNECTION_FAILED,
                        QuestionFailure.AGENT_TIMEOUT)) {
            doThrow(failure.exception()).when(agent).query(anyString(), any());
            String body =
                    http.perform(
                                    post("/api/questions")
                                            .header("X-Invite-Code", CODE)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{\"question\":\"질문\"}"))
                            .andExpect(status().isAccepted())
                            .andReturn()
                            .getResponse()
                            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            String id = json.readTree(body).path("request_id").asText();
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(
                            () -> {
                                io.eranalytics.pipeline.webapi.entity.QuestionRequestEntity stored =
                                        requests.findById(id).orElseThrow();
                                assertThat(stored.getStatus()).isEqualTo("failed");
                                assertThat(stored.getErrorCode()).isEqualTo(failure.name());
                                http.perform(
                                                get("/api/questions/" + id)
                                                        .header("X-Invite-Code", CODE))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.error_code").value(failure.name()))
                                        .andExpect(
                                                jsonPath("$.message")
                                                        .value(failure.exception().getMessage()));
                            });
        }
        verify(agent, times(6)).query(anyString(), any());
        assertThat(logs.count()).isZero();
    }

    @Test
    void recordsQuestionAndOverwritesOwnedFeedback() throws Exception {
        JsonNode response = ask();
        String id = response.path("log_id").asText();
        assertThat(response.path("remaining_today").asInt()).isEqualTo(49);
        assertThat(logs.findById(id).orElseThrow().getRows()).isEqualTo(response.path("rows"));
        for (String rating : List.of("like", "dislike")) {
            http.perform(
                            post("/api/questions/" + id + "/feedback")
                                    .header("X-Invite-Code", CODE)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            json.writeValueAsString(
                                                    java.util.Map.of(
                                                            "rating",
                                                            rating,
                                                            "comment",
                                                            "가".repeat(500)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.saved").value(true));
        }
        assertThat(feedback.count()).isEqualTo(1);
        assertThat(feedback.findById(id).orElseThrow().isLiked()).isFalse();
        invites.save(new InviteCodeEntity("other", null, NOW));
        http.perform(
                        post("/api/questions/" + id + "/feedback")
                                .header("X-Invite-Code", "other")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"rating\":\"like\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void limitsAtFiftyAndResetsAtKoreanMidnight() throws Exception {
        seed(49, 49);
        ask();
        http.perform(
                        post("/api/questions")
                                .header("X-Invite-Code", CODE)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"question\":\"질문\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("INVITE_LIMIT_EXCEEDED"));
        clock.now = Instant.parse("2026-10-12T15:00:00Z");
        assertThat(ask().path("remaining_today").asInt()).isEqualTo(49);
        assertThat(
                        usage.findById(new DailyUsageId(LocalDate.of(2026, 10, 12), "GLOBAL"))
                                .orElseThrow()
                                .getRequestCount())
                .isEqualTo(50);
    }

    @Test
    void globalLimitAppliesAcrossCodes() throws Exception {
        seed(0, 299);
        assertThat(ask().path("remaining_today").asInt()).isZero();
        invites.save(new InviteCodeEntity("other", null, NOW));
        http.perform(
                        post("/api/questions")
                                .header("X-Invite-Code", "other")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"question\":\"질문\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("GLOBAL_LIMIT_EXCEEDED"));
        assertLogsCount(1);
    }

    @Test
    void concurrentRequestsDoNotExceedPersonalOrGlobalLimit() throws Exception {
        seed(48, 298);
        List<Future<Boolean>> attempts = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(6)) {
            for (int index = 0; index < 12; index++) {
                attempts.add(
                        executor.submit(
                                () -> {
                                    try {
                                        questions.ask(
                                                CODE, new WebApiRequests.Question("질문", null));
                                        return true;
                                    } catch (WebApiException error) {
                                        assertThat(error.getStatus().value()).isEqualTo(429);
                                        return false;
                                    }
                                }));
            }
            int success = 0;
            for (Future<Boolean> result : attempts) {
                if (result.get()) success++;
            }
            assertThat(success).isEqualTo(2);
        }
        assertLogsCount(2);
        assertThat(
                        usage.findById(new DailyUsageId(LocalDate.of(2026, 10, 12), "GLOBAL"))
                                .orElseThrow()
                                .getRequestCount())
                .isEqualTo(300);
    }

    @Test
    void firstCounterCreationRacesPreserveAllCounts() throws Exception {
        List<Future<?>> attempts = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            for (int index = 0; index < 6; index++) {
                String code = "race-invite-" + index;
                invites.save(new InviteCodeEntity(code, "synthetic", NOW));
                attempts.add(
                        executor.submit(
                                () ->
                                        questions.ask(
                                                code, new WebApiRequests.Question("질문", null))));
            }
            for (Future<?> result : attempts) result.get();
        }
        assertLogsCount(6);
        assertThat(
                        usage.findById(new DailyUsageId(LocalDate.of(2026, 10, 12), "GLOBAL"))
                                .orElseThrow()
                                .getRequestCount())
                .isEqualTo(6);
    }

    @Test
    void differentCodesCompeteForLastGlobalSlotWithoutOverrun() throws Exception {
        seed(0, 299);
        invites.save(new InviteCodeEntity("other", null, NOW));
        List<Future<Boolean>> attempts = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            for (String code : List.of(CODE, "other", CODE, "other")) {
                attempts.add(
                        executor.submit(
                                () -> {
                                    try {
                                        questions.ask(
                                                code, new WebApiRequests.Question("질문", null));
                                        return true;
                                    } catch (WebApiException error) {
                                        assertThat(error.getCode())
                                                .isEqualTo("GLOBAL_LIMIT_EXCEEDED");
                                        return false;
                                    }
                                }));
            }
            int success = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get()) success++;
            }
            assertThat(success).isEqualTo(1);
        }
        assertLogsCount(1);
        assertThat(
                        usage.findById(new DailyUsageId(LocalDate.of(2026, 10, 12), "GLOBAL"))
                                .orElseThrow()
                                .getRequestCount())
                .isEqualTo(300);
    }

    @Test
    void malformedConditionsDoNotConsumeAndAsyncFailureIsRecorded() throws Exception {
        for (String conditions : List.of("null", "[]", "{\"character_code\":-1}")) {
            http.perform(
                            post("/api/questions")
                                    .header("X-Invite-Code", CODE)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"question\":\"질문\",\"conditions\":"
                                                    + conditions
                                                    + "}"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(usage.count()).isZero();
        when(agent.query(anyString(), any()))
                .thenThrow(new IllegalStateException("synthetic failure"));
        JsonNode receipt = questions.ask(CODE, new WebApiRequests.Question("질문", null));
        String id = receipt.path("request_id").asText();
        await().atMost(Duration.ofSeconds(5))
                .until(() -> "failed".equals(requests.findById(id).orElseThrow().getStatus()));
        assertThat(questions.find(CODE, id).path("error_code").asText())
                .isEqualTo("QUESTION_PROCESSING_FAILED");
        assertThat(
                        usage.findById(
                                        new DailyUsageId(
                                                LocalDate.of(2026, 10, 12), "INVITE:" + CODE))
                                .orElseThrow()
                                .getRequestCount())
                .isEqualTo(1);
        assertThat(logs.count()).isZero();
    }

    @Test
    void storesNoticeClarificationEmptyAndSummaryFailureWithoutLosingFields() throws Exception {
        for (String scenario :
                List.of(
                        "notice",
                        "clarification",
                        "answer-empty-result",
                        "answer-without-summary")) {
            MockAgentClient mock = new MockAgentClient(json, scenario);
            when(agent.query(anyString(), any()))
                    .thenAnswer(call -> mock.query(call.getArgument(0), call.getArgument(1)));
            JsonNode response = ask();
            assertThat(
                            logs.findById(response.path("log_id").asText())
                                    .orElseThrow()
                                    .getResponseType())
                    .isEqualTo(response.path("kind").asText());
        }
        assertLogsCount(4);
    }

    @Test
    void conditionsUsesRestrictedConnectionAndReturnsSortedLists() throws Exception {
        try (Connection connection = DriverManager.getConnection(testUrl(), "stage2_test", "");
                Statement sql = connection.createStatement()) {
            sql.execute(
                    "INSERT INTO games(game_id,season_id,matching_mode,matching_team_mode,version_major,version_minor,start_dtm) VALUES(990001,41,3,3,5,0,'2026-10-12T00:00:00Z') ON CONFLICT DO NOTHING");
            sql.execute(
                    "INSERT INTO characters(character_code,name_ko) VALUES(99999,'합성 실험체') ON CONFLICT DO NOTHING");
        }
        http.perform(get("/api/conditions").header("X-Invite-Code", CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patches[0].major").value(5))
                .andExpect(jsonPath("$.tiers").isNotEmpty())
                .andExpect(jsonPath("$.characters[0].name").value("합성 실험체"));
        assertThat(usage.count()).isZero();
    }

    private JsonNode ask() throws Exception {
        String body =
                http.perform(
                                post("/api/questions")
                                        .header("X-Invite-Code", CODE)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"question\":\"질문\"}"))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode receipt = json.readTree(body);
        String id = receipt.path("request_id").asText();
        await().atMost(Duration.ofSeconds(5))
                .until(() -> "completed".equals(requests.findById(id).orElseThrow().getStatus()));
        com.fasterxml.jackson.databind.node.ObjectNode result =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        questions.find(CODE, id).path("result");
        result.put("remaining_today", receipt.path("remaining_today").asInt());
        return result;
    }

    private void assertLogsCount(long count) {
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(logs.count()).isEqualTo(count));
    }

    @Test
    void rollbackNeverDispatchesAgent() {
        assertThatThrownBy(
                        () ->
                                usageService.withQuestion(
                                        CODE,
                                        remaining -> {
                                            events.publishEvent(
                                                    new QuestionAccepted("rolled-back"));
                                            throw new IllegalStateException("synthetic rollback");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(agent);
        assertThat(usage.count()).isZero();
        assertThat(requests.count()).isZero();
    }

    @Test
    void returnsReceiptWhileAgentIsBlockedAfterCommitOnAsyncThread() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MockAgentClient mock = new MockAgentClient(json, "answer");
        when(agent.query(anyString(), any()))
                .thenAnswer(
                        call -> {
                            assertThat(Thread.currentThread().getName())
                                    .startsWith("webapi-question-");
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            assertThat(requests.countByStatusIn(List.of("processing")))
                                    .isEqualTo(1);
                            started.countDown();
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Test worker release timed out");
                            }
                            return mock.query(call.getArgument(0), call.getArgument(1));
                        });
        try {
            String body =
                    http.perform(
                                    post("/api/questions")
                                            .header("X-Invite-Code", CODE)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{\"question\":\"질문\"}"))
                            .andExpect(status().isAccepted())
                            .andExpect(header().exists("Location"))
                            .andReturn()
                            .getResponse()
                            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            String id = json.readTree(body).path("request_id").asText();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            http.perform(get("/api/questions/" + id).header("X-Invite-Code", CODE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("processing"));
            invites.save(new InviteCodeEntity("other", null, NOW));
            http.perform(get("/api/questions/" + id).header("X-Invite-Code", "other"))
                    .andExpect(status().isNotFound());
            assertThat(logs.count()).isZero();
        } finally {
            release.countDown();
        }
        assertLogsCount(1);
    }

    private void seed(int personal, int global) {
        LocalDate date = LocalDate.of(2026, 10, 12);
        DailyUsageEntity perCode = new DailyUsageEntity(date, CODE);
        DailyUsageEntity total = new DailyUsageEntity(date, null);
        for (int index = 0; index < personal; index++) perCode.increment();
        for (int index = 0; index < global; index++) total.increment();
        usage.saveAll(List.of(total, perCode));
    }

    @TestConfiguration
    static class TimeConfiguration {
        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }
    }

    static class TestClock extends WebApiClock {
        volatile Instant now = NOW;

        TestClock() {
            super(Clock.systemUTC());
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
