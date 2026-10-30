"""Failure wording through the real service/HTTP/JSONL, with model and DB ports replaced."""

from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest
from fastapi.testclient import TestClient

from er_agent.http.app import create_app
from er_agent.http.config import HttpSettings
from er_agent.models.errors import DatabaseQueryError, LlmCallError
from er_agent.models.generation import TOKEN_FIELDS, ModelReply
from er_agent.models.query import QueryResponse, ResponseKind
from er_agent.settings import Settings

TEMPORARY_MODEL = "답변을 만드는 모델이 지금 응답하지 못했습니다. 잠시 뒤 다시 질문해 주세요."
MODEL_PROBLEM = "답변을 만드는 중 문제가 생겼습니다. 같은 문제가 계속되면 운영자에게 알려 주세요."
QUERY_TIMEOUT = (
    "조회에 시간이 너무 오래 걸렸습니다. "
    "패치, 티어, 실험체 조건을 좁히거나 질문 범위를 줄여 다시 질문해 주세요."
)
QUERY_FAILED = (
    "조회를 끝내지 못했습니다. 질문을 조금 다르게 표현하거나 조건을 바꿔 다시 질문해 주세요."
)
GOOD_SQL = "SELECT count(*) AS samples FROM participants"


def _reply(text: str) -> ModelReply:
    return ModelReply(text, dict.fromkeys(TOKEN_FIELDS))


def _invoke(tmp_path, snapshot, provider, transport, database_failures=None):
    settings = Settings(
        "unused-database",
        gemini_api_key="test-only",
        gemini_model="test-model",
        llm_provider=provider,
        openai_api_key="test-only",
        openai_model="test-model",
    )
    path = tmp_path / "notice.jsonl"
    with (
        patch("er_agent.bootstrap.PostgresContextRepository.load", return_value=snapshot),
        patch("er_agent.bootstrap.PostgresQueryExecutor.execute") as execute,
        patch("er_agent.bootstrap.model_transport") as factory,
        patch("er_agent.generation.retry.time.sleep"),
    ):
        factory.return_value.__enter__.return_value = transport
        execute.side_effect = (
            database_failures
            if database_failures is not None
            else AssertionError("This path must not execute a query")
        )
        app = create_app(settings, HttpSettings("a" * 64, path))
        with TestClient(app) as client:
            response = client.post(
                "/v1/query",
                headers={"X-Agent-Token": "a" * 64},
                json={"question": "참가자 수"},
            )
        factory.assert_called_once_with(settings.llm)
    records = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    assert all(record["provider"] == provider for record in records)
    assert records[-1]["response"] == response.json()
    return response, records, execute


def _assert_notice(response, message: str) -> None:
    assert response.status_code == 200
    payload = response.json()
    assert (
        payload
        == QueryResponse(
            ResponseKind.NOTICE,
            "참가자 수",
            payload["log_id"],
            payload["elapsed_ms"],
            message=message,
        ).to_dict()
    )
    text = json.dumps(payload, ensure_ascii=False)
    for term in (
        "LLM",
        "HTTP",
        "SQL",
        "SQLSTATE",
        "Gemini",
        "OpenAI",
        "test-model",
        "unavailable",
        "api_error",
        "57014",
        "503",
    ):
        assert term not in text
    assert "횟수" not in payload["message"]


