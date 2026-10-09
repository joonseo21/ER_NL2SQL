from __future__ import annotations

import re
from dataclasses import dataclass

from sqlglot import exp, parse
from sqlglot.errors import OptimizeError, ParseError
from sqlglot.optimizer.normalize_identifiers import normalize_identifiers
from sqlglot.optimizer.qualify import qualify
from sqlglot.optimizer.scope import traverse_scope

from .query_schema import ALLOWED_TABLES, BLOCKED_COLUMNS, MAX_ROWS, TABLE_COLUMNS


# 분석 전용: SQL 문자열을 실행하거나, 파일/설정을 읽거나, 세션 상태를 수정하는 함수는 사용하지 않는다. 쿼리 계약에 따라 의도적으로 수정하기.
ALLOWED_FUNCTIONS = frozenset({
    "ABS", "AND", "ARRAY", "ARRAY_AGG", "ARRAY_LENGTH", "ARRAY_SIZE", "AVG", "BOOL_AND", "BOOL_OR",
    "CARDINALITY", "CASE", "CAST", "CEIL", "COALESCE", "CONCAT", "CONCAT_WS", "COUNT",
    "CURRENT_DATE", "CURRENT_TIMESTAMP", "DATE_TRUNC", "DENSE_RANK", "EXISTS", "EXTRACT", "FILTER",
    "FLOOR", "GREATEST", "GROUP_CONCAT", "IF", "JSON_AGG", "JSON_BUILD_ARRAY", "JSON_BUILD_OBJECT",
    "JSON_EXTRACT", "JSON_EXTRACT_SCALAR", "JSONB_AGG", "JSONB_ARRAY_LENGTH", "JSONB_BUILD_ARRAY", "JSONB_BUILD_OBJECT",
    "JSONB_EACH", "JSONB_EACH_TEXT", "JSONB_EXTRACT_PATH", "JSONB_EXTRACT_PATH_TEXT",
    "LAG", "LEAD", "LEAST", "LENGTH", "LOWER", "MAX", "MIN", "NOT", "NOW", "NULLIF", "OR",
    "PERCENTILE_CONT", "PERCENTILE_DISC", "RANK", "ROUND", "ROW_NUMBER", "ROW_TO_JSON",
    "STDDEV", "STDDEV_POP", "STDDEV_SAMP", "STRING_AGG", "SUBSTRING", "SUM",
    "TIME_TO_STR", "TIMESTAMP_TRUNC", "TO_CHAR", "TO_JSON", "TO_JSONB", "TRIM",
    "UNNEST", "UPPER", "VAR_POP", "VAR_SAMP", "VARIANCE",
})


class SqlGuardError(ValueError):
    pass


@dataclass(frozen=True)
class GuardedSql:
    sql: str
    limit_added: bool


def extract_sql(model_text: str) -> str:
    fenced = re.fullmatch(r"\s*```(?:sql)?\s*(.*?)\s*```\s*", model_text, re.IGNORECASE | re.DOTALL)
    return (fenced.group(1) if fenced else model_text).strip()


