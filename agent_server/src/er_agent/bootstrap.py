"""Compose concrete adapters and own their lifetime; keep policy in QueryService."""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
from functools import partial
from pathlib import Path
from typing import Any

from .database.context_repository import PostgresContextRepository
from .database.manual_queries import MANUAL_QUERIES
from .database.query_executor import PostgresQueryExecutor, execute_select
from .generation.agent import SqlGenerationAgent
from .generation.gemini import GeminiTransport, create_client
from .generation.retry import RetryingCaller
from .generation.session import QueryGenerationAgent
from .generation.summary import ResultSummaryAgent
from .models.query import QueryRequest, QueryResponse, ResponseKind
from .query.ports import QueryGenerator, QueryRecorder
from .query.service import QueryService
from .recording.jsonl import DEFAULT_LOG_PATH, JsonlSink
from .recording.trial import TrialRecorder
from .settings import Settings

MANUAL_QUERY_NAMES = tuple(MANUAL_QUERIES)


class QueryFailed(RuntimeError):
    """Non-answer error for the legacy row-only Python helper."""


@contextmanager
def _generation_session(settings: Settings, recorder: QueryRecorder) -> Iterator[QueryGenerator]:
    with create_client(settings.gemini_api_key, settings.gemini_timeout_seconds) as client:
        transport = GeminiTransport(client, settings.gemini_model, settings.gemini_thinking_level)
        caller = RetryingCaller(transport, recorder)
        yield QueryGenerationAgent(SqlGenerationAgent(caller, recorder), ResultSummaryAgent(caller))


def run_query(
    request: QueryRequest, settings: Settings, log_path: Path = DEFAULT_LOG_PATH
) -> QueryResponse:
    recorder = TrialRecorder(JsonlSink(log_path), request.question, settings.gemini_model)
    service = QueryService(
        settings,
        PostgresContextRepository(settings.database_url),
        partial(_generation_session, settings, recorder),
        PostgresQueryExecutor(settings.database_url),
        recorder,
    )
    return service.run(request)


def run_question(
    question: str, settings: Settings, log_path: Path = DEFAULT_LOG_PATH
) -> list[dict[str, Any]]:
    response = run_query(QueryRequest(question), settings, log_path)
    if response.kind != ResponseKind.ANSWER:
        raise QueryFailed(response.message)
    return list(response.rows)


def run_manual(query_name: str, settings: Settings) -> list[dict[str, Any]]:
    settings.require_database()
    return execute_select(settings.database_url, MANUAL_QUERIES[query_name])
