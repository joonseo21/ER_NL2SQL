from __future__ import annotations

import re
from dataclasses import dataclass

from sqlglot import exp, parse
from sqlglot.errors import ParseError


ALLOWED_TABLES = {"games", "participants", "rankers", "characters", "weapon_types"}
class SqlGuardError(ValueError):
    pass


@dataclass(frozen=True)
class GuardedSql:
    sql: str
    limit_added: bool


def extract_sql(model_text: str) -> str:
    fenced = re.fullmatch(r"\s*```(?:sql)?\s*(.*?)\s*```\s*", model_text, re.IGNORECASE | re.DOTALL)
    return (fenced.group(1) if fenced else model_text).strip()


def guard_sql(model_text: str, max_rows: int = 200) -> GuardedSql:
    sql = extract_sql(model_text)
    if not sql:
        raise SqlGuardError("SQL is empty")
    if "--" in sql or "/*" in sql or "*/" in sql:
        raise SqlGuardError("SQL comments are not allowed")

    if sql.endswith(";"):
        sql = sql[:-1].rstrip()
    if ";" in sql:
        raise SqlGuardError("Multiple SQL statements are not allowed")
    if not re.match(r"(?is)^select\b", sql):
        raise SqlGuardError("Only SQL starting with SELECT is allowed")

    normalized = _without_string_literals(sql).lower()
    if re.search(r"\b(pg_catalog|information_schema|pg_[a-z0-9_]*)\b", normalized):
        raise SqlGuardError("System catalogs are not allowed")

    try:
        statements = parse(sql, dialect="postgres")
    except ParseError as exception:
        raise SqlGuardError(f"SQL could not be parsed: {exception}") from exception
    if len(statements) != 1 or not isinstance(statements[0], exp.Select):
        raise SqlGuardError("Only one SELECT statement is allowed")

    statement = statements[0]
    for table in statement.find_all(exp.Table):
        if table.catalog or table.db or table.name.lower() not in ALLOWED_TABLES:
            raise SqlGuardError(f"Table is not allowed: {table.sql(dialect='postgres')}")

    limit = statement.args.get("limit")
    if limit is not None and isinstance(limit.expression, exp.Literal) and limit.expression.is_int:
        if int(limit.expression.this) <= max_rows:
            return GuardedSql(sql=sql, limit_added=False)
        return GuardedSql(
            sql=f"SELECT * FROM (\n{sql}\n) AS guarded_query\nLIMIT {max_rows}",
            limit_added=True,
        )
    if limit is not None:
        raise SqlGuardError("LIMIT must be an integer literal")
    return GuardedSql(sql=f"{sql}\nLIMIT {max_rows}", limit_added=True)


def _without_string_literals(sql: str) -> str:
    return re.sub(r"'(?:''|[^'])*'", "''", sql)
