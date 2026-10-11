"""Bounded Gemini retries; one log event for each physical SDK request."""

from __future__ import annotations

import time
from typing import Any

from google import genai
from google.genai import errors, types

from .trial_log import TrialLog

RETRY_CODES = frozenset({408, 429, 500, 502, 503, 504})
MAX_ATTEMPTS = 3


class LlmCallError(RuntimeError):
    def __init__(self, category: str, code: int | None = None) -> None:
        self.category = category
        self.code = code
        super().__init__(f"Gemini {category}" + (f" (HTTP {code})" if code is not None else ""))


def create_client(api_key: str) -> genai.Client:
    # Disable SDK retries so every request is visible to our attempt logger.
    return genai.Client(
        api_key=api_key,
        http_options=types.HttpOptions(
            timeout=30_000, retry_options=types.HttpRetryOptions(attempts=1)
        ),
    )


def _category(code: int) -> str:
    if code == 429:
        return "rate_limited"
    if code == 503:
        return "unavailable"
    return "api_error"


def _tokens(response: types.GenerateContentResponse | None) -> dict[str, int | None]:
    usage = response.usage_metadata if response is not None else None
    return {
        name: getattr(usage, name, None)
        for name in (
            "prompt_token_count",
            "candidates_token_count",
            "total_token_count",
            "thoughts_token_count",
            "cached_content_token_count",
        )
    }


class GeminiCaller:
    def __init__(self, client: genai.Client, model: str, trial: TrialLog) -> None:
        self.client = client
        self.model = model
        self.trial = trial

    def generate(self, prompt: str, phase: str, schema: dict[str, Any] | None = None) -> str:
        self.trial.logical_calls += 1
        call_id = self.trial.logical_calls
        config = types.GenerateContentConfig(
            response_mime_type="application/json" if schema is not None else "text/plain",
            response_json_schema=schema,
            candidate_count=1,
        )
        for attempt in range(1, MAX_ATTEMPTS + 1):
            failure: LlmCallError | None = None
            response: types.GenerateContentResponse | None = None
            started = time.perf_counter()
            self.trial.physical_calls += 1
            try:
                response = self.client.models.generate_content(
                    model=self.model, contents=prompt, config=config
                )
                text = response.text
                if not text or not text.strip():
                    failure = LlmCallError("empty_response")
            except errors.APIError as exception:
                failure = LlmCallError(_category(exception.code), exception.code)
            except Exception:
                # SDK exceptions can contain URLs, credentials or a response body.
                failure = LlmCallError("transport_or_sdk_error")
            retry = failure is not None and failure.code in RETRY_CODES and attempt < MAX_ATTEMPTS
            self.trial.append(
                "llm_attempt",
                phase=phase,
                call_id=call_id,
                attempt=attempt,
                elapsed_ms=int((time.perf_counter() - started) * 1000),
                succeeded=failure is None,
                error_code=failure.code if failure else None,
                error_category=failure.category if failure else None,
                retry_scheduled=retry,
                tokens=_tokens(response),
            )
            if failure is None:
                return text
            if not retry:
                raise failure from None
            time.sleep(2 ** (attempt - 1))
        raise RuntimeError("Unreachable Gemini retry state")
