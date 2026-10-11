"""Retry one logical model call, recording every physical request immediately."""

from __future__ import annotations

import time
from typing import Any

from ..models.errors import LlmCallError
from ..models.generation import TOKEN_FIELDS
from ..query.ports import QueryRecorder
from .ports import ModelTransport

RETRY_CODES = frozenset({408, 429, 500, 502, 503, 504})
MAX_ATTEMPTS = 3


class RetryingCaller:
    def __init__(self, transport: ModelTransport, recorder: QueryRecorder) -> None:
        self.transport = transport
        self.recorder = recorder

    def generate(self, prompt: str, phase: str, schema: dict[str, Any] | None = None) -> str:
        call_id = self.recorder.begin_call()
        for attempt in range(1, MAX_ATTEMPTS + 1):
            failure = None
            text = None
            tokens = dict.fromkeys(TOKEN_FIELDS)
            started = time.perf_counter()
            self.recorder.begin_attempt()
            try:
                reply = self.transport.complete(prompt, schema)
                text = reply.text
                tokens = reply.tokens
                if not text or not text.strip():
                    failure = LlmCallError("empty_response")
            except LlmCallError as exception:
                failure = exception
            retry = failure is not None and failure.code in RETRY_CODES and attempt < MAX_ATTEMPTS
            self.recorder.append(
                "llm_attempt",
                phase=phase,
                call_id=call_id,
                attempt=attempt,
                elapsed_ms=int((time.perf_counter() - started) * 1000),
                succeeded=failure is None,
                error_code=failure.code if failure else None,
                error_category=failure.category if failure else None,
                retry_scheduled=retry,
                tokens=tokens,
            )
            if failure is None:
                return text
            if not retry:
                raise failure from None
            time.sleep(2 ** (attempt - 1))
        raise RuntimeError("Unreachable Gemini retry state")