@pytest.mark.parametrize("provider", ["gemini", "openai"])
@pytest.mark.parametrize(
    "category,code,expected",
    [
        ("unavailable", 503, TEMPORARY_MODEL),
        ("rate_limited", 429, TEMPORARY_MODEL),
        ("timeout", 408, TEMPORARY_MODEL),
        ("api_error", 500, TEMPORARY_MODEL),
        ("api_error", 502, TEMPORARY_MODEL),
        ("api_error", 504, TEMPORARY_MODEL),
        ("api_error", 400, MODEL_PROBLEM),
        ("api_error", 401, MODEL_PROBLEM),
        ("api_error", 403, MODEL_PROBLEM),
        ("api_error", 404, MODEL_PROBLEM),
        ("api_error", 422, MODEL_PROBLEM),
        ("api_error", 402, MODEL_PROBLEM),
        ("timeout", 401, MODEL_PROBLEM),
        ("unavailable", None, TEMPORARY_MODEL),
        ("rate_limited", None, TEMPORARY_MODEL),
        ("timeout", None, TEMPORARY_MODEL),
        ("empty_response", None, TEMPORARY_MODEL),
        ("incomplete_response", None, TEMPORARY_MODEL),
        ("transport_or_sdk_error", None, MODEL_PROBLEM),
        ("model_refusal", None, MODEL_PROBLEM),
        ("unexpected_category", None, MODEL_PROBLEM),
    ],
)
def test_model_failures_use_korean_notice_and_preserve_diagnostic_records(
    tmp_path: Path, query_snapshot, provider, category, code, expected
) -> None:
    error = LlmCallError(category, code)
    transport = MagicMock()
    transport.complete.side_effect = error
    response, records, execute = _invoke(tmp_path, query_snapshot, provider, transport)
    _assert_notice(response, expected)
    assert category not in response.json()["message"]
    if code is not None:
        assert str(code) not in response.json()["message"]
    execute.assert_not_called()
    attempts = [record for record in records if record["event"] == "llm_attempt"]
    count = 3 if code in {408, 429, 500, 502, 503, 504} else 1
    assert len(attempts) == transport.complete.call_count == count
    assert all(record["error_category"] == category for record in attempts)
    assert all(record["error_code"] == code for record in attempts)
    assert records[-1]["error"] == str(error)


@pytest.mark.parametrize("provider", ["gemini", "openai"])
@pytest.mark.parametrize(
    "codes,expected",
    [
        (["57014", "57014"], QUERY_TIMEOUT),
        (["42601", "57014"], QUERY_TIMEOUT),
        (["57014", "42601"], QUERY_FAILED),
        (["42601", "42601"], QUERY_FAILED),
        (["22012", "22012"], QUERY_FAILED),
        (["42501"], QUERY_FAILED),
        (["08006"], QUERY_FAILED),
        ([None], QUERY_FAILED),
    ],
)
def test_query_failures_use_the_final_cause_and_preserve_repair_budget(
    tmp_path: Path, query_snapshot, generation_text, provider, codes, expected
) -> None:
    transport = MagicMock()
    transport.complete.side_effect = [_reply(generation_text(GOOD_SQL)), _reply(GOOD_SQL)]
    failures = [DatabaseQueryError(code) for code in codes]
    response, records, execute = _invoke(tmp_path, query_snapshot, provider, transport, failures)
    _assert_notice(response, expected)
    for code in codes:
        assert (code or "unavailable") not in response.json()["message"]
    attempts = [record for record in records if record["event"] == "sql_attempt"]
    assert [record["error"] for record in attempts] == [str(error) for error in failures]
    assert len(attempts) == execute.call_count == transport.complete.call_count == len(codes)
    assert records[-1]["error"] is None  # Execution causes stay in sql_attempt, as before.


@pytest.mark.parametrize("provider", ["gemini", "openai"])
def test_model_failure_during_repair_keeps_the_guard_record(
    tmp_path: Path, query_snapshot, generation_text, provider
) -> None:
    transport = MagicMock()
    transport.complete.side_effect = [
        _reply(generation_text("DELETE FROM participants")),
        *[LlmCallError("unavailable", 503)] * 3,
    ]
    response, records, execute = _invoke(tmp_path, query_snapshot, provider, transport)
    _assert_notice(response, TEMPORARY_MODEL)
    execute.assert_not_called()
    attempts = [record for record in records if record["event"] == "llm_attempt"]
    assert [record["phase"] for record in attempts] == ["generate", "repair", "repair", "repair"]
    sql_attempts = [record for record in records if record["event"] == "sql_attempt"]
    assert len(sql_attempts) == 1 and sql_attempts[0]["error"].startswith("guard:")
    assert records[-1]["error"] == "LLM unavailable (HTTP 503)"


def test_final_guard_rejection_keeps_its_existing_notice(
    tmp_path: Path, query_snapshot, generation_text
) -> None:
    transport = MagicMock()
    transport.complete.side_effect = [
        _reply(generation_text("DELETE FROM participants")),
        _reply("DELETE FROM participants"),
    ]
    response, records, execute = _invoke(tmp_path, query_snapshot, "gemini", transport)
    assert response.status_code == 200
    assert response.json()["kind"] == "notice"
    assert response.json()["message"] == "SQL을 실행하지 못했습니다. 질문을 다시 제출해 주세요."
    execute.assert_not_called()
    assert transport.complete.call_count == 2
    assert all(
        record["error"].startswith("guard:")
        for record in records
        if record["event"] == "sql_attempt"
    )
