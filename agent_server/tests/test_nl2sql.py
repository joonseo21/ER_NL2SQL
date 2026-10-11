from __future__ import annotations

import json
from collections.abc import Callable, Iterator
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import psycopg
import pytest
from google.genai import errors

from er_agent.bootstrap import run_query, run_question
from er_agent.models.context import ContextSnapshot
from er_agent.models.query import PatchVersion, QueryRequest, ResponseKind
from er_agent.models.sql import QueryTable
from er_agent.settings import Settings

SETTINGS = Settings("synthetic-url", "synthetic-key", "test-model")
GOOD_SQL = "SELECT count(*) AS samples FROM participants"
DUPLICATE_SQL = "SELECT count(*) AS value, count(DISTINCT game_id) AS value FROM participants"
SUMMARY = json.dumps(
    {
        "sentences": [
            {"template": "표본은 {0}건입니다.", "cells": [{"row": 0, "column": "samples"}]}
        ]
    },
    ensure_ascii=False,
)


def output(text: str) -> SimpleNamespace:
    return SimpleNamespace(text=text, usage_metadata=None)


def events(path: Path, kind: str | None = None) -> list[dict]:
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    return [row for row in rows if kind is None or row["event"] == kind]


@pytest.fixture
def execution(query_snapshot: ContextSnapshot) -> Iterator[MagicMock]:
    with (
        patch(
            "er_agent.database.context_repository.load_context_snapshot",
            return_value=query_snapshot,
        ),
        patch(
            "er_agent.database.query_executor.execute_table",
            return_value=QueryTable(("samples",), ({"samples": 4},)),
        ) as execute,
    ):
        yield execute


def test_success_validates_context_and_logs_returned_rows(
    tmp_path: Path,
    fake_client: MagicMock,
    execution: MagicMock,
    generation_text: Callable[..., str],
) -> None:
    fake_client.models.generate_content.side_effect = [output(generation_text()), output(SUMMARY)]
    path = tmp_path / "log.jsonl"
    answer = run_query(QueryRequest("참가자 수"), SETTINGS, path)
    assert answer.kind == ResponseKind.ANSWER
    assert answer.rows == ({"samples": 4},)
    assert answer.applied_conditions.conditions.patch.versions == (PatchVersion(5, 0),)
    assert answer.summary == "표본은 4건입니다."
    assert answer.evidence.sample_size == 4
    assert execution.call_args.args[2]["__er_season"] == 41
    log = events(path)
    assert log[-1]["response"] == answer.to_dict()
    assert log[-1]["logical_calls"] == log[-1]["physical_calls"] == 2
    assert all(row["log_id"] == answer.log_id for row in log)
    assert events(path, "sql_attempt")[0]["execution_succeeded"]


@pytest.mark.parametrize(
    "first",
    [
        "DELETE FROM participants",
        "SELECT nickname FROM participants",
        "SELECT count(*) FROM participants WHERE character_num = 1",
    ],
)
def test_guard_repair_is_one_call_and_repaired_sql_is_guarded(
    tmp_path: Path,
    fake_client: MagicMock,
    execution: MagicMock,
    generation_text: Callable[..., str],
    first: str,
) -> None:
    fake_client.models.generate_content.side_effect = [
        output(generation_text(first)),
        output(GOOD_SQL),
        output(SUMMARY),
    ]
    path = tmp_path / "log.jsonl"
    answer = run_query(QueryRequest("참가자 수"), SETTINGS, path)
    assert answer.kind == ResponseKind.ANSWER
    execution.assert_called_once()
    attempts = events(path, "sql_attempt")
    assert [attempt["guard_passed"] for attempt in attempts] == [False, True]
    assert [attempt["generated_sql"] for attempt in attempts] == [first, GOOD_SQL]
    assert events(path)[-1]["logical_calls"] == 3
    repair_prompt = fake_client.models.generate_content.call_args_list[1].kwargs["contents"]
    assert "Repair the failed SQL once" in repair_prompt
    assert "guard:" in repair_prompt


