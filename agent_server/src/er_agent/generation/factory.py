"""Own provider clients; callers depend only on the ModelTransport port."""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager

from ..models.generation import ModelSettings
from . import gemini, openai_transport
from .ports import ModelTransport


@contextmanager
def model_transport(settings: ModelSettings) -> Iterator[ModelTransport]:
    if settings.provider == "gemini":
        with gemini.create_client(settings.api_key, settings.timeout_seconds) as client:
            yield gemini.GeminiTransport(client, settings.model, settings.reasoning_level)
    elif settings.provider == "openai":
        with openai_transport.create_client(settings.api_key, settings.timeout_seconds) as client:
            yield openai_transport.OpenAITransport(client, settings.model, settings.reasoning_level)
    else:
        raise ValueError("Unsupported LLM provider")
