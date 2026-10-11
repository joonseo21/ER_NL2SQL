"""Small contracts used by the query flow; no concrete driver or SDK types."""

from __future__ import annotations

from typing import Any, Protocol

from ..models.context import ContextSnapshot, ResolvedContext
from ..models.generation import Generation
from ..models.query import QueryRequest


class QuerySettings(Protocol):
    @property
    def season_id(self) -> int: ...

    def require_database(self) -> None: ...

    def require_gemini(self) -> None: ...


class ContextRepository(Protocol):
    def load(self, season_id: int) -> ContextSnapshot: ...


class QueryExecutor(Protocol):
    def execute(self, sql: str, parameters: dict[str, Any]) -> list[dict[str, Any]]: ...


class SqlGenerator(Protocol):
    def generate(self, request: QueryRequest, snapshot: ContextSnapshot) -> Generation: ...

    def repair(
        self, request: QueryRequest, context: ResolvedContext, sql: str, error: str
    ) -> str: ...


class QueryRecorder(Protocol):
    @property
    def log_id(self) -> str: ...

    @property
    def elapsed_ms(self) -> int: ...

    def begin_call(self) -> int: ...

    def begin_attempt(self) -> None: ...

    def append(self, event: str, **values: Any) -> None: ...

    def finish(self, **values: Any) -> None: ...
