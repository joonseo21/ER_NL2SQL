"""Exercise the real SDK through an in-memory HTTP transport, without API access."""

from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import patch

import httpx
import pytest
from openai import OpenAI

from er_agent.bootstrap import run_query
from er_agent.generation.factory import model_transport
from er_agent.generation.openai_transport import OpenAITransport, create_client
from er_agent.generation.retry import RetryingCaller
from er_agent.generation.schema import GENERATION_SCHEMA
from er_agent.generation.summary import SUMMARY_SCHEMA
from er_agent.models.errors import LlmCallError
from er_agent.models.generation import TOKEN_FIELDS, ModelSettings
from er_agent.models.query import QueryRequest, ResponseKind
from er_agent.models.sql import QueryTable
from er_agent.recording.jsonl import JsonlSink
from er_agent.recording.trial import TrialRecorder
from er_agent.settings import Settings


def reply(**changes: object) -> dict:
    value = {
        "id": "resp_test",
        "object": "response",
        "created_at": 0,
        "model": "test-gpt",
        "status": "completed",
        "output": [
            {"id": "reasoning_test", "type": "reasoning", "summary": [], "content": None},
            {
                "id": "msg_test",
                "type": "message",
                "role": "assistant",
                "status": "completed",
                "content": [
                    {"type": "output_text", "text": '{"sql":"SELECT 1"}', "annotations": []}
                ],
            },
        ],
        "usage": {
            "input_tokens": 100,
            "output_tokens": 30,
            "total_tokens": 130,
            "input_tokens_details": {"cached_tokens": 60},
            "output_tokens_details": {"reasoning_tokens": 20},
        },
    }
    value.update(changes)
    return value


def sdk(handler) -> OpenAI:
    return OpenAI(
        api_key="test-only",
        base_url="https://api.openai.com/v1",
        max_retries=0,
        http_client=httpx.Client(transport=httpx.MockTransport(handler)),
    )


@pytest.mark.parametrize("schema", [None, GENERATION_SCHEMA, SUMMARY_SCHEMA])
@pytest.mark.parametrize("effort", ["", "low"])
def test_wire_request_and_usage_are_provider_independent(schema: dict | None, effort: str) -> None:
    requests = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json=reply())

    with sdk(handler) as client:
        result = OpenAITransport(client, "test-gpt", effort).complete("prompt", schema)
    request = json.loads(requests[0].content)
    assert requests[0].url == "https://api.openai.com/v1/responses"
    assert request["store"] is False
    assert request["model"] == "test-gpt" and request["input"] == "prompt"
    if schema is None:
        assert "text" not in request
    else:
        assert request["text"]["format"] == {
            "type": "json_schema",
            "strict": True,
            "name": "agent_reply",
            "schema": schema,
        }
    assert request.get("reasoning") == ({"effort": effort} if effort else None)
    assert result.text == '{"sql":"SELECT 1"}' and result.error_category is None
    assert result.tokens == dict(zip(TOKEN_FIELDS, (100, 10, 130, 20, 60), strict=True))


def test_client_has_explicit_endpoint_timeout_and_no_hidden_retries() -> None:
    with create_client("test-only", 90) as client:
        assert str(client.base_url) == "https://api.openai.com/v1/"
        assert client.timeout == 90
        assert client.max_retries == 0


def test_missing_usage_remains_unknown() -> None:
    with sdk(lambda request: httpx.Response(200, json=reply(usage=None))) as client:
        result = OpenAITransport(client, "test-gpt").complete("prompt")
    assert result.tokens == dict.fromkeys(TOKEN_FIELDS)


@pytest.mark.parametrize("kind", ["incomplete", "refusal"])
def test_partial_or_refused_output_is_not_accepted_and_usage_is_recorded(
    tmp_path: Path, kind: str
) -> None:
    payload = reply()
    if kind == "incomplete":
        payload["status"] = "incomplete"
    else:
        payload["output"][-1]["content"] = [{"type": "refusal", "refusal": "SECRET"}]
    recorder = TrialRecorder(JsonlSink(tmp_path / "log.jsonl"), "question", "test-gpt", "openai")
    with (
        sdk(lambda request: httpx.Response(200, json=payload)) as client,
        pytest.raises(LlmCallError),
    ):
        RetryingCaller(OpenAITransport(client, "test-gpt"), recorder).generate("prompt", "generate")
    log = recorder.sink.path.read_text(encoding="utf-8")
    event = json.loads(log)
    assert event["tokens"]["total_token_count"] == 130
    assert event["provider"] == "openai" and event["succeeded"] is False
    assert recorder.physical_calls == 1
    assert "SECRET" not in log


