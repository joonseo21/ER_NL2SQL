from __future__ import annotations

from typing import Any, Protocol

from ..models.generation import ModelReply


class ModelTransport(Protocol):
    def complete(self, prompt: str, schema: dict[str, Any] | None = None) -> ModelReply: ...


class ModelCaller(Protocol):
    def generate(self, prompt: str, phase: str, schema: dict[str, Any] | None = None) -> str: ...
