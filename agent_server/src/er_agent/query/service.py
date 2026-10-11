"""Generation, validated context, execution and one SQL repair through ports."""

from __future__ import annotations

from collections.abc import Callable
from contextlib import AbstractContextManager
from dataclasses import asdict, replace

from ..models.errors import GenerationFormatError, LlmCallError
from ..models.query import QueryRequest, QueryResponse, ResponseKind
from .context_resolver import prepare_context
from .execution import execute_attempt
from .ports import ContextRepository, QueryExecutor, QueryRecorder, QuerySettings, SqlGenerator
from .repair_policy import MAX_SQL_ATTEMPTS, should_repair


class QueryService:
    def __init__(
        self,
        settings: QuerySettings,
        repository: ContextRepository,
        generator_session: Callable[[], AbstractContextManager[SqlGenerator]],
        executor: QueryExecutor,
        recorder: QueryRecorder,
    ) -> None:
        self.settings = settings
        self.repository = repository
        self.generator_session = generator_session
        self.executor = executor
        self.recorder = recorder

    def _notice(self, request: QueryRequest, message: str) -> QueryResponse:
        return QueryResponse(
            ResponseKind.NOTICE,
            request.question,
            self.recorder.log_id,
            self.recorder.elapsed_ms,
            message=message,
        )

    def run(self, request: QueryRequest) -> QueryResponse:
        """Record even configuration/serialization failures; summary belongs to 7-4."""
        payload = None
        error = None
        try:
            self.recorder.append("query_start", selected_conditions=asdict(request.conditions))
            self.settings.require_database()
            self.settings.require_gemini()
            response = self._process(request)
            payload = response.to_dict()
        except LlmCallError as exception:
            error = str(exception)
            response = self._notice(request, f"{error}. 잠시 뒤 다시 요청해 주세요.")
            payload = response.to_dict()
        except GenerationFormatError:
            error = "generation_format"
            response = self._notice(
                request, "질문 의미를 확인하지 못했습니다. 질문을 다시 제출해 주세요."
            )
            payload = response.to_dict()
        except Exception as exception:
            error = type(exception).__name__
            raise
        finally:
            self.recorder.finish(error=error, response=payload)
        return response

    def _process(self, request: QueryRequest) -> QueryResponse:
        snapshot = self.repository.load(self.settings.season_id)
        with self.generator_session() as generator:
            generation = generator.generate(request, snapshot)
            context = prepare_context(request, generation.intent, snapshot, self.recorder.log_id)
            if isinstance(context, QueryResponse):
                return replace(context, elapsed_ms=self.recorder.elapsed_ms)
            self.recorder.append(
                "resolved_context",
                applied_conditions=asdict(context.applied_conditions),
                names=[asdict(name) for name in context.names],
            )
            sql = generation.sql or ""
            for attempt in range(1, MAX_SQL_ATTEMPTS + 1):
                result = execute_attempt(sql, context, self.executor, self.recorder, attempt)
                if result.rows is not None:
                    return QueryResponse(
                        ResponseKind.ANSWER,
                        request.question,
                        self.recorder.log_id,
                        self.recorder.elapsed_ms,
                        applied_conditions=context.applied_conditions,
                        sql=result.guarded_sql,
                        columns=tuple(result.rows[0]) if result.rows else (),
                        rows=tuple(result.rows),
                        evidence=context.evidence,
                        warnings=context.warnings,
                    )
                if not should_repair(attempt, result):
                    return self._notice(
                        request, "SQL을 실행하지 못했습니다. 질문을 다시 제출해 주세요."
                    )
                sql = generator.repair(request, context, sql, result.error or "")
        raise RuntimeError("Unreachable SQL repair state")
