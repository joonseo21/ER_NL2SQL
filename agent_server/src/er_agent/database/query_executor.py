from __future__ import annotations

from typing import Any
from uuid import uuid4

import psycopg
from sqlglot import exp, parse_one

from ..models.errors import DatabaseQueryError
from ..models.limits import MAX_ROWS
from ..models.sql import QueryTable
from ..sql.guard import guard_sql
from .connection import _analytics_connection


def execute_select(
    database_url: str, sql: str, parameters: dict[str, Any] | None = None
) -> list[dict[str, Any]]:
    return list(execute_table(database_url, sql, parameters).rows)


def execute_table(
    database_url: str, sql: str, parameters: dict[str, Any] | None = None
) -> QueryTable:
    guarded = guard_sql(sql)
    query = _parameter_query(guarded.sql) if parameters is not None else guarded.sql
    with _analytics_connection(database_url) as connection:
        cursor = connection.execute(query, parameters)
        columns = tuple(column.name for column in cursor.description or ())
        rows = tuple(dict(row) for row in cursor.fetchmany(MAX_ROWS))
        return QueryTable(columns, rows)


def _parameter_query(sql: str) -> str:
    """Escape percent literals/operators without changing real pyformat placeholders."""
    statement = parse_one(sql, dialect="postgres")
    markers: dict[str, str] = {}
    prefix = "__er_bind_" + uuid4().hex
    for index, placeholder in enumerate(list(statement.find_all(exp.Placeholder))):
        marker = f"{prefix}_{index}__"
        markers[marker] = placeholder.sql(dialect="postgres")
        placeholder.replace(exp.Var(this=marker))
    query = statement.sql(dialect="postgres").replace("%", "%%")
    for marker, placeholder_sql in markers.items():
        query = query.replace(marker, placeholder_sql)
    return query


class PostgresQueryExecutor:
    def __init__(self, database_url: str) -> None:
        self.database_url = database_url

    def execute(self, sql: str, parameters: dict[str, Any]) -> QueryTable:
        try:
            return execute_table(self.database_url, sql, parameters)
        except psycopg.Error as exception:
            raise DatabaseQueryError(exception.sqlstate) from None
