package io.eranalytics.pipeline.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import io.eranalytics.pipeline.collection.metadata.NameMatchUsageStore;
import io.eranalytics.pipeline.erapi.ErApiClient;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Explicit local DB maintenance only: no schema reset, game ingestion, or real API client. */
@Tag("live")
@SpringBootTest(
        properties = {
            "er.collection.enabled=false",
            "er.web-api.enabled=false",
            "er.api.season-id=41",
            "spring.config.import="
        })
class NameMatchUsageVerificationTest {
    private static final List<String> NAMES = List.of("items", "trait_names", "tactical_skills");
    @Autowired NameMatchUsageStore usage;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ErApiClient api;

    @DynamicPropertySource
    static void guard(DynamicPropertyRegistry properties) {
        String url = System.getenv("COLLECTOR_DATABASE_URL");
        if (!"true".equals(System.getenv("VERIFY_LOCAL_NAME_USAGE"))
                || !"jdbc:postgresql://127.0.0.1:15446/er_analytics".equals(url)) {
            throw new IllegalStateException(
                    "Explicit local 15446 name usage authorization required");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> "collector");
        properties.add("spring.datasource.password", () -> System.getenv("COLLECTOR_PASSWORD"));
    }

    @Test
    void initializesExistingNamesWithoutChangingMatchesOrCallingApi() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .isEqualTo("er_analytics");
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("collector");
        Map<String, Long> before = counts();
        Map<String, String> metadata = signatures(true);
        long started = System.nanoTime();
        usage.initializeMissing(41, Instant.now());
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(counts()).isEqualTo(before);
        assertThat(signatures(true)).isEqualTo(metadata);
        for (String table : NAMES) {
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM "
                                            + table
                                            + " WHERE seen_in_matches IS NULL OR usage_season_id IS DISTINCT FROM 41"
                                            + " OR usage_checked_at IS NULL",
                                    Long.class))
                    .isZero();
            System.out.println(
                    "NAME_USAGE "
                            + table
                            + " "
                            + jdbc.queryForList(
                                    "SELECT usage_season_id,seen_in_matches,count(*) FROM "
                                            + table
                                            + " GROUP BY 1,2 ORDER BY 1,2"));
        }
        Map<String, String> initialized = signatures(false);
        usage.initializeMissing(41, Instant.now());
        assertThat(signatures(false)).isEqualTo(initialized);
        verifyNoInteractions(api);
        System.out.println("NAME_USAGE_ELAPSED_MS " + elapsedMs);
        System.out.println("PRESERVED_COUNTS " + before);
    }

    private Map<String, Long> counts() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String table :
                List.of(
                        "games",
                        "participants",
                        "users",
                        "collect_queue",
                        "routes",
                        "route_paths",
                        "api_call_log")) {
            result.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class));
        }
        return result;
    }

    private Map<String, String> signatures(boolean excludeUsage) {
        Map<String, String> result = new LinkedHashMap<>();
        String excluded =
                excludeUsage ? "-'seen_in_matches'-'usage_season_id'-'usage_checked_at'" : "";
        for (String table : NAMES) {
            result.put(
                    table,
                    jdbc.queryForObject(
                            "SELECT md5(string_agg((to_jsonb(n)"
                                    + excluded
                                    + ")::text,E'\\n' ORDER BY code)) FROM "
                                    + table
                                    + " n",
                            String.class));
        }
        return result;
    }
}
