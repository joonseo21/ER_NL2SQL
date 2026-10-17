-- Roles are provisioned separately by db/init/020-roles.sh on fresh volumes.
-- Never grant runtime roles access to flyway_schema_history or schema ownership.
GRANT USAGE ON SCHEMA public TO collector, agent_ro;
GRANT SELECT, INSERT, UPDATE, DELETE ON
    rankers, collect_queue, games, participants, characters, weapon_types, api_call_log,
    users, tiers, participant_equipment, participant_traits, participant_mastery,
    participant_matchups, participant_deaths
TO collector;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO collector;

REVOKE ALL ON participants, rankers, users FROM agent_ro;
GRANT SELECT ON
    games, characters, weapon_types, tiers, participant_equipment, participant_traits,
    participant_mastery, participant_matchups, participant_deaths
TO agent_ro;
DO $$
DECLARE
    agent_columns text;
BEGIN
    SELECT string_agg(quote_ident(column_name), ', ' ORDER BY ordinal_position)
    INTO agent_columns
    FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'participants'
      AND column_name NOT IN ('nickname', 'raw', 'is_ranker');
    EXECUTE format('GRANT SELECT (%s) ON participants TO agent_ro', agent_columns);
END
$$;
ALTER ROLE agent_ro SET default_transaction_read_only = on;
ALTER ROLE agent_ro SET statement_timeout = '20s';

-- Targeted older-version tests may run this before V5 exists.
DO $$
DECLARE name_table text;
BEGIN
    FOREACH name_table IN ARRAY ARRAY['items','trait_names','tactical_skills','skill_groups','areas','weather_types','routes','route_paths']
    LOOP
        IF to_regclass('public.' || name_table) IS NOT NULL THEN
            EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO collector', name_table);
            EXECUTE format('GRANT SELECT ON %I TO agent_ro', name_table);
        END IF;
    END LOOP;
END $$;

-- Keep older-version verification usable before web storage and its role exist.
DO $$
BEGIN
    IF to_regclass('public.invite_codes') IS NOT NULL THEN
        REVOKE ALL ON invite_codes, daily_usage, question_logs, feedback FROM collector, agent_ro;
        IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'api_server') THEN
            GRANT USAGE ON SCHEMA public TO api_server;
            REVOKE ALL ON ALL TABLES IN SCHEMA public FROM api_server;
            REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM api_server;
            -- Table revocation does not remove earlier column grants.
            REVOKE SELECT (nickname, raw, is_ranker) ON participants FROM api_server;
            GRANT SELECT, INSERT, UPDATE, DELETE ON
                invite_codes, daily_usage, question_logs, feedback TO api_server;
            GRANT SELECT ON tiers, characters, collect_queue, api_call_log TO api_server;
            GRANT SELECT (game_id, season_id, matching_mode, matching_team_mode,
                version_major, version_minor, start_dtm, fetched_at) ON games TO api_server;
            GRANT SELECT (game_id, participant_id, tier, tier_division) ON participants TO api_server;
        END IF;
    END IF;
END $$;

-- Run after the V7 reset of all api_server table privileges.
DO $$
DECLARE user_columns text;
BEGIN
    IF to_regclass('public.question_requests') IS NOT NULL THEN
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
    END IF;
END $$;
