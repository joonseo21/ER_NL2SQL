"""Read-only stage 5 checks; output contains counts and check names only."""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path

import psycopg
from psycopg.conninfo import conninfo_to_dict

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "agent_server" / "src"))
from er_agent.database import execute_select, sample_rows
from er_agent.guard import guard_sql
from er_agent.query_schema import TABLE_COLUMNS
from er_agent.cli import MANUAL_QUERIES


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> None:
    url = os.environ.get("STAGE5_AGENT_DATABASE_URL", "")
    config = conninfo_to_dict(url)
    require(config.get("host") in {"localhost", "127.0.0.1"}
            and config.get("port") == "15445"
            and config.get("dbname") == "er_stage5_copy"
            and config.get("user") == "agent_ro"
            and not config.get("service") and not config.get("hostaddr"),
            "Use agent_ro on the dedicated local stage5 copy")
    with psycopg.connect(url, autocommit=True) as connection:
        identity = connection.execute("""
            SELECT current_database(),current_user,shobj_description(oid,'pg_database')
            FROM pg_database WHERE datname=current_database()
        """).fetchone()
        require(identity == ("er_stage5_copy", "agent_ro", "ER_ANALYTICS_STAGE5_COPY"),
                "Verification database identity/marker required")
        actual = set(connection.execute("""
            SELECT table_name,column_name FROM information_schema.column_privileges
            WHERE grantee=current_user AND table_schema='public' AND privilege_type='SELECT'
        """).fetchall())
        expected = {(table, column) for table, columns in TABLE_COLUMNS.items() for column in columns}
        require(actual == expected, "Runtime column grants differ from the SQL contract")
        private_queries = ["SELECT nickname FROM participants LIMIT 1",
                           "SELECT raw FROM participants LIMIT 1",
                           "SELECT is_ranker FROM participants LIMIT 1"]
        private_queries += [f"SELECT * FROM {table} LIMIT 1" for table in
                            ("users", "rankers", "collect_queue", "api_call_log", "flyway_schema_history")]
        for query in private_queries:
            try:
                connection.execute(query)
            except psycopg.errors.InsufficientPrivilege:
                pass
            else:
                raise AssertionError("Private SELECT unexpectedly allowed")
        # A false predicate tests permissions without changing even a copied row.
        try:
            with connection.transaction():
                connection.execute("SET TRANSACTION READ ONLY")
                connection.execute("UPDATE games SET season_id=season_id WHERE false")
        except (psycopg.errors.ReadOnlySqlTransaction, psycopg.errors.InsufficientPrivilege):
            pass
        else:
            raise AssertionError("Write unexpectedly allowed")
    for query in private_queries + ["DELETE FROM games", "SELECT pg_sleep(1)"]:
        try:
            guard_sql(query)
        except ValueError:
            pass
        else:
            raise AssertionError("SQL guard unexpectedly allowed a forbidden query")
    samples = sample_rows(url)
    results = {name: len(execute_select(url, query)) for name, query in MANUAL_QUERIES.items()}
    results["child-join"] = len(execute_select(url, """
        SELECT p.character_num,count(*) AS equipment_rows
        FROM participants p JOIN participant_equipment e ON e.participant_id=p.participant_id
        GROUP BY p.character_num ORDER BY equipment_rows DESC LIMIT 20
    """))
    print(json.dumps({"status": "PASS", "column_grants": len(expected),
                      "sample_rows": {table: len(rows) for table, rows in samples.items()},
                      "query_rows": results, "private_db_denials": len(private_queries),
                      "guard_denials": len(private_queries) + 2, "write_denied": True}, indent=2))


if __name__ == "__main__":
    main()
