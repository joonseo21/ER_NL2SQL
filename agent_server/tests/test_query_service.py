"""Query policies run with ports and an in-memory recorder, without real adapters."""

from __future__ import annotations

from contextlib import nullcontext
from dataclasses import dataclass
from typing import Any
from unittest.mock import MagicMock

import pytest

from er_agent.models.context import ContextSnapshot, QueryIntent, ResolvedContext
from er_agent.models.errors import DatabaseQueryError
from er_agent.models.generation import Generation
from er_agent.models.names import NameTerm
from er_agent.models.query import QueryRequest, ResponseKind
from er_agent.models.sql import QueryTable
from er_agent.models.summary import CellReference, SummaryDraft, SummarySentence
from er_agent.query.service import QueryService
from er_agent.recording.trial import TrialRecorder


class MemorySink:
    def __init__(self) -> None:
        self.records: list[dict[str, Any]] = []

    def write(self, record: dict[str, Any]) -> None:
        self.records.append(record)


@dataclass(frozen=True)
class ConfiguredQuery:
    season_id: int = 41

    def require_database(self) -> None:
        pass

    def require_gemini(self) -> None:
        pass


def service(
    snapshot: ContextSnapshot, generation: Generation, failures: list[object]
) -> tuple[QueryService, MagicMock, MagicMock, MemorySink]:
    sink = MemorySink()
    recorder = TrialRecorder(sink, "질문", "test-model")
    repository = MagicMock()
    repository.load.return_value = snapshot
    generator = MagicMock()
    generator.generate.return_value = generation
    generator.repair.return_value = "SELECT count(*) AS samples FROM participants"
    generator.summarize.return_value = SummaryDraft(
        (SummarySentence("표본은 {0}건입니다.", (CellReference(0, "samples"),)),)
    )
    executor = MagicMock()
    executor.execute.side_effect = [
        QueryTable(tuple(value[0]) if value else (), tuple(value))
        if isinstance(value, list)
        else value
        for value in failures
    ]
    query = QueryService(
        ConfiguredQuery(), repository, lambda: nullcontext(generator), executor, recorder
    )
    return query, executor, generator, sink


def test_service_uses_neutral_error_and_preserves_context_during_repair(
    query_snapshot: ContextSnapshot,
) -> None:
    query, executor, generator, sink = service(
        query_snapshot,
        Generation(QueryIntent(), "SELECT count(*)/0 AS samples FROM participants"),
        [DatabaseQueryError("22012"), [{"samples": 4}]],
    )
    answer = query.run(QueryRequest("질문"))
    assert answer.kind == ResponseKind.ANSWER
    assert executor.execute.call_count == 2
    assert generator.repair.call_count == 1
    context = generator.repair.call_args.args[1]
    assert isinstance(context, ResolvedContext)
    assert context.applied_conditions == answer.applied_conditions
    assert executor.execute.call_args_list[0].args[1] == executor.execute.call_args_list[1].args[1]
    assert generator.repair.call_args.args[3] == "database: SQLSTATE 22012"
    assert sink.records[-1]["response"] == answer.to_dict()


@pytest.mark.parametrize("code", [None, "42501", "08006"])
def test_service_does_not_repair_connection_or_permission_failure(
    query_snapshot: ContextSnapshot, code: str | None
) -> None:
    query, executor, generator, _ = service(
        query_snapshot,
        Generation(QueryIntent(), "SELECT count(*) AS samples FROM participants"),
        [DatabaseQueryError(code)],
    )
    assert query.run(QueryRequest("질문")).kind == ResponseKind.NOTICE
    executor.execute.assert_called_once()
    generator.repair.assert_not_called()


def test_service_clarifies_unverified_name_before_executor_port(
    query_snapshot: ContextSnapshot,
) -> None:
    query, executor, generator, sink = service(
        query_snapshot,
        Generation(QueryIntent(names=(NameTerm("없는 명칭"),)), "DELETE FROM participants"),
        [],
    )
    assert query.run(QueryRequest("질문")).kind == ResponseKind.CLARIFICATION
    executor.execute.assert_not_called()
    generator.repair.assert_not_called()
    assert not any(record["event"] == "sql_attempt" for record in sink.records)