def test_database_error_is_repaired_with_sqlstate_without_private_message(
    tmp_path: Path,
    fake_client: MagicMock,
    execution: MagicMock,
    generation_text: Callable[..., str],
) -> None:
    fake_client.models.generate_content.side_effect = [
        output(generation_text()),
        output(GOOD_SQL),
        output(SUMMARY),
    ]
    execution.side_effect = [
        psycopg.errors.DivisionByZero("SECRET database row"),
        QueryTable(("samples",), ({"samples": 4},)),
    ]
    path = tmp_path / "log.jsonl"
    answer = run_query(QueryRequest("참가자 수"), SETTINGS, path)
    assert answer.kind == ResponseKind.ANSWER
    assert execution.call_count == 2
    assert events(path, "sql_attempt")[0]["error"] == "database: SQLSTATE 22012"
    assert "SECRET" not in path.read_text(encoding="utf-8")
    assert "SECRET" not in fake_client.models.generate_content.call_args.kwargs["contents"]


@pytest.mark.parametrize(
    "second", ["DELETE FROM participants", "SELECT nickname FROM participants"]
)
def test_failed_repair_stops_without_third_call(
    tmp_path: Path,
    fake_client: MagicMock,
    execution: MagicMock,
    generation_text: Callable[..., str],
    second: str,
) -> None:
    fake_client.models.generate_content.side_effect = [
        output(generation_text("DELETE FROM participants")),
        output(second),
    ]
    path = tmp_path / "log.jsonl"
    answer = run_query(QueryRequest("참가자 수"), SETTINGS, path)
    assert answer.kind == ResponseKind.NOTICE
    assert answer.sql is None and not answer.rows
    execution.assert_not_called()
    assert fake_client.models.generate_content.call_count == 2
    assert len(events(path, "sql_attempt")) == 2


def test_second_database_failure_also_stops(
    tmp_path: Path,
    fake_client: MagicMock,
    execution: MagicMock,
    generation_text: Callable[..., str],
) -> None:
    fake_client.models.generate_content.side_effect = [output(generation_text()), output(GOOD_SQL)]
    execution.side_effect = psycopg.errors.DivisionByZero("private")
    answer = run_query(QueryRequest("참가자 수"), SETTINGS, tmp_path / "log.jsonl")
    assert answer.kind == ResponseKind.NOTICE
    assert execution.call_count == fake_client.models.generate_content.call_count == 2


@pytest.mark.parametrize(
    "exception",
    [
        psycopg.OperationalError("SECRET connection"),
        psycopg.errors.InsufficientPrivilege("SECRET permission"),
    ],
)
def test_connection_and_privilege_errors_do_not_trigger_sql_repair(
    tmp_path: Path,
    fake_client: MagicMock,
    execution: MagicMock,
    generation_text: Callable[..., str],
    exception: psycopg.Error,
) -> None:
    fake_client.models.generate_content.return_value = output(generation_text())
    execution.side_effect = exception
    path = tmp_path / "log.jsonl"
    assert run_query(QueryRequest("참가자 수"), SETTINGS, path).kind == ResponseKind.NOTICE
    assert fake_client.models.generate_content.call_count == 1
    assert "SECRET" not in path.read_text(encoding="utf-8")


@pytest.mark.parametrize(
    "intent,kind",
    [
        ({"names": [{"text": "없는 명칭", "category": "character"}]}, ResponseKind.CLARIFICATION),
        ({"unsupported_reason": "personal_history"}, ResponseKind.NOTICE),
        ({"unsupported_reason": "prediction"}, ResponseKind.NOTICE),
        ({"unsupported_reason": "unrelated"}, ResponseKind.NOTICE),
        (
            {"patch": {"mode": "exact", "versions": [{"major": 999, "minor": 0}]}},
            ResponseKind.NOTICE,
        ),
        ({"tier": {"tier": "데미갓", "division": None}}, ResponseKind.NOTICE),
    ],
)
def test_early_responses_never_execute_or_repair_even_with_sql_draft(
    tmp_path: Path,
    fake_client: MagicMock,
    execution: MagicMock,
    generation_text: Callable[..., str],
    intent: dict,
    kind: ResponseKind,
) -> None:
    fake_client.models.generate_content.return_value = output(
        generation_text("DELETE FROM participants", **intent)
    )
    path = tmp_path / "log.jsonl"
    response = run_query(QueryRequest("질문"), SETTINGS, path)
    assert response.kind == kind and response.sql is None
    execution.assert_not_called()
    assert fake_client.models.generate_content.call_count == 1
    assert not events(path, "sql_attempt")


def test_model_format_error_is_not_a_sql_repair(
    tmp_path: Path, fake_client: MagicMock, execution: MagicMock
) -> None:
    fake_client.models.generate_content.return_value = output("not JSON SECRET")
    path = tmp_path / "log.jsonl"
    assert run_query(QueryRequest("질문"), SETTINGS, path).kind == ResponseKind.NOTICE
    execution.assert_not_called()
    assert fake_client.models.generate_content.call_count == 1
    assert events(path)[-1]["error"] == "generation_format"
    assert "SECRET" not in path.read_text(encoding="utf-8")


