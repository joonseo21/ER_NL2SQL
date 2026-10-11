from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class GuardedSql:
    sql: str
    limit_added: bool


@dataclass(frozen=True)
class ScopedSql:
    sql: str
    parameters: dict[str, Any]


@dataclass(frozen=True)
class SqlAttempt:
    rows: list[dict[str, Any]] | None
    guarded_sql: str | None
    error: str | None
    repairable: bool
