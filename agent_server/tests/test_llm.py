from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest
from google.genai import errors, types

from er_agent.llm import GeminiCaller, LlmCallError, create_client
from er_agent.trial_log import TrialLog


def records(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]


def response(text: str = "SELECT 1", *, usage: bool = True) -> types.GenerateContentResponse:
    return types.GenerateContentResponse(
        candidates=[types.Candidate(content=types.Content(parts=[types.Part(text=text)]))],
        usage_metadata=(
            types.GenerateContentResponseUsageMetadata(
                prompt_token_count=10,
                candidates_token_count=5,
                total_token_count=17,
                thoughts_token_count=2,
            )
            if usage
            else None
        ),
    )


def api_error(code: int) -> errors.APIError:
    return errors.APIError(code, {"error": {"message": "SECRET response body", "code": code}})


def test_client_disables_hidden_retries() -> None:
    with patch("er_agent.llm.genai.Client") as factory:
        create_client("test-only")
    options = factory.call_args.kwargs["http_options"]
    assert options.retry_options.attempts == 1
    assert options.timeout == 30_000


def test_each_attempt_is_logged_with_separate_code_and_tokens(tmp_path: Path) -> None:
    trial = TrialLog(tmp_path / "log.jsonl", "질문", "test-model")
    client = MagicMock()
    client.models.generate_content.side_effect = [api_error(429), api_error(503), response()]
    with patch("er_agent.llm.time.sleep") as sleep:
        assert (
            GeminiCaller(client, "test-model", trial).generate("prompt", "generate") == "SELECT 1"
        )
    events = records(trial.path)
    assert [e["error_code"] for e in events] == [429, 503, None]
    assert [e["error_category"] for e in events] == ["rate_limited", "unavailable", None]
    assert [e["attempt"] for e in events] == [1, 2, 3]
    assert [e["retry_scheduled"] for e in events] == [True, True, False]
    assert {e["call_id"] for e in events} == {1}
    assert {e["log_id"] for e in events} == {trial.log_id}
    assert events[0]["tokens"]["total_token_count"] is None
    assert events[2]["tokens"]["total_token_count"] == 17
    assert events[2]["tokens"]["thoughts_token_count"] == 2
    assert trial.logical_calls == 1 and trial.physical_calls == 3
    assert [call.args[0] for call in sleep.call_args_list] == [1, 2]
    assert "SECRET" not in trial.path.read_text(encoding="utf-8")


@pytest.mark.parametrize("code", [408, 429, 500, 502, 503, 504])
def test_retries_stop_after_three_requests(tmp_path: Path, code: int) -> None:
    trial = TrialLog(tmp_path / "log.jsonl", "질문", "test-model")
    client = MagicMock()
    client.models.generate_content.side_effect = api_error(code)
    with patch("er_agent.llm.time.sleep"), pytest.raises(LlmCallError) as error:
        GeminiCaller(client, "test-model", trial).generate("prompt", "repair")
    assert error.value.code == code
    assert "SECRET" not in str(error.value)
    assert client.models.generate_content.call_count == 3
    assert len(records(trial.path)) == 3
    assert records(trial.path)[-1]["retry_scheduled"] is False


@pytest.mark.parametrize("code", [400, 401, 403, 404])
def test_permanent_errors_do_not_retry(tmp_path: Path, code: int) -> None:
    trial = TrialLog(tmp_path / "log.jsonl", "질문", "test-model")
    client = MagicMock()
    client.models.generate_content.side_effect = api_error(code)
    with patch("er_agent.llm.time.sleep") as sleep, pytest.raises(LlmCallError):
        GeminiCaller(client, "test-model", trial).generate("prompt", "generate")
    assert len(records(trial.path)) == 1
    sleep.assert_not_called()


@pytest.mark.parametrize("result", [response(""), RuntimeError("SECRET URL and key")])
def test_empty_or_sdk_failure_is_logged_without_raw_message(tmp_path: Path, result: object) -> None:
    trial = TrialLog(tmp_path / "log.jsonl", "질문", "test-model")
    client = MagicMock()
    client.models.generate_content.side_effect = [result]
    with pytest.raises(LlmCallError):
        GeminiCaller(client, "test-model", trial).generate("prompt", "generate")
    assert len(records(trial.path)) == 1
    assert "SECRET" not in trial.path.read_text(encoding="utf-8")


def test_missing_usage_is_null_and_repair_is_another_logical_call(tmp_path: Path) -> None:
    trial = TrialLog(tmp_path / "log.jsonl", "질문", "test-model")
    client = MagicMock()
    client.models.generate_content.return_value = response(usage=False)
    caller = GeminiCaller(client, "test-model", trial)
    caller.generate("original", "generate", {"type": "object"})
    caller.generate("fixed", "repair")
    events = records(trial.path)
    assert [event["call_id"] for event in events] == [1, 2]
    assert events[0]["tokens"]["total_token_count"] is None
    assert client.models.generate_content.call_args_list[0].kwargs[
        "config"
    ].response_json_schema == {"type": "object"}


def test_log_write_failure_stops_further_calls(tmp_path: Path) -> None:
    trial = TrialLog(tmp_path / "log.jsonl", "질문", "test-model")
    client = MagicMock()
    client.models.generate_content.side_effect = api_error(503)
    with patch.object(trial, "append", side_effect=OSError), pytest.raises(OSError):
        GeminiCaller(client, "test-model", trial).generate("prompt", "generate")
    assert client.models.generate_content.call_count == 1


def test_unserializable_record_does_not_append_partial_line(tmp_path: Path) -> None:
    trial = TrialLog(tmp_path / "nested/log.jsonl", "질문", "test-model")
    trial.append("test", value=1)
    with pytest.raises(TypeError):
        trial.append("test", value=object())
    assert len(records(trial.path)) == 1
