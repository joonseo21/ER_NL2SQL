from __future__ import annotations

import json
from collections.abc import Callable, Iterator
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import psycopg
import pytest
from google.genai import errors

from er_agent.context import ContextSnapshot
from er_agent.contracts import PatchVersion, QueryRequest, ResponseKind
from er_agent.nl2sql import run_query, run_question
from er_agent.settings import Settings

SETTINGS = Settings("synthetic-url", "synthetic-key", "test-model")
GOOD_SQL = "SELECT count(*) AS samples FROM participants"


def output(text: str) -> SimpleNamespace:
    return SimpleNamespace(text=text, usage_metadata=None)


def events(path: Path, kind: str | None = None) -> list[dict]:
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    return [row for row in rows if kind is None or row["event"] == kind]


@pytest.fixture
def execution(query_snapshot: ContextSnapshot) -> Iterator[MagicMock]:
    with (
        patch("er_agent.nl2sql.load_context_snapshot", return_value=query_snapshot),
        patch("er_agent.nl2sql.execute_select", return_value=[{"samples": 4}]) as execute,
    ):
        yield execute


def test_success_validates_context_and_logs_returned_rows(
    tmp_path: Path,
    fake_client: MagicMock,
    execution: MagicMock,
    generation_text: Callable[..., str],
) -> None:
    fake_client.models.generate_content.return_value = output(generation_text())
    path = tmp_path / "log.jsonl"
    answer = run_query(QueryRequest("참가자 수"), SETTINGS, path)
    assert answer.kind == ResponseKind.ANSWER
    assert answer.rows == ({"samples": 4},)
    assert answer.applied_conditions.conditions.patch.versions == (PatchVersion(5, 0),)
    assert answer.summary is None
    assert execution.call_args.args[2]["__er_season"] == 41
    log = events(path)
    assert log[-1]["response"] == answer.to_dict()
    assert log[-1]["logical_calls"] == log[-1]["physical_calls"] == 1
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
    ]
    path = tmp_path / "log.jsonl"
    answer = run_query(QueryRequest("참가자 수"), SETTINGS, path)
    assert answer.kind == ResponseKind.ANSWER
    execution.assert_called_once()
    attempts = events(path, "sql_attempt")
    assert [attempt["guard_passed"] for attempt in attempts] == [False, True]
    assert [attempt["generated_sql"] for attempt in attempts] == [first, GOOD_SQL]
    assert events(path)[-1]["logical_calls"] == 2
    repair_prompt = fake_client.models.generate_content.call_args.kwargs["contents"]
    assert "Repair the failed SQL once" in repair_prompt
    assert "guard:" in repair_prompt


def test_database_error_is_repaired_with_sqlstate_without_private_message(
    tmp_path: Path,
    fake_client: MagicMock,
    execution: MagicMock,
    generation_text: Callable[..., str],
) -> None:
    fake_client.models.generate_content.side_effect = [output(generation_text()), output(GOOD_SQL)]
    execution.side_effect = [psycopg.errors.DivisionByZero("SECRET database row"), [{"samples": 4}]]
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
    ]
    path = tmp_path / "log.jsonl"
    with patch("er_agent.llm.time.sleep"):
        assert run_query(QueryRequest("질문"), SETTINGS, path).kind == ResponseKind.ANSWER
    assert events(path)[-1]["logical_calls"] == 2
    assert events(path)[-1]["physical_calls"] == 3
    assert [event["phase"] for event in events(path, "llm_attempt")] == [
        "generate",
        "generate",
        "repair",
    ]


def test_exhausted_api_requests_return_notice_and_leave_every_attempt(
    tmp_path: Path, fake_client: MagicMock, execution: MagicMock
) -> None:
    fake_client.models.generate_content.side_effect = errors.APIError(
        429, {"error": {"code": 429, "message": "SECRET api"}}
    )
    path = tmp_path / "log.jsonl"
    with patch("er_agent.llm.time.sleep"):
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
        patch("er_agent.nl2sql.load_context_snapshot", side_effect=ValueError("SECRET db")),
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


def test_unserializable_result_failure_still_finishes_log(
    tmp_path: Path,
    fake_client: MagicMock,
    execution: MagicMock,
    generation_text: Callable[..., str],
) -> None:
    fake_client.models.generate_content.return_value = output(generation_text())
    execution.return_value = [{"samples": float("nan")}]
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
