from __future__ import annotations

from dataclasses import dataclass

from .context import QueryIntent

TOKEN_FIELDS = (
    "prompt_token_count",
    "candidates_token_count",
    "total_token_count",
    "thoughts_token_count",
    "cached_content_token_count",
)


@dataclass(frozen=True)
class Generation:
    intent: QueryIntent
    sql: str | None


@dataclass(frozen=True)
class ModelReply:
    text: str | None
    tokens: dict[str, int | None]
