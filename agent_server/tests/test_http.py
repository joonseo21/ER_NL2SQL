from __future__ import annotations

import json
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path
from threading import Event
from unittest.mock import Mock, patch

import anyio
import httpx
import pytest
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

from er_agent.http.app import create_app
from er_agent.http.config import HttpSettings
from er_agent.http.openapi import build_openapi
from er_agent.models.names import NameCandidate, NameCategory, NameClarification
from er_agent.models.query import (
    AppliedConditions,
    DataEvidence,
    PatchFilter,
    PatchMode,
    PatchVersion,
    QueryConditions,
    QueryResponse,
    ResponseKind,
)
from er_agent.paths import REPOSITORY_ROOT
from er_agent.settings import Settings

TOKEN = "a" * 64
HEADERS = {"X-Agent-Token": TOKEN}
SETTINGS = Settings("unused-database", "unused-key", "unused-model")


def response(kind=ResponseKind.ANSWER, **changes):
    values = dict(kind=kind, question="질문", log_id="trial-1", elapsed_ms=12)
    if kind == ResponseKind.ANSWER:
        values.update(
            applied_conditions=AppliedConditions(
                41, QueryConditions(PatchFilter(PatchMode.EXACT, (PatchVersion(5, 0),)))
            ),
            sql="SELECT 10 AS samples",
            columns=("samples",),
            rows=({"samples": Decimal("10.00000000000000000001")},),
            evidence=DataEvidence(
                10, "participant_appearances", datetime(2026, 10, 12, tzinfo=UTC)
            ),
            warnings=("표본 30건 미만",),
        )
    else:
        values["message"] = "질문을 확인해 주세요."
        if kind == ResponseKind.CLARIFICATION:
            values["clarifications"] = (
                NameClarification(
                    "이름", (NameCandidate(NameCategory.CHARACTER, 17, "아드리아나"),)
                ),
            )
    return QueryResponse(**(values | changes))


def client(runner=None, log_path=Path("unused-log")):
    return TestClient(
        create_app(
            SETTINGS, HttpSettings(TOKEN, log_path), runner=runner or Mock(return_value=response())
        )
    )


@pytest.mark.parametrize("kind", list(ResponseKind))
def test_three_response_kinds_are_200_and_preserve_payload(kind):
    expected = response(kind)
    runner = Mock(return_value=expected)
    with client(runner) as http:
        result = http.post("/v1/query", headers=HEADERS, json={"question": "질문"})
    assert result.status_code == 200
    assert result.json() == expected.to_dict()
    request, settings, log_path = runner.call_args.args
    assert request.question == "질문"
    assert request.conditions.patch.mode == PatchMode.LATEST
    assert settings is SETTINGS
    assert log_path == Path("unused-log")
    assert result.json()["chart"] is None
    assert result.json()["conversation_context"] is None


def test_explicit_conditions_and_question_are_passed_unchanged():
    runner = Mock(return_value=response())
    selected = {
        "patch": {"mode": "exact", "versions": [{"major": 5, "minor": 0}]},
        "tier": "다이아몬드",
        "tier_division": 4,
        "character_code": 17,
    }
    with client(runner) as http:
        assert (
            http.post(
                "/v1/query", headers=HEADERS, json={"question": "  질문  ", "conditions": selected}
            ).status_code
            == 200
        )
    request = runner.call_args.args[0]
    assert request.question == "  질문  "
    assert request.conditions.character_code == 17
    assert request.conditions.tier_division == 4
    assert request.conditions.patch.versions == (PatchVersion(5, 0),)


@pytest.mark.parametrize(
    "headers",
    [
        {},
        {"X-Agent-Token": ""},
        {"X-Agent-Token": "b" * 64},
        {"X-Agent-Token": TOKEN.upper()},
        {"X-Internal": "true"},
        [("X-Agent-Token", TOKEN), ("X-Agent-Token", TOKEN)],
    ],
)
def test_invalid_internal_tokens_are_rejected_before_invalid_json(headers):
    runner = Mock()
    with client(runner) as http:
        result = http.post("/v1/query", headers=headers, content="not-json")
    assert result.status_code == 401
    assert result.headers["WWW-Authenticate"] == 'ApiKey realm="agent"'
    assert result.json()["detail"]["code"] == "unauthorized_internal_call"
    assert runner.call_count == 0
    assert TOKEN not in result.text


def test_header_name_is_case_insensitive():
    with client() as http:
        assert (
            http.post(
                "/v1/query", headers={"x-agent-token": TOKEN}, json={"question": "질문"}
            ).status_code
            == 200
        )


