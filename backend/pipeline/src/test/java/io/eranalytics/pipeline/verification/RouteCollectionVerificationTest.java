package io.eranalytics.pipeline.verification;

import static org.assertj.core.api.Assertions.assertThat;

import io.eranalytics.pipeline.collection.game.GameCollectionProcessor;
import io.eranalytics.pipeline.collection.metadata.NameMetadataRefresher;
import io.eranalytics.pipeline.collection.route.RouteCollectionProcessor;
import io.eranalytics.pipeline.collection.route.RouteStore;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Bounded real API verification; never drops schemas or calls Gemini. */
@Tag("live")
@SpringBootTest(
        properties = {
            "er.collection.enabled=false",
            "er.web-api.enabled=false",
            "er.api.requests-per-second=0.5",
            "er.collection.routes-per-game=1"
        })
class RouteCollectionVerificationTest {
    @Autowired RouteStore store;
    @Autowired RouteCollectionProcessor routes;
    @Autowired GameCollectionProcessor games;
    @Autowired NameMetadataRefresher metadata;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void guard(DynamicPropertyRegistry properties) {
        String url = System.getenv("COLLECTOR_DATABASE_URL");
        if (!"true".equals(System.getenv("VERIFY_LOCAL_ROUTES"))
                || !"jdbc:postgresql://127.0.0.1:15446/er_analytics".equals(url)) {
            throw new IllegalStateException("Explicit local 15446 testbed authorization required");
        }
        properties.add("spring.datasource.url", () -> url);
    }

    @Test
    void boundedGamesInterleavedWithRoutesThenReportsCoverageAndAgentQuery() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .isEqualTo("er_analytics");
        if (!"true".equals(System.getenv("VERIFY_ROUTE_QUERY_ONLY"))) collectBounded();
        System.out.println(
                "ROUTE_STATUS "
                        + jdbc.queryForList(
                                "SELECT fetch_status,response_code,count(*) FROM routes GROUP BY 1,2 ORDER BY 1,2"));
        System.out.println(
                "PATCH_5_COVERAGE "
                        + jdbc.queryForList(
                                """
                SELECT count(*) AS appearances, count(*) FILTER(WHERE p.route_id_of_start>0) AS with_route,
                  count(*) FILTER(WHERE r.fetch_status IN ('SUCCESS','INVALID_VERSION')) AS loaded,
                  count(DISTINCT p.route_id_of_start) FILTER(WHERE p.route_id_of_start>0) AS route_numbers
                FROM participants p JOIN games g USING(game_id)
                LEFT JOIN routes r ON r.season_id=g.season_id AND r.route_id=p.route_id_of_start
                WHERE g.season_id=41 AND g.version_major=5 AND g.version_minor=0
                """));
        System.out.println(
                "MATERIAL_COUNTS "
                        + jdbc.queryForList(
                                """
                SELECT m.code,m.name_ko,count(*) FILTER(WHERE i.item_type='Weapon') AS weapons,
                  count(*) FILTER(WHERE i.item_type='Armor') AS armor
                FROM items m LEFT JOIN items i ON m.code=i.make_material_1 OR m.code=i.make_material_2
                WHERE m.code IN (401304,401401,401403,401208,401209) GROUP BY 1,2 ORDER BY 1
                """));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM route_paths", Long.class))
                .isPositive();
        agentQuery();
    }

    private void collectBounded() {
        store.initializePool();
        metadata.refreshIfDue();
        long before = jdbc.queryForObject("SELECT count(*) FROM games", Long.class);
        for (int index = 0; index < 2; index++) {
            assertThat(games.processNext()).isTrue();
            routes.processBatch();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM games", Long.class))
                .isGreaterThan(before);
        for (int index = 0; index < 28; index++) routes.processBatch();
    }

    private void agentQuery() throws Exception {
        long started = System.nanoTime();
        try (Connection connection =
                        DriverManager.getConnection(
                                System.getenv("COLLECTOR_DATABASE_URL"),
                                "agent_ro",
                                System.getenv("AGENT_RO_PASSWORD"));
                Statement sql = connection.createStatement()) {
            ResultSet setting = sql.executeQuery("SHOW statement_timeout");
            setting.next();
            assertThat(setting.getString(1)).isEqualTo("20s");
            ResultSet result =
                    sql.executeQuery(
                            """
                    WITH route_usage AS MATERIALIZED (
                        SELECT g.season_id,p.route_id_of_start AS route_id,count(*) AS uses
                        FROM participants p JOIN games g USING(game_id)
                        WHERE g.season_id=41 AND g.version_major=5 AND g.version_minor=0
                          AND p.route_id_of_start>0
                        GROUP BY g.season_id,p.route_id_of_start
                    ), last_area AS (
                        SELECT DISTINCT ON (season_id,route_id) season_id,route_id,area_code
                        FROM route_paths ORDER BY season_id,route_id,seq DESC
                    )
                    SELECT a.name_ko,sum(u.uses) AS appearances
                    FROM route_usage u
                    JOIN routes r ON r.season_id=u.season_id AND r.route_id=u.route_id
                    JOIN last_area l ON l.season_id=r.season_id AND l.route_id=r.route_id
                    LEFT JOIN areas a ON a.code=l.area_code
                    WHERE r.fetch_status IN ('SUCCESS','INVALID_VERSION')
                    GROUP BY l.area_code,a.name_ko ORDER BY appearances DESC
                    """);
            int rows = 0;
            while (result.next()) rows++;
            assertThat(rows).isPositive();
            System.out.println(
                    "AGENT_LAST_AREA rows="
                            + rows
                            + " elapsed_ms="
                            + (System.nanoTime() - started) / 1_000_000);
        }
    }
}
