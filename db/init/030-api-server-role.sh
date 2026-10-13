#!/bin/sh
set -eu

: "${API_SERVER_PASSWORD:?Set API_SERVER_PASSWORD}"
psql --set ON_ERROR_STOP=1 \
  --set api_server_password="$API_SERVER_PASSWORD" \
  --set database_name="$POSTGRES_DB" \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" <<'SQL'
-- Provisioning again preserves the role's existing password.
SELECT format('CREATE ROLE api_server LOGIN PASSWORD %L', :'api_server_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'api_server')
\gexec
GRANT CONNECT ON DATABASE :"database_name" TO api_server;
GRANT USAGE ON SCHEMA public TO api_server;
SQL