@pytest.mark.parametrize(
    "body",
    [
        {},
        {"question": ""},
        {"question": "   "},
        {"question": 7},
        {"question": "질문", "conditions": None},
        {"question": "질문", "log_id": "client-id"},
        {"question": "질문", "conversation_context": "previous"},
        {"question": "질문", "conditions": {"character_code": "17"}},
        {"question": "질문", "conditions": {"character_code": True}},
        {"question": "질문", "conditions": {"character_code": 17.0}},
        {"question": "질문", "conditions": {"character_code": 0}},
        {"question": "질문", "conditions": {"tier_division": 1}},
        {"question": "질문", "conditions": {"patch": {"mode": "exact"}}},
        {
            "question": "질문",
            "conditions": {"patch": {"mode": "all", "versions": [{"major": 5, "minor": 0}]}},
        },
        {
            "question": "질문",
            "conditions": {"patch": {"mode": "exact", "versions": [{"major": 5, "minor": 0}] * 2}},
        },
        {"question": "질문", "conditions": {"unexpected": "private-input"}},
    ],
)
def test_invalid_request_is_422_without_execution_or_raw_inputs(body):
    runner = Mock()
    with client(runner) as http:
        result = http.post("/v1/query", headers=HEADERS, json=body)
    assert result.status_code == 422
    assert result.json()["detail"]["code"] == "invalid_request"
    assert runner.call_count == 0
    assert "private-input" not in result.text
    assert "input" not in result.json()["detail"]


def test_malformed_json_is_sanitized():
    with client() as http:
        result = http.post("/v1/query", headers=HEADERS, content='{"question": "private-input"')
    assert result.status_code == 422
    assert "private-input" not in result.text


def test_invalid_json_encoding_is_422():
    with client() as http:
        result = http.post(
            "/v1/query", headers=HEADERS | {"Content-Type": "application/json"}, content=b'"\xff"'
        )
    assert result.status_code == 422
    assert result.json()["detail"]["code"] == "invalid_request"


def test_unknown_field_names_are_not_echoed_as_error_paths():
    with client() as http:
        result = http.post(
            "/v1/query", headers=HEADERS, json={"question": "질문", "private-input-name": "value"}
        )
    assert result.status_code == 422
    assert "private-input-name" not in result.text
    assert result.json()["detail"]["issues"][0]["path"] == ["body", "unknown_field"]


@pytest.mark.parametrize("changes", [{"rows": ()}, {"summary": None, "warnings": ("요약 실패",)}])
def test_empty_result_and_summary_failure_preserve_sql_and_columns(changes):
    expected = response(**changes)
    with client(Mock(return_value=expected)) as http:
        result = http.post("/v1/query", headers=HEADERS, json={"question": "질문"})
    assert result.status_code == 200
    assert result.json() == expected.to_dict()


def test_server_exception_is_sanitized_and_admission_is_released(caplog):
    runner = Mock(side_effect=[RuntimeError("secret-db-url " + TOKEN), response()])
    with client(runner) as http:
        result = http.post("/v1/query", headers=HEADERS, json={"question": "질문"})
        assert result.status_code == 500
        assert result.json()["detail"]["code"] == "internal_error"
        assert http.post("/v1/query", headers=HEADERS, json={"question": "질문"}).status_code == 200
    assert "secret-db-url" not in result.text + caplog.text
    assert TOKEN not in result.text + caplog.text


def test_one_query_at_a_time_without_queue_and_health_remains_responsive():
    started, finish = Event(), Event()

    def slow_runner(*args):
        started.set()
        assert finish.wait(5)
        return response()

    with client(slow_runner) as http, ThreadPoolExecutor(max_workers=1) as executor:
        first = executor.submit(http.post, "/v1/query", headers=HEADERS, json={"question": "질문"})
        try:
            assert started.wait(3)
            second = http.post("/v1/query", headers=HEADERS, json={"question": "질문"})
            assert second.status_code == 503
            assert second.json()["detail"]["code"] == "service_unavailable"
            assert http.get("/health").json() == {"status": "ok"}
        finally:
            finish.set()
        assert first.result(timeout=3).status_code == 200
        assert http.post("/v1/query", headers=HEADERS, json={"question": "질문"}).status_code == 200


