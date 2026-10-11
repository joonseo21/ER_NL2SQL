package io.eranalytics.pipeline.persistence.apilog;

import io.eranalytics.pipeline.erapi.ApiCallLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcApiCallLog implements ApiCallLog {
    private final JdbcTemplate jdbc;

    public JdbcApiCallLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void logApiCall(String endpoint, int statusCode, long latencyMs) {
        jdbc.update(
                """
                INSERT INTO api_call_log(endpoint, status_code, latency_ms)
                VALUES (?, ?, ?)
                """,
                endpoint,
                statusCode,
                Math.max(0, latencyMs));
    }
}
