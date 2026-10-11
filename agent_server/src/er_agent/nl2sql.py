"""Generation + local context validation + guarded execution + at most one repair."""

from __future__ import annotations

import json
from dataclasses import asdict, replace
from pathlib import Path
from time import perf_counter
from typing import Any

import psycopg

from .context import ResolvedContext, prepare_context
from .contracts import QueryRequest, QueryResponse, ResponseKind
from .database import execute_select, load_context_snapshot
from .generation import GENERATION_SCHEMA, GenerationFormatError, parse_generation
from .guard import SqlGuardError
from .llm import GeminiCaller, LlmCallError, create_client
from .prompt import build_generation_prompt, build_repair_prompt
from .settings import Settings
from .sql_scope import scope_sql
from .trial_log import DEFAULT_LOG_PATH, TrialLog


class QueryFailed(RuntimeError):
    """Compatibility error until CLI response envelopes are connected in 7-4."""


def _notice(request: QueryRequest, trial: TrialLog, message: str) -> QueryResponse:
    return QueryResponse(
        ResponseKind.NOTICE, request.question, trial.log_id, trial.elapsed_ms, message=message
    )


def _repairable(exception: psycopg.Error) -> bool:
    code = exception.sqlstate
    return (
        code is not None
        and code != "42501"
        and (code[:2] in {"21", "22", "42", "54"} or code == "57014")
    )


def _execute_attempt(
    sql: str, context: ResolvedContext, settings: Settings, trial: TrialLog, attempt: int
) -> tuple[list[dict[str, Any]] | None, str | None, str | None, bool]:
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
        rows = execute_select(settings.database_url, scoped.sql, scoped.parameters)
    except SqlGuardError as exception:
        error = f"guard: {exception}"
        repairable = True
    except psycopg.Error as exception:
        error = f"database: SQLSTATE {exception.sqlstate or 'unavailable'}"
        repairable = _repairable(exception)
    except Exception as exception:
        error = type(exception).__name__
        raise
    finally:
        trial.append(
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
    return rows, guarded_sql, error, repairable


def _run_query(request: QueryRequest, settings: Settings, trial: TrialLog) -> QueryResponse:
    settings.require_database()
    settings.require_gemini()
    snapshot = load_context_snapshot(settings.database_url, settings.season_id)
    prompt = build_generation_prompt(request, snapshot)
    with create_client(settings.gemini_api_key) as client:
        caller = GeminiCaller(client, settings.gemini_model, trial)
        text = caller.generate(prompt, "generate", GENERATION_SCHEMA)
        generation = parse_generation(text)
        trial.append(
            "generation_result", intent=json.loads(text)["intent"], generated_sql=generation.sql
        )
        context = prepare_context(request, generation.intent, snapshot, trial.log_id)
        if isinstance(context, QueryResponse):
            return replace(context, elapsed_ms=trial.elapsed_ms)
        trial.append(
            "resolved_context",
            applied_conditions=asdict(context.applied_conditions),
            names=[asdict(name) for name in context.names],
        )
        sql = generation.sql or ""
        for attempt in (1, 2):
            rows, guarded, error, repairable = _execute_attempt(
                sql, context, settings, trial, attempt
            )
            if rows is not None:
                return QueryResponse(
                    ResponseKind.ANSWER,
                    request.question,
                    trial.log_id,
                    trial.elapsed_ms,
                    applied_conditions=context.applied_conditions,
                    sql=guarded,
                    columns=tuple(rows[0]) if rows else (),
                    rows=tuple(rows),
                    evidence=context.evidence,
                    warnings=context.warnings,
                )
            if attempt == 2 or not repairable:
                return _notice(
                    request, trial, "SQL을 실행하지 못했습니다. 질문을 다시 제출해 주세요."
                )
            sql = caller.generate(build_repair_prompt(request, context, sql, error or ""), "repair")
    raise RuntimeError("Unreachable SQL repair state")


def run_query(
    request: QueryRequest, settings: Settings, log_path: Path = DEFAULT_LOG_PATH
) -> QueryResponse:
    """Return an answer/notice/clarification; summary generation belongs to 7-4."""
    trial = TrialLog(log_path, request.question, settings.gemini_model)
    response = None
    payload = None
    error = None
    try:
        trial.append("query_start", selected_conditions=asdict(request.conditions))
        response = _run_query(request, settings, trial)
        payload = response.to_dict()
    except LlmCallError as exception:
        error = str(exception)
        response = _notice(request, trial, f"{error}. 잠시 뒤 다시 요청해 주세요.")
        payload = response.to_dict()
    except GenerationFormatError:
        error = "generation_format"
        response = _notice(
            request, trial, "질문 의미를 확인하지 못했습니다. 질문을 다시 제출해 주세요."
        )
        payload = response.to_dict()
    except Exception as exception:
        error = type(exception).__name__
        raise
    finally:
        trial.finish(error=error, response=payload)
    return response


def run_question(
    question: str, settings: Settings, log_path: Path = DEFAULT_LOG_PATH
) -> list[dict[str, Any]]:
    response = run_query(QueryRequest(question), settings, log_path)
    if response.kind != ResponseKind.ANSWER:
        raise QueryFailed(response.message)
    return list(response.rows)