@pytest.mark.parametrize("code,count", [(429, 3), (503, 3), (504, 3), (400, 1), (401, 1)])
def test_retry_policy_is_shared_and_errors_are_sanitized(
    tmp_path: Path, code: int, count: int
) -> None:
    requests = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(code, json={"error": {"message": "SECRET", "type": "test_error"}})

    recorder = TrialRecorder(JsonlSink(tmp_path / "log.jsonl"), "question", "test-gpt", "openai")
    with (
        sdk(handler) as client,
        patch("er_agent.generation.retry.time.sleep"),
        pytest.raises(LlmCallError) as error,
    ):
        RetryingCaller(OpenAITransport(client, "test-gpt"), recorder).generate("prompt", "generate")
    assert error.value.code == code
    assert len(requests) == recorder.physical_calls == count
    assert "SECRET" not in str(error.value) + recorder.sink.path.read_text(encoding="utf-8")


def test_timeouts_use_shared_retry_policy(tmp_path: Path) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("SECRET", request=request)

    recorder = TrialRecorder(JsonlSink(tmp_path / "log.jsonl"), "question", "test-gpt", "openai")
    with (
        sdk(handler) as client,
        patch("er_agent.generation.retry.time.sleep"),
        pytest.raises(LlmCallError) as error,
    ):
        RetryingCaller(OpenAITransport(client, "test-gpt"), recorder).generate("prompt", "summary")
    assert error.value.code == 408
    assert recorder.physical_calls == 3


@pytest.mark.parametrize(
    "provider,module,transport",
    [
        ("gemini", "gemini", "GeminiTransport"),
        ("openai", "openai_transport", "OpenAITransport"),
    ],
)
def test_factory_selects_and_closes_only_the_selected_client(
    provider: str, module: str, transport: str
) -> None:
    settings = ModelSettings(provider, "test-model", "secret-key", 30, "low")
    with (
        patch("er_agent.generation.factory.gemini.create_client") as gemini,
        patch("er_agent.generation.factory.openai_transport.create_client") as openai,
        patch(f"er_agent.generation.factory.{module}.{transport}") as adapter,
    ):
        factory, unused = (gemini, openai) if provider == "gemini" else (openai, gemini)
        with model_transport(settings) as selected:
            assert selected is adapter.return_value
        factory.assert_called_once_with("secret-key", 30)
        adapter.assert_called_once_with(
            factory.return_value.__enter__.return_value, "test-model", "low"
        )
        factory.return_value.__exit__.assert_called_once()
        unused.assert_not_called()
    assert "secret-key" not in repr(settings)


@pytest.mark.parametrize("repair", [False, True])
def test_openai_runs_shared_sql_guard_repair_and_summary(
    tmp_path: Path,
    query_snapshot,
    generation_text,
    repair: bool,
) -> None:
    good_sql = "SELECT count(*) AS samples FROM participants"
    summary = json.dumps(
        {
            "sentences": [
                {
                    "template": "표본은 {0}건입니다.",
                    "cells": [{"row": 0, "column": "samples"}],
                }
            ]
        }
    )
    texts = [generation_text("DELETE FROM participants" if repair else good_sql)]
    if repair:
        texts.append(good_sql)
    texts.append(summary)
    requests = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(json.loads(request.content))
        payload = reply()
        payload["output"][-1]["content"][0]["text"] = texts[len(requests) - 1]
        return httpx.Response(200, json=payload)

    client = sdk(handler)
    settings = Settings(
        "synthetic-url",
        llm_provider="openai",
        openai_api_key="test-only",
        openai_model="test-gpt",
    )
    path = tmp_path / "log.jsonl"
    with (
        patch("er_agent.generation.factory.openai_transport.create_client", return_value=client),
        patch("er_agent.generation.factory.gemini.create_client") as gemini,
        patch(
            "er_agent.database.context_repository.load_context_snapshot",
            return_value=query_snapshot,
        ),
        patch(
            "er_agent.database.query_executor.execute_table",
            return_value=QueryTable(
                ("samples",),
                ({"samples": 4},),
            ),
        ) as execute,
    ):
        answer = run_query(QueryRequest("참가자 수"), settings, path)
    assert answer.kind == ResponseKind.ANSWER and answer.summary == "표본은 4건입니다."
    assert execute.call_count == 1
    gemini.assert_not_called()
    events = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    attempts = [event for event in events if event["event"] == "llm_attempt"]
    assert [event["phase"] for event in attempts] == (
        ["generate", "repair", "summary"] if repair else ["generate", "summary"]
    )
    assert all(event["provider"] == "openai" and event["model"] == "test-gpt" for event in events)
    assert requests[0]["text"]["format"]["schema"] == GENERATION_SCHEMA
    assert requests[-1]["text"]["format"]["schema"] == SUMMARY_SCHEMA
    assert client.is_closed()
