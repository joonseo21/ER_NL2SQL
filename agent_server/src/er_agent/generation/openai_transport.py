"""One OpenAI Responses request, normalized into the shared transport contract."""

from __future__ import annotations

from typing import Any

from openai import APIStatusError, APITimeoutError, OpenAI

from ..models.errors import LlmCallError
from ..models.generation import ModelReply


def create_client(api_key: str, timeout_seconds: int = 30) -> OpenAI:
    return OpenAI(
        api_key=api_key,
        base_url="https://api.openai.com/v1",
        timeout=timeout_seconds,
        max_retries=0,
    )


class OpenAITransport:
    def __init__(self, client: OpenAI, model: str, reasoning_effort: str = "") -> None:
        self.client = client
        self.model = model
        self.reasoning_effort = reasoning_effort

    def complete(self, prompt: str, schema: dict[str, Any] | None = None) -> ModelReply:
        options: dict[str, Any] = {"model": self.model, "input": prompt, "store": False}
        if schema is not None:
            options["text"] = {
                "format": {
                    "type": "json_schema",
                    "name": "agent_reply",
                    "strict": True,
                    "schema": schema,
                }
            }
        if self.reasoning_effort:
            options["reasoning"] = {"effort": self.reasoning_effort}
        try:
            response = self.client.responses.create(**options)
            usage = response.usage
            output = getattr(usage, "output_tokens", None)
            thoughts = getattr(
                getattr(usage, "output_tokens_details", None), "reasoning_tokens", None
            )
            tokens = {
                "prompt_token_count": getattr(usage, "input_tokens", None),
                # OpenAI output_tokens includes reasoning; Gemini candidates excludes it.
                "candidates_token_count": (
                    output - thoughts if output is not None and thoughts is not None else None
                ),
                "total_token_count": getattr(usage, "total_tokens", None),
                "thoughts_token_count": thoughts,
                "cached_content_token_count": getattr(
                    getattr(usage, "input_tokens_details", None), "cached_tokens", None
                ),
            }
            failure = None
            if response.status != "completed":
                failure = "incomplete_response"
            elif any(
                getattr(part, "type", None) == "refusal"
                for item in response.output
                for part in (getattr(item, "content", None) or ())
            ):
                failure = "model_refusal"
            return ModelReply(response.output_text, tokens, failure)
        except APITimeoutError:
            raise LlmCallError("timeout", 408) from None
        except APIStatusError as exception:
            code = exception.status_code
            category = {429: "rate_limited", 503: "unavailable"}.get(code, "api_error")
            raise LlmCallError(category, code) from None
        except Exception:
            raise LlmCallError("transport_or_sdk_error") from None
