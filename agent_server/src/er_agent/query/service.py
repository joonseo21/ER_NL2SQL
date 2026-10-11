"""Generation, validated context, execution and one SQL repair through ports."""

from __future__ import annotations

from collections.abc import Callable
from contextlib import AbstractContextManager
from dataclasses import asdict, replace

from ..models.context import ResolvedContext
from ..models.errors import GenerationFormatError, LlmCallError, SummaryFormatError
from ..models.generation import Generation
from ..models.query import QueryRequest, QueryResponse, ResponseKind
from ..models.sql import SqlAttempt
from .context_resolver import prepare_context
from .evidence import result_evidence
from .execution import execute_attempt
from .ports import ContextRepository, QueryExecutor, QueryGenerator, QueryRecorder, QuerySettings
from .repair_policy import MAX_SQL_ATTEMPTS, should_repair
from .summary import render_summary


class QueryService:
    def __init__(
        self,
        settings: QuerySettings,
        repository: ContextRepository,
        generator_session: Callable[[], AbstractContextManager[QueryGenerator]],
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
        """Record even configuration/serialization failures."""
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
                if result.table is not None:
                    response = self._answer(request, context, generation, sql, result)
                    return self._summarize(response, generator)
                if not should_repair(attempt, result):
                    return self._notice(
                        request, "SQL을 실행하지 못했습니다. 질문을 다시 제출해 주세요."
                    )
                sql = generator.repair(request, context, sql, result.error or "")
        raise RuntimeError("Unreachable SQL repair state")

    def _answer(
        self,
        request: QueryRequest,
        context: ResolvedContext,
        generation: Generation,
        sql: str,
        result: SqlAttempt,
    ) -> QueryResponse:
        if result.table is None:
            raise RuntimeError("Successful execution requires a table")
        evidence, warnings = result_evidence(
            result.table,
            sql,
            generation.sample,
            context.evidence,
            require_target=context.applied_conditions.conditions.character_code is not None,
        )
        return QueryResponse(
            ResponseKind.ANSWER,
            request.question,
            self.recorder.log_id,
            self.recorder.elapsed_ms,
            applied_conditions=context.applied_conditions,
            sql=result.guarded_sql,
            columns=result.table.columns,
            rows=result.table.rows,
            evidence=evidence,
            warnings=context.warnings + warnings,
        )

    def _summarize(self, response: QueryResponse, generator: QueryGenerator) -> QueryResponse:
        response.to_dict()  # Serialization errors remain fatal, not summary failures.
        summary = None
        warnings = response.warnings
        error = None
        if not response.rows:
            summary = "조회 결과가 없습니다."
        else:
            try:
                summary = render_summary(generator.summarize(response), response)
            except (LlmCallError, SummaryFormatError) as exception:
                error = str(exception) if isinstance(exception, LlmCallError) else "summary_format"
                warnings += ("요약을 생성하지 못했습니다. SQL과 표를 확인해 주세요.",)
        self.recorder.append(
            "summary_result", succeeded=summary is not None, error=error, summary=summary
        )
        return replace(
            response, summary=summary, warnings=warnings, elapsed_ms=self.recorder.elapsed_ms
        )