def test_api_retries_are_distinct_from_sql_repair(
    tmp_path: Path,
    fake_client: MagicMock,
    execution: MagicMock,
    generation_text: Callable[..., str],
) -> None:
    unavailable = errors.APIError(503, {"error": {"code": 503, "message": "SECRET api"}})
    fake_client.models.generate_content.side_effect = [
        unavailable,
        output(generation_text("DELETE FROM participants")),
        output(GOOD_SQL),
        output(SUMMARY),
    ]
    path = tmp_path / "log.jsonl"
    with patch("er_agent.generation.retry.time.sleep"):
        assert run_query(QueryRequest("질문"), SETTINGS, path).kind == ResponseKind.ANSWER
    assert events(path)[-1]["logical_calls"] == 3
    assert events(path)[-1]["physical_calls"] == 4
    assert [event["phase"] for event in events(path, "llm_attempt")] == [
        "generate",
        "generate",
        "repair",
        "summary",
    ]


def test_exhausted_api_requests_return_notice_and_leave_every_attempt(
    tmp_path: Path, fake_client: MagicMock, execution: MagicMock
) -> None:
    fake_client.models.generate_content.side_effect = errors.APIError(
        429, {"error": {"code": 429, "message": "SECRET api"}}
    )
    path = tmp_path / "log.jsonl"
    with patch("er_agent.generation.retry.time.sleep"):
        answer = run_query(QueryRequest("질문"), SETTINGS, path)
    assert answer.kind == ResponseKind.NOTICE and "429" in answer.message
    execution.assert_not_called()
    assert len(events(path, "llm_attempt")) == 3
    assert "SECRET" not in path.read_text(encoding="utf-8")


def test_snapshot_failure_logs_without_calling_model(
    tmp_path: Path, fake_client: MagicMock
) -> None:
    path = tmp_path / "log.jsonl"
    with (
        patch(
            "er_agent.database.context_repository.load_context_snapshot",
            side_effect=ValueError("SECRET db"),
        ),
        pytest.raises(ValueError),
    ):
        run_query(QueryRequest("질문"), SETTINGS, path)
    fake_client.models.generate_content.assert_not_called()
    assert events(path)[-1]["error"] == "ValueError"
    assert events(path)[-1]["physical_calls"] == 0
    assert "SECRET" not in path.read_text(encoding="utf-8")


def test_legacy_success_still_returns_rows(
    tmp_path: Path,
    fake_client: MagicMock,
    execution: MagicMock,
    generation_text: Callable[..., str],
) -> None:
    fake_client.models.generate_content.return_value = output(generation_text())
    assert run_question("질문", SETTINGS, tmp_path / "log.jsonl") == [{"samples": 4}]


@pytest.mark.parametrize("text", ["not JSON", "success", "clarification"])
def test_bootstrap_closes_generation_session_on_every_response(
    tmp_path: Path,
    fake_client: MagicMock,
    execution: MagicMock,
    generation_text: Callable[..., str],
    text: str,
) -> None:
    if text == "success":
        text = generation_text()
    elif text == "clarification":
        text = generation_text(names=[{"text": "없는 명칭", "category": "character"}])
    fake_client.models.generate_content.return_value = output(text)
    run_query(QueryRequest("질문"), SETTINGS, tmp_path / "log.jsonl")
    fake_client.__exit__.assert_called_once()


def test_unserializable_result_failure_still_finishes_log(
    tmp_path: Path,
    fake_client: MagicMock,
    execution: MagicMock,
    generation_text: Callable[..., str],
) -> None:
    fake_client.models.generate_content.return_value = output(generation_text())
    execution.return_value = QueryTable(("samples",), ({"samples": float("nan")},))
    path = tmp_path / "log.jsonl"
    with pytest.raises(ValueError):
        run_query(QueryRequest("질문"), SETTINGS, path)
    assert events(path)[-1]["error"] == "ValueError"
    assert events(path)[-1]["response"] is None


@pytest.mark.parametrize("value", [True, 0, -1, "41", None])
def test_collection_season_requires_positive_integer(value: object) -> None:
    with pytest.raises(ValueError, match="ER_SEASON_ID"):
        Settings("url", "key", "model", value)


