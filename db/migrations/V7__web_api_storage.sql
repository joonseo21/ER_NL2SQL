-- Web requests use api_server, even when collection runs in the same Spring app.
SET LOCAL lock_timeout = '10s';

CREATE TABLE invite_codes (
    code text PRIMARY KEY CHECK (length(btrim(code)) > 0),
    active boolean NOT NULL DEFAULT true,
    memo text,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- One GLOBAL row per Korean calendar date; one INVITE:<code> row per invite.
-- A non-null composite key is usable by JPA without a synthetic sequence.
CREATE TABLE daily_usage (
    usage_date date NOT NULL,
    scope_key text NOT NULL,
    invite_code text REFERENCES invite_codes(code),
    request_count integer NOT NULL DEFAULT 0 CHECK (request_count >= 0),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    PRIMARY KEY (usage_date, scope_key),
    CHECK ((invite_code IS NULL AND scope_key = 'GLOBAL')
        OR (invite_code IS NOT NULL AND scope_key = 'INVITE:' || invite_code))
);

CREATE TABLE question_logs (
    log_id text PRIMARY KEY CHECK (length(btrim(log_id)) > 0),
    invite_code text NOT NULL REFERENCES invite_codes(code),
    question text NOT NULL CHECK (length(btrim(question)) > 0),
    request_conditions jsonb NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(request_conditions) = 'object'),
    response_type text NOT NULL CHECK (length(btrim(response_type)) > 0),
    applied_conditions jsonb NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(applied_conditions) = 'object'),
    sql_text text,
    columns jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(columns) = 'array'),
    rows jsonb NOT NULL DEFAULT '[]'::jsonb,
    summary text,
    message text,
    warnings jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(warnings) = 'array'),
    evidence jsonb,
    elapsed_ms bigint NOT NULL CHECK (elapsed_ms >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (CASE WHEN jsonb_typeof(rows) = 'array'
        THEN jsonb_array_length(rows) <= 200 ELSE false END)
);
CREATE INDEX idx_question_logs_invite_created ON question_logs (invite_code, created_at DESC);
CREATE INDEX idx_question_logs_created ON question_logs (created_at DESC);

CREATE TABLE feedback (
    log_id text PRIMARY KEY REFERENCES question_logs(log_id),
    liked boolean NOT NULL,
    comment text,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (updated_at >= created_at)
);

-- Also grant here: a repeatable already applied at V6 may not run again.
DO $$
BEGIN
    REVOKE ALL ON invite_codes, daily_usage, question_logs, feedback FROM collector, agent_ro;
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'api_server') THEN
        GRANT USAGE ON SCHEMA public TO api_server;
        REVOKE ALL ON ALL TABLES IN SCHEMA public FROM api_server;
        REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM api_server;
        REVOKE SELECT (nickname, raw, is_ranker) ON participants FROM api_server;
        GRANT SELECT, INSERT, UPDATE, DELETE ON
            invite_codes, daily_usage, question_logs, feedback TO api_server;
        GRANT SELECT ON tiers, characters, collect_queue, api_call_log TO api_server;
        GRANT SELECT (game_id, season_id, matching_mode, matching_team_mode,
            version_major, version_minor, start_dtm, fetched_at) ON games TO api_server;
        GRANT SELECT (game_id, participant_id, tier, tier_division) ON participants TO api_server;
    END IF;
END $$;
