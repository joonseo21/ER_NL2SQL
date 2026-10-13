package io.eranalytics.pipeline.webapi;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/** Synthetic test fixture only. The pipeline part owns the real database migration. */
final class QuestionRequestTestSchema {
    private QuestionRequestTestSchema() {}

    static void prepare(String url) throws Exception {
        if (!url.matches(
                "jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/er_webapi_logic_test")) {
            throw new IllegalArgumentException(
                    "Receipt fixture requires the dedicated local logic DB");
        }
        try (Connection connection = DriverManager.getConnection(url, "stage2_test", "");
                Statement sql = connection.createStatement()) {
            sql.execute(
                    """
                    CREATE TABLE IF NOT EXISTS question_requests (
                        request_id text PRIMARY KEY CHECK (btrim(request_id) <> ''),
                        invite_code text NOT NULL REFERENCES invite_codes(code),
                        question text NOT NULL CHECK (btrim(question) <> ''),
                        request_conditions jsonb NOT NULL CHECK (jsonb_typeof(request_conditions)='object'),
                        status text NOT NULL CHECK (status IN ('accepted','processing','completed','failed')),
                        agent_log_id text REFERENCES question_logs(log_id),
                        response jsonb CHECK (response IS NULL OR jsonb_typeof(response)='object'),
                        error_code text, message text,
                        created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
                        version bigint NOT NULL DEFAULT 0,
                        CHECK ((status='completed' AND agent_log_id IS NOT NULL AND response IS NOT NULL)
                            OR (status<>'completed' AND agent_log_id IS NULL AND response IS NULL))
                    )
                    """);
            sql.execute("GRANT SELECT,INSERT,UPDATE ON question_requests TO api_server");
        }
    }
}
