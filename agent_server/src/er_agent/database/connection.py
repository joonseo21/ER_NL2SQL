from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any

import psycopg
from psycopg.rows import dict_row

from ..models.limits import SQL_TIMEOUT_SECONDS


@contextmanager
def _analytics_connection(
    database_url: str,
    *,
    repeatable_read: bool = False,
) -> Iterator[psycopg.Connection[dict[str, Any]]]:
    with (
        psycopg.connect(
            database_url,
            autocommit=False,
            row_factory=dict_row,
            connect_timeout=5,
        ) as connection,
        connection.transaction(),
    ):
        if repeatable_read:
            connection.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY")
        else:
            connection.execute("SET TRANSACTION READ ONLY")
        # Session value overrides the agent_ro role default for this transaction only.
        connection.execute(f"SET LOCAL statement_timeout = '{SQL_TIMEOUT_SECONDS}s'")
        connection.execute("SET LOCAL search_path = pg_catalog, public")
        identity = connection.execute("SELECT current_user AS role").fetchone()
        if identity is None or identity["role"] != "agent_ro":
            raise ValueError("Analytics queries must execute as agent_ro")
        yield connection
