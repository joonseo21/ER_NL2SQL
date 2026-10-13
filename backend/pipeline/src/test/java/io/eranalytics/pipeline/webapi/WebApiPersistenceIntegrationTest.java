package io.eranalytics.pipeline.webapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.eranalytics.pipeline.webapi.entity.DailyUsageEntity;
import io.eranalytics.pipeline.webapi.entity.FeedbackEntity;
import io.eranalytics.pipeline.webapi.entity.InviteCodeEntity;
import io.eranalytics.pipeline.webapi.entity.QuestionLogEntity;
import io.eranalytics.pipeline.webapi.entity.QuestionRequestEntity;
import io.eranalytics.pipeline.webapi.repository.DailyUsageRepository;
import io.eranalytics.pipeline.webapi.repository.FeedbackRepository;
import io.eranalytics.pipeline.webapi.repository.InviteCodeRepository;
import io.eranalytics.pipeline.webapi.repository.QuestionLogRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag("postgres")
@SpringBootTest(
        properties = {
            "er.collection.enabled=false",
            "er.web-api.enabled=true",
            "spring.config.import="
        })
class WebApiPersistenceIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-12T09:00:00Z");
    private static final String CODE = "synthetic-invite";

    @Autowired InviteCodeRepository invites;
    @Autowired DailyUsageRepository usage;
    @Autowired QuestionLogRepository questions;
    @Autowired FeedbackRepository feedback;

    @Autowired
    @Qualifier("jdbcTemplate") JdbcTemplate collectionJdbc;

    @Autowired
    @Qualifier("webApiJdbcTemplate") JdbcTemplate webJdbc;

    @Autowired
    @Qualifier("entityManagerFactory") EntityManagerFactory collectionEntities;

    @Autowired
    @Qualifier("webApiEntityManagerFactory") EntityManagerFactory webEntities;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws Exception {
        String url = testUrl();
        try (Connection connection = admin();
                Statement sql = connection.createStatement()) {
            ResultSet identity = sql.executeQuery("SELECT current_database(), current_user");
            identity.next();
            if (!"er_webapi_test".equals(identity.getString(1))
                    || !"stage2_test".equals(identity.getString(2))) {
                throw new IllegalStateException("Dedicated web test database identity required");
            }
            sql.execute(
                    """
                    DO $$ BEGIN
                        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='collector') THEN
                            CREATE ROLE collector LOGIN;
                        END IF;
                        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='agent_ro') THEN
                            CREATE ROLE agent_ro LOGIN;
                        END IF;
                        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='api_server') THEN
                            CREATE ROLE api_server LOGIN;
                        END IF;
                    END $$
                    """);
            sql.execute("DROP SCHEMA public CASCADE");
            sql.execute("CREATE SCHEMA public");
        }
        Flyway.configure()
                .dataSource(url, "stage2_test", "")
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load()
                .migrate();
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> "collector");
        properties.add("spring.datasource.password", () -> "");
        properties.add("er.web-api.datasource.url", () -> url);
        properties.add("er.web-api.datasource.username", () -> "api_server");
        properties.add("er.web-api.datasource.password", () -> "");
    }

    private static String testUrl() {
        String url = System.getenv("WEB_API_TEST_DATABASE_URL");
        if (url == null
                || !url.matches(
                        "jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/er_webapi_test")) {
            throw new IllegalStateException(
                    "WEB_API_TEST_DATABASE_URL must target a dedicated local er_webapi_test DB");
        }
        return url;
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(testUrl(), "stage2_test", "");
    }

    @BeforeEach
    void resetWebData() throws Exception {
        try (Connection connection = admin();
                Statement sql = connection.createStatement()) {
            sql.execute(
                    "TRUNCATE question_requests, feedback, question_logs, daily_usage, invite_codes");
        }
        invites.saveAndFlush(new InviteCodeEntity(CODE, "synthetic recipient", NOW));
    }

    @Test
    void separatesConnectionsEntitiesAndTransactionManagers() {
        assertThat(collectionJdbc.queryForObject("SELECT current_user", String.class))
                .isEqualTo("collector");
        assertThat(webJdbc.queryForObject("SELECT current_user", String.class))
                .isEqualTo("api_server");
        assertThat(collectionEntities.getMetamodel().getEntities())
                .noneMatch(type -> type.getJavaType().getName().contains(".webapi."));
        assertThat(webEntities.getMetamodel().getEntities())
                .hasSize(5)
                .allMatch(type -> type.getJavaType().getName().contains(".webapi.entity."));
    }

    @Test
    void roundTripsCompleteResponseWithTwoHundredRows() throws Exception {
        ArrayNode rows = JSON.createArrayNode();
        for (int index = 0; index < 200; index++) rows.addObject().put("appearances", index);
        JsonNode evidence = JSON.readTree("{\"unit\":\"participant appearance\"}");
        QuestionLogEntity log =
                QuestionLogEntity.builder()
                        .logId("synthetic-answer")
                        .inviteCode(CODE)
                        .question("합성 데이터 질문")
                        .requestConditions(JSON.readTree("{\"patch\":\"5.0\"}"))
                        .responseType("answer")
                        .appliedConditions(JSON.readTree("{\"season\":41}"))
                        .sqlText("SELECT 1 AS appearances")
                        .columns(JSON.readTree("[\"appearances\"]"))
                        .rows(rows)
                        .summary("합성 요약")
                        .message("합성 안내")
                        .warnings(JSON.readTree("[\"synthetic warning\"]"))
                        .evidence(evidence)
                        .elapsedMs(123)
                        .createdAt(NOW)
                        .build();
        questions.saveAndFlush(log);
        QuestionLogEntity loaded = questions.findById(log.getLogId()).orElseThrow();
        assertThat(loaded).usingRecursiveComparison().isEqualTo(log);
        assertThat(loaded.getRows()).hasSize(200);
        assertThat(invites.findById(CODE).orElseThrow().getMemo()).isEqualTo("synthetic recipient");
    }

    @Test
    void storesGuidanceWithoutSqlOrRows() {
        QuestionLogEntity log = question("synthetic-guidance", "guidance");
        questions.saveAndFlush(log);
        QuestionLogEntity loaded = questions.findById(log.getLogId()).orElseThrow();
        assertThat(loaded.getSqlText()).isNull();
        assertThat(loaded.getRows()).isEmpty();
        assertThat(loaded.getRequestConditions()).isEqualTo(JSON.createObjectNode());
    }

    @Test
    void updatesOneFeedbackPerQuestionAndPreservesItsCreationTime() {
        questions.saveAndFlush(question("synthetic-feedback", "answer"));
        feedback.saveAndFlush(new FeedbackEntity("synthetic-feedback", true, "좋아요", NOW));
        FeedbackEntity existing = feedback.findById("synthetic-feedback").orElseThrow();
        existing.update(false, "수정 의견", NOW.plusSeconds(30));
        feedback.saveAndFlush(existing);
        FeedbackEntity loaded = feedback.findById("synthetic-feedback").orElseThrow();
        assertThat(feedback.count()).isEqualTo(1);
        assertThat(loaded.isLiked()).isFalse();
        assertThat(loaded.getComment()).isEqualTo("수정 의견");
        assertThat(loaded.getCreatedAt()).isEqualTo(NOW);
        assertThat(loaded.getUpdatedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThatThrownBy(
                        () ->
                                feedback.saveAndFlush(
                                        new FeedbackEntity("missing-question", true, null, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void separatesGlobalInviteAndKoreanCalendarDateCounters() {
        Instant boundary = Instant.parse("2026-10-12T14:59:59Z");
        LocalDate today = boundary.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        LocalDate tomorrow = boundary.plusSeconds(1).atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        usage.saveAndFlush(new DailyUsageEntity(today, null));
        DailyUsageEntity perInvite = new DailyUsageEntity(today, CODE);
        perInvite.increment();
        usage.saveAndFlush(perInvite);
        usage.saveAndFlush(new DailyUsageEntity(tomorrow, CODE));
        assertThat(usage.count()).isEqualTo(3);
        assertThat(usage.findById(perInvite.getId()).orElseThrow().getRequestCount()).isEqualTo(1);
        assertThat(tomorrow).isEqualTo(LocalDate.of(2026, 10, 13));
    }

    @Test
    void rejectsLostCounterUpdates() {
        DailyUsageEntity saved =
                usage.saveAndFlush(new DailyUsageEntity(LocalDate.of(2026, 10, 12), CODE));
        DailyUsageEntity first = usage.findById(saved.getId()).orElseThrow();
        DailyUsageEntity second = usage.findById(saved.getId()).orElseThrow();
        first.increment();
        second.increment();
        usage.saveAndFlush(first);
        assertThatThrownBy(() -> usage.saveAndFlush(second))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(usage.findById(saved.getId()).orElseThrow().getRequestCount()).isEqualTo(1);
    }

    @Test
    void rejectsOversizedInvalidResultsAndBrokenReferences() {
        questions.saveAndFlush(question("synthetic-constraints", "answer"));
        ArrayNode oversized = JSON.createArrayNode();
        for (int index = 0; index < 201; index++) oversized.addObject();
        for (String invalid : List.of(oversized.toString(), "{}", "null")) {
            assertThatThrownBy(
                            () -> webJdbc.update("UPDATE question_logs SET rows=?::jsonb", invalid))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> webJdbc.update("UPDATE question_logs SET elapsed_ms=-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> webJdbc.update("UPDATE question_logs SET invite_code='missing'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                webJdbc.update(
                                        "INSERT INTO daily_usage(usage_date,scope_key,invite_code) VALUES ('2026-10-12','GLOBAL',?)",
                                        CODE))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                webJdbc.update(
                                        "INSERT INTO daily_usage(usage_date,scope_key,request_count) VALUES ('2026-10-12','GLOBAL',-1)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void permitsAggregatesAndBlocksPrivateDataAndCollectionWrites() throws Exception {
        webJdbc.queryForList(
                """
                SELECT g.version_major,p.tier,COUNT(DISTINCT g.game_id),MAX(g.fetched_at)
                FROM games g LEFT JOIN participants p ON p.game_id=g.game_id
                WHERE g.season_id=41 GROUP BY g.version_major,p.tier
                """);
        for (String allowed : List.of("tiers", "characters", "collect_queue", "api_call_log")) {
            webJdbc.queryForList("SELECT * FROM " + allowed + " LIMIT 0");
        }
        try (Connection connection = DriverManager.getConnection(testUrl(), "api_server", "");
                Statement sql = connection.createStatement()) {
            for (String blocked :
                    List.of(
                            "SELECT nickname FROM participants",
                            "SELECT raw FROM participants",
                            "SELECT is_ranker FROM participants",
                            "SELECT row_to_json(participants) FROM participants",
                            "SELECT * FROM users",
                            "SELECT * FROM rankers",
                            "SELECT * FROM flyway_schema_history",
                            "INSERT INTO games(game_id) VALUES (123)",
                            "CREATE TABLE forbidden(id int)")) {
                assertThatThrownBy(() -> sql.execute(blocked))
                        .isInstanceOf(SQLException.class)
                        .satisfies(
                                error ->
                                        assertThat(((SQLException) error).getSQLState())
                                                .isEqualTo("42501"));
            }
        }
        for (String role : List.of("collector", "agent_ro")) {
            try (Connection connection = DriverManager.getConnection(testUrl(), role, "");
                    Statement sql = connection.createStatement()) {
                for (String table :
                        List.of("invite_codes", "daily_usage", "question_logs", "feedback")) {
                    assertThatThrownBy(() -> sql.executeQuery("SELECT * FROM " + table))
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("permission denied");
                }
            }
        }
    }

    @Test
    void validatesExistingReceiptEntityAndRoundTripsItsLifecycle() throws Exception {
        questions.saveAndFlush(question("synthetic-receipt-log", "answer"));
        try (EntityManager entities = webEntities.createEntityManager()) {
            entities.getTransaction().begin();
            entities.persist(
                    new QuestionRequestEntity(
                            "synthetic-receipt", CODE, "합성 질문", JSON.createObjectNode(), NOW));
            entities.getTransaction().commit();
            entities.clear();
            entities.getTransaction().begin();
            QuestionRequestEntity request =
                    entities.find(QuestionRequestEntity.class, "synthetic-receipt");
            request.processing(NOW.plusSeconds(1));
            entities.getTransaction().commit();
            entities.getTransaction().begin();
            request.complete(
                    JSON.readTree("{\"log_id\":\"synthetic-receipt-log\",\"type\":\"answer\"}"),
                    NOW.plusSeconds(2));
            entities.getTransaction().commit();
            entities.clear();
            QuestionRequestEntity loaded =
                    entities.find(QuestionRequestEntity.class, "synthetic-receipt");
            assertThat(loaded.getStatus()).isEqualTo("completed");
            assertThat(loaded.getAgentLogId()).isEqualTo("synthetic-receipt-log");
            assertThat(loaded.getVersion()).isEqualTo(2);
            assertThat(loaded.getResponse().get("type").asText()).isEqualTo("answer");
        }
    }

    private QuestionLogEntity question(String logId, String type) {
        return QuestionLogEntity.builder()
                .logId(logId)
                .inviteCode(CODE)
                .question("합성 질문")
                .responseType(type)
                .elapsedMs(0)
                .createdAt(NOW)
                .build();
    }
}
