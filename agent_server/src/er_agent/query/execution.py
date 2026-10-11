"""Scope and guard each SQL attempt, execute through a port, record its outcome."""

from __future__ import annotations

from time import perf_counter

from ..models.context import ResolvedContext
from ..models.errors import DatabaseQueryError
from ..models.sql import SqlAttempt
from ..sql.guard import SqlGuardError
from ..sql.scope import scope_sql
from .ports import QueryExecutor, QueryRecorder
from .repair_policy import repairable_sqlstate


def execute_attempt(
    sql: str,
    context: ResolvedContext,
    executor: QueryExecutor,
    recorder: QueryRecorder,
    attempt: int,
) -> SqlAttempt:
    started = perf_counter()
    guarded_sql = None
    error = None
    repairable = False
    rows = None
    parameters = None
    try:
        scoped = scope_sql(sql, context)
        guarded_sql = scoped.sql
        parameters = scoped.parameters
        rows = executor.execute(scoped.sql, scoped.parameters)
    except SqlGuardError as exception:
        error = f"guard: {exception}"
        repairable = True
    except DatabaseQueryError as exception:
        error = str(exception)
        repairable = repairable_sqlstate(exception.sqlstate)
    except Exception as exception:
        error = type(exception).__name__
        raise
    finally:
        recorder.append(
            "sql_attempt",
            attempt=attempt,
            generated_sql=sql,
            guarded_sql=guarded_sql,
            bindings={
                key: value.isoformat() if hasattr(value, "isoformat") else value
                for key, value in (parameters or {}).items()
            },
            guard_passed=guarded_sql is not None,
            execution_succeeded=rows is not None,
            result_row_count=len(rows) if rows is not None else None,
            error=error,
            repairable=repairable,
            elapsed_ms=int((perf_counter() - started) * 1000),
        )
    return SqlAttempt(rows, guarded_sql, error, repairable)
