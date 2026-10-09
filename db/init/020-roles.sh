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
-- Tables and their grants are managed by Flyway after role provisioning.
ALTER ROLE agent_ro SET default_transaction_read_only = on;
ALTER ROLE agent_ro SET statement_timeout = '5s';
SQL
