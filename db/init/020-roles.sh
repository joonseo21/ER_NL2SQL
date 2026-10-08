#!/bin/sh
set -eu

psql --set ON_ERROR_STOP=1 \
  --set collector_password="$COLLECTOR_PASSWORD" \
  --set agent_ro_password="$AGENT_RO_PASSWORD" \
  --set database_name="$POSTGRES_DB" \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" <<'SQL'
CREATE ROLE collector LOGIN PASSWORD :'collector_password';
CREATE ROLE agent_ro LOGIN PASSWORD :'agent_ro_password';

GRANT CONNECT ON DATABASE :"database_name" TO collector, agent_ro;
GRANT USAGE ON SCHEMA public TO collector, agent_ro;

GRANT SELECT, INSERT, UPDATE, DELETE ON
  rankers, collect_queue, games, participants, characters, weapon_types, api_call_log,
  users, tiers, participant_equipment, participant_traits, participant_mastery,
  participant_matchups, participant_deaths
TO collector;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO collector;

-- agent_ro reads participants without nickname / raw / is_ranker and cannot read rankers or
-- users. Keep this in step with the privileges section of the V4 migration.
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
ALTER ROLE agent_ro SET statement_timeout = '5s';
SQL