def test_cancelled_caller_does_not_release_admission_before_worker_finishes():
    started, finish = Event(), Event()

    def slow_runner(*args):
        started.set()
        assert finish.wait(5)
        return response()

    async def check():
        app = create_app(SETTINGS, HttpSettings(TOKEN), runner=slow_runner)
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app), base_url="http://test"
        ) as http:
            scope = anyio.CancelScope()

            async def first():
                with scope:
                    await http.post("/v1/query", headers=HEADERS, json={"question": "질문"})

            async with anyio.create_task_group() as group:
                group.start_soon(first)
                try:
                    assert await anyio.to_thread.run_sync(started.wait, 3)
                    scope.cancel()
                    rejected = await http.post(
                        "/v1/query", headers=HEADERS, json={"question": "질문"}
                    )
                    assert rejected.status_code == 503
                finally:
                    finish.set()
            assert (
                await http.post("/v1/query", headers=HEADERS, json={"question": "질문"})
            ).status_code == 200

    anyio.run(check)


@pytest.mark.parametrize("path", ["/docs", "/redoc", "/openapi.json", "/v1/query/"])
def test_undocumented_or_public_documentation_paths_are_unavailable(path):
    with client() as http:
        assert http.get(path, headers=HEADERS).status_code == 404


@pytest.mark.parametrize("token", ["", "a" * 63, "A" * 64, "g" * 64, " " + TOKEN])
def test_invalid_server_token_fails_without_revealing_it(token):
    with pytest.raises(ValueError, match="AGENT_INTERNAL_TOKEN") as error:
        HttpSettings(token)
    if token:
        assert token not in str(error.value)


def test_token_is_excluded_from_settings_repr():
    assert TOKEN not in repr(HttpSettings(TOKEN))


def test_http_settings_read_environment_and_log_path(tmp_path, monkeypatch):
    monkeypatch.setenv("AGENT_INTERNAL_TOKEN", TOKEN)
    monkeypatch.setenv("AGENT_LOG_PATH", str(tmp_path / "query.jsonl"))
    with patch("er_agent.http.config.load_dotenv"):
        settings = HttpSettings.from_env()
    assert settings.internal_token == TOKEN
    assert settings.log_path == tmp_path / "query.jsonl"


def test_missing_query_configuration_prevents_server_start():
    with pytest.raises(ValueError, match="AGENT_DATABASE_URL"):
        create_app(Settings("", "unused", "unused"), HttpSettings(TOKEN))


def test_default_runner_records_model_failure_and_returns_notice(tmp_path, query_snapshot):
    from er_agent.models.errors import LlmCallError

    log_path = tmp_path / "query.jsonl"
    with (
        patch("er_agent.bootstrap.PostgresContextRepository.load", return_value=query_snapshot),
        patch("er_agent.bootstrap.create_client", side_effect=LlmCallError("api_error", 503)),
        TestClient(create_app(SETTINGS, HttpSettings(TOKEN, log_path))) as http,
    ):
        result = http.post("/v1/query", headers=HEADERS, json={"question": "질문"})
    assert result.status_code == 200
    assert result.json()["kind"] == "notice"
    records = [json.loads(line) for line in log_path.read_text(encoding="utf-8").splitlines()]
    assert records
    assert result.json()["log_id"] in log_path.read_text(encoding="utf-8")
    assert TOKEN not in log_path.read_text(encoding="utf-8")


def test_openapi_schemas_and_examples_match_running_adapter():
    doc = build_openapi()
    assert doc["components"] == create_app(SETTINGS, HttpSettings(TOKEN)).openapi()["components"]
    saved = REPOSITORY_ROOT / "docs/contracts/agent-openapi.json"
    if saved.is_file():
        assert json.loads(saved.read_text(encoding="utf-8")) == doc
    assert doc["openapi"] == "3.1.0"
    assert set(doc["paths"]) == {"/v1/query", "/health"}
    assert doc["paths"]["/v1/query"]["post"]["security"] == [{"AgentInternalToken": []}]
    assert not doc["paths"]["/health"]["get"].get("security")
    for schema in doc["components"]["schemas"].values():
        Draft202012Validator.check_schema(schema)
    registry = Registry().with_resource(
        "urn:agent-api",
        Resource.from_contents({"$schema": "https://json-schema.org/draft/2020-12/schema", **doc}),
    )
    validator = Draft202012Validator(
        {"$ref": "urn:agent-api#/components/schemas/QueryReply"},
        registry=registry,
        format_checker=FormatChecker(),
    )
    for file in (REPOSITORY_ROOT / "docs/contracts/samples").glob("*.json"):
        validator.validate(json.loads(file.read_text(encoding="utf-8")))
    for kind in ResponseKind:
        validator.validate(response(kind).to_dict())
