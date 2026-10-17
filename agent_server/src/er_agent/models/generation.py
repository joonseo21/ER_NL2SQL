from __future__ import annotations

from dataclasses import dataclass, field

from .context import QueryIntent
from .evidence import SampleColumn

TOKEN_FIELDS = (
    "prompt_token_count",
    "candidates_token_count",
    "total_token_count",
    "thoughts_token_count",
    "cached_content_token_count",
)


@dataclass(frozen=True)
class ModelSettings:
    """Selected provider configuration; SDK details stay in generation adapters."""

    provider: str
    model: str
    api_key: str = field(repr=False)
    timeout_seconds: int
    reasoning_level: str


@dataclass(frozen=True)
class Generation:
    intent: QueryIntent
    sql: str | None
    sample: SampleColumn | None = None


@dataclass(frozen=True)
class ModelReply:
    text: str | None
    tokens: dict[str, int | None]
    error_category: str | None = None
