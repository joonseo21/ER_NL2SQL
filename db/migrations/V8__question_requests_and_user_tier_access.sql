SET LOCAL lock_timeout = '10s';

CREATE TABLE question_requests (
    request_id text PRIMARY KEY,
    invite_code text NOT NULL REFERENCES invite_codes(code),
    question text NOT NULL CHECK (question ~ '[^[:space:]]'),
    request_conditions jsonb NOT NULL CHECK (jsonb_typeof(request_conditions) = 'object'),
    status text NOT NULL CHECK (status IN ('accepted', 'processing', 'completed', 'failed')),
    agent_log_id text REFERENCES question_logs(log_id),
    response jsonb,
    error_code text,
    message text,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CHECK ((status = 'completed' AND agent_log_id IS NOT NULL
            AND response IS NOT NULL AND jsonb_typeof(response) = 'object')
        OR (status <> 'completed' AND agent_log_id IS NULL AND response IS NULL))
);
CREATE INDEX idx_question_requests_status_created ON question_requests (status, created_at);

-- Keep privileges correct even when the repeatable was already applied at V6.
DO $$
DECLARE user_columns text;
BEGIN
    REVOKE ALL ON question_requests FROM collector, agent_ro;
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'api_server') THEN
        REVOKE ALL ON question_requests, users FROM api_server;
        GRANT SELECT, INSERT, UPDATE ON question_requests TO api_server;
        SELECT string_agg(quote_ident(column_name), ', ' ORDER BY ordinal_position)
        INTO user_columns FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'users';
        EXECUTE format('REVOKE SELECT (%s) ON users FROM api_server', user_columns);
        GRANT SELECT (season_id, tier) ON users TO api_server;
    END IF;
END $$;
