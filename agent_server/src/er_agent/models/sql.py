from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .limits import MAX_ROWS


@dataclass(frozen=True)
class QueryTable:
    columns: tuple[str, ...]
    rows: tuple[dict[str, Any], ...]

    def __post_init__(self) -> None:
        object.__setattr__(self, "columns", tuple(self.columns))
        object.__setattr__(self, "rows", tuple(dict(row) for row in self.rows))
        if len(set(self.columns)) != len(self.columns):
            raise ValueError("Result columns must be unique")
        if len(self.rows) > MAX_ROWS or any(set(row) != set(self.columns) for row in self.rows):
            raise ValueError("Result rows must match columns and row limit")


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
    table: QueryTable | None
    guarded_sql: str | None
    error: str | None
    repairable: bool
