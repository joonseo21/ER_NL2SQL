"""Exactly one SDK request; convert SDK replies and errors to neutral values."""

from __future__ import annotations

from typing import Any

from google import genai
from google.genai import errors, types

from ..models.errors import LlmCallError
from ..models.generation import TOKEN_FIELDS, ModelReply


def create_client(api_key: str, timeout_seconds: int = 30) -> genai.Client:
    # The retry wrapper must observe every physical request.
    return genai.Client(
        api_key=api_key,
        http_options=types.HttpOptions(
            timeout=timeout_seconds * 1000, retry_options=types.HttpRetryOptions(attempts=1)
        ),
    )


def _category(code: int) -> str:
    if code == 429:
        return "rate_limited"
    if code == 503:
        return "unavailable"
    return "api_error"


class GeminiTransport:
    def __init__(self, client: genai.Client, model: str, thinking_level: str = "") -> None:
        self.client = client
        self.model = model
        # Empty keeps the model default; only an explicit level is sent.
        self.thinking_level = thinking_level

    def complete(self, prompt: str, schema: dict[str, Any] | None = None) -> ModelReply:
        config = types.GenerateContentConfig(
            response_mime_type="application/json" if schema is not None else "text/plain",
            response_json_schema=schema,
            candidate_count=1,
            thinking_config=(
                types.ThinkingConfig(thinking_level=self.thinking_level)
                if self.thinking_level
                else None
            ),
        )
        try:
            response = self.client.models.generate_content(
                model=self.model, contents=prompt, config=config
            )
            return ModelReply(
                response.text,
                {name: getattr(response.usage_metadata, name, None) for name in TOKEN_FIELDS},
            )
        except errors.APIError as exception:
            raise LlmCallError(_category(exception.code), exception.code) from None
        except Exception:
            raise LlmCallError("transport_or_sdk_error") from None