def test_existing_season_environment_variable_is_shared() -> None:
    with patch("er_agent.settings.load_dotenv"), patch.dict("os.environ", {"ER_SEASON_ID": "42"}):
        assert Settings.from_env().season_id == 42


@pytest.mark.parametrize(
    "text", ["not JSON PRIVATE", '{"sentences":[]}', SUMMARY.replace('"row": 0', '"row": 1')]
)
def test_summary_failure_preserves_sql_and_table_without_repair(
    tmp_path: Path,
    fake_client: MagicMock,
    execution: MagicMock,
    generation_text: Callable[..., str],
    text: str,
) -> None:
    fake_client.models.generate_content.side_effect = [output(generation_text()), output(text)]
    path = tmp_path / "log.jsonl"
    answer = run_query(QueryRequest("참가자 수"), SETTINGS, path)
    assert answer.kind == ResponseKind.ANSWER and answer.summary is None
    assert answer.rows == ({"samples": 4},) and answer.sql
    assert answer.evidence.sample_size == 4
    assert any("요약을 생성하지" in warning for warning in answer.warnings)
    execution.assert_called_once()
    assert events(path)[-1]["logical_calls"] == 2
    assert events(path, "summary_result")[0]["error"] == "summary_format"
    assert "PRIVATE" not in path.read_text(encoding="utf-8")


@pytest.mark.parametrize("recovered", [True, False])
def test_summary_api_retries_never_reexecute_sql(
    tmp_path: Path,
    fake_client: MagicMock,
    execution: MagicMock,
    generation_text: Callable[..., str],
    recovered: bool,
) -> None:
    unavailable = errors.APIError(503, {"error": {"code": 503, "message": "PRIVATE api"}})
    tail = [unavailable, output(SUMMARY)] if recovered else [unavailable] * 3
    fake_client.models.generate_content.side_effect = [output(generation_text()), *tail]
    path = tmp_path / "log.jsonl"
    with patch("er_agent.generation.retry.time.sleep"):
        answer = run_query(QueryRequest("참가자 수"), SETTINGS, path)
    assert answer.kind == ResponseKind.ANSWER and answer.rows == ({"samples": 4},)
    assert (answer.summary is not None) is recovered
    assert events(path)[-1]["logical_calls"] == 2
    assert events(path)[-1]["physical_calls"] == (3 if recovered else 4)
    assert len(events(path, "sql_attempt")) == 1
    execution.assert_called_once()
    assert "PRIVATE" not in path.read_text(encoding="utf-8")


def test_empty_result_preserves_columns_and_skips_summary_call(
    tmp_path: Path,
    fake_client: MagicMock,
    execution: MagicMock,
    generation_text: Callable[..., str],
) -> None:
    fake_client.models.generate_content.return_value = output(
        generation_text("SELECT character_num FROM participants LIMIT 0", sample=None)
    )
    execution.return_value = QueryTable(("character_num",), ())
    path = tmp_path / "log.jsonl"
    answer = run_query(QueryRequest("질문"), SETTINGS, path)
    assert answer.columns == ("character_num",) and answer.rows == ()
    assert answer.summary == "조회 결과가 없습니다."
    assert answer.evidence.sample_size is None
    assert events(path)[-1]["logical_calls"] == 1
    fake_client.models.generate_content.assert_called_once()


@pytest.mark.parametrize("repaired", [True, False])
def test_duplicate_columns_repair_once_or_return_notice(
    tmp_path: Path,
    fake_client: MagicMock,
    execution: MagicMock,
    generation_text: Callable[..., str],
    repaired: bool,
) -> None:
    replies = [
        output(generation_text(DUPLICATE_SQL)),
        output(GOOD_SQL if repaired else DUPLICATE_SQL),
    ]
    fake_client.models.generate_content.side_effect = replies + (
        [output(SUMMARY)] if repaired else []
    )
    path = tmp_path / "log.jsonl"
    answer = run_query(QueryRequest("참가자 수"), SETTINGS, path)
    attempts = events(path, "sql_attempt")
    assert len(attempts) == 2 and not attempts[0]["guard_passed"]
    assert "Result column names must be unique" in attempts[0]["error"]
    assert attempts[0]["repairable"]
    if repaired:
        assert answer.kind == ResponseKind.ANSWER and answer.rows == ({"samples": 4},)
        execution.assert_called_once()
        assert fake_client.models.generate_content.call_count == 3
    else:
        assert answer.kind == ResponseKind.NOTICE and answer.sql is None
        execution.assert_not_called()
        assert fake_client.models.generate_content.call_count == 2