def guard_sql(model_text: str, max_rows: int = MAX_ROWS) -> GuardedSql:
    if isinstance(max_rows, bool) or not isinstance(max_rows, int) or not 1 <= max_rows <= MAX_ROWS:
        raise SqlGuardError(f"max_rows must be between 1 and {MAX_ROWS}")
    sql = extract_sql(model_text)
    if not sql:
        raise SqlGuardError("SQL is empty")
    if "--" in sql or "/*" in sql or "*/" in sql:
        raise SqlGuardError("SQL comments are not allowed")

    try:
        statements = parse(sql, dialect="postgres")
    except ParseError as exception:
        raise SqlGuardError("SQL could not be parsed") from exception
    if len(statements) != 1 or not isinstance(statements[0], exp.Select):
        raise SqlGuardError("Only one SELECT statement is allowed")

    statement = normalize_identifiers(statements[0], dialect="postgres")
    _validate_structure(statement)
    try:
        _validate_sources(statement)
    except OptimizeError as exception:
        raise SqlGuardError("Table sources could not be resolved") from exception
    limit = statement.args.get("limit")
    limit_added = limit is None
    if limit is not None:
        value = limit.expression
        if not isinstance(value, exp.Literal) or not value.is_int or int(value.this) < 0:
            raise SqlGuardError("LIMIT must be a non-negative integer literal")
        limit_added = int(value.this) > max_rows
    if limit_added:
        statement = statement.limit(max_rows, copy=False)
    schema = {table: dict.fromkeys(columns, "UNKNOWN") for table, columns in TABLE_COLUMNS.items()}
    try:
        statement = qualify(statement, dialect="postgres", schema=schema, infer_schema=False)
    except OptimizeError as exception:
        raise SqlGuardError("Columns could not be resolved against the allowed schema") from exception
    _reject_whole_rows(statement)

    return GuardedSql(sql=statement.sql(dialect="postgres", pretty=True), limit_added=limit_added)


def _validate_structure(statement: exp.Select) -> None:
    for with_clause in statement.find_all(exp.With):
        if with_clause.args.get("recursive"):
            raise SqlGuardError("Recursive CTEs are not allowed")
    for cte in statement.find_all(exp.CTE):
        if not isinstance(cte.this, exp.Select):
            raise SqlGuardError("CTEs must contain SELECT only")
    for node in statement.walk():
        if isinstance(node, (exp.DML, exp.DDL, exp.Command, exp.Into, exp.Lock)):
            raise SqlGuardError("Data changes, SELECT INTO, and row locks are not allowed")
        if isinstance(node, exp.Join) and node.args.get("method") == "NATURAL":
            raise SqlGuardError("Use explicit join keys instead of NATURAL JOIN")
        if isinstance(node, exp.Column) and node.name.lower() in BLOCKED_COLUMNS:
            raise SqlGuardError(f"Column is not allowed: {node.name}")
        if isinstance(node, exp.Star) and not (
            isinstance(node.parent, exp.Count) and node.parent.this is node
        ):
            raise SqlGuardError("Use explicit columns; only COUNT(*) may use a star")
        if isinstance(node, exp.Func):
            name = node.name.upper() if isinstance(node, exp.Anonymous) else node.sql_name().upper()
            if name not in ALLOWED_FUNCTIONS:
                raise SqlGuardError(f"Function is not allowed: {name}")
            if isinstance(node.parent, exp.Dot):
                raise SqlGuardError("Schema-qualified functions are not allowed")


def _validate_sources(statement: exp.Select) -> None:
    for scope in traverse_scope(statement):
        for _, source in scope.selected_sources.values():
            if isinstance(source, exp.Table):
                alias = source.args.get("alias")
                if alias is not None and alias.args.get("columns"):
                    # Positional aliases use the physical schema, including blocked
                    # columns. They must never be mapped onto our reduced schema.
                    raise SqlGuardError("Renaming base-table columns by position is not allowed")
            if isinstance(source, exp.Table) and (
                source.catalog or source.db not in {"", "public"} or source.name not in ALLOWED_TABLES
            ):
                raise SqlGuardError(f"Table is not allowed: {source.name}")


def _reject_whole_rows(statement: exp.Select) -> None:
    if next(statement.find_all(exp.TableColumn), None) is not None:
        raise SqlGuardError("Whole-row references are not allowed; select explicit columns")
    for scope in traverse_scope(statement):
        # PostgreSQL treats SELECT p / row_to_json(p) as a composite whole row.
        # Qualification intentionally accepts that syntax; check it separately.
        for column in scope.find_all(exp.Column):
            if not column.table and column.name in scope.sources:
                raise SqlGuardError("Whole-row references are not allowed; select explicit columns")
