from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from enum import StrEnum
from typing import Any

from .limits import MAX_ROWS
from .names import NameClarification
from .serialization import json_value
from .validation import _integer, _text


class PatchMode(StrEnum):
    LATEST = "latest"
    EXACT = "exact"
    ALL = "all"


@dataclass(frozen=True)
class PatchVersion:
    major: int
    minor: int

    def __post_init__(self) -> None:
        _integer(self.major, "patch.major")
        _integer(self.minor, "patch.minor")


@dataclass(frozen=True)
class PatchFilter:
    mode: PatchMode = PatchMode.LATEST
    versions: tuple[PatchVersion, ...] = ()

    def __post_init__(self) -> None:
        if not isinstance(self.mode, PatchMode):
            raise ValueError("patch.mode must be a PatchMode")
        versions = tuple(self.versions)
        if any(not isinstance(version, PatchVersion) for version in versions):
            raise ValueError("patch.versions must contain PatchVersion values")
        if len(set(versions)) != len(versions):
            raise ValueError("patch.versions must not contain duplicates")
        if (self.mode == PatchMode.EXACT) != bool(versions):
            raise ValueError("Only exact patches require explicit versions")
        object.__setattr__(self, "versions", versions)


@dataclass(frozen=True)
class QueryConditions:
    patch: PatchFilter = field(default_factory=PatchFilter)
    tier: str | None = None
    character_code: int | None = None
    tier_division: int | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.patch, PatchFilter):
            raise ValueError("conditions.patch must be a PatchFilter")
        if self.tier is not None:
            _text(self.tier, "conditions.tier")
        if self.character_code is not None:
            _integer(self.character_code, "conditions.character_code", 1)
        if self.tier_division is not None:
            _integer(self.tier_division, "conditions.tier_division")
            if self.tier is None:
                raise ValueError("Tier division requires a tier")


@dataclass(frozen=True)
class QueryRequest:
    question: str
    conditions: QueryConditions = field(default_factory=QueryConditions)

    def __post_init__(self) -> None:
        _text(self.question, "question")
        if not isinstance(self.conditions, QueryConditions):
            raise ValueError("conditions must be QueryConditions")


@dataclass(frozen=True)
class AppliedConditions:
    season_id: int
    conditions: QueryConditions

    def __post_init__(self) -> None:
        _integer(self.season_id, "season_id", 1)
        if not isinstance(self.conditions, QueryConditions):
            raise ValueError("conditions must be QueryConditions")
        if self.conditions.patch.mode == PatchMode.LATEST:
            raise ValueError(
                "Resolve latest to actual patch versions before reporting applied conditions"
            )


class ResponseKind(StrEnum):
    ANSWER = "answer"
    NOTICE = "notice"
    CLARIFICATION = "clarification"


@dataclass(frozen=True)
class DataEvidence:
    sample_size: int | None = None
    sample_unit: str | None = None
    data_as_of: datetime | None = None

    def __post_init__(self) -> None:
        if self.sample_unit is not None:
            _text(self.sample_unit, "sample_unit")
        if self.sample_size is not None:
            _integer(self.sample_size, "sample_size")
            _text(self.sample_unit, "sample_unit")
        if self.data_as_of is not None and (
            not isinstance(self.data_as_of, datetime) or self.data_as_of.utcoffset() is None
        ):
            raise ValueError("data_as_of must be a timezone-aware datetime")


@dataclass(frozen=True)
class QueryResponse:
    kind: ResponseKind
    question: str
    log_id: str
    elapsed_ms: int
    applied_conditions: AppliedConditions | None = None
    sql: str | None = None
    columns: tuple[str, ...] = ()
    rows: tuple[dict[str, Any], ...] = ()
    summary: str | None = None
    message: str | None = None
    examples: tuple[str, ...] = ()
    clarifications: tuple[NameClarification, ...] = ()
    evidence: DataEvidence | None = None
    warnings: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if not isinstance(self.kind, ResponseKind):
            raise ValueError("kind must be a ResponseKind")
        for name in ("columns", "examples", "clarifications", "warnings"):
            object.__setattr__(self, name, tuple(getattr(self, name)))
        if any(not isinstance(row, dict) for row in self.rows):
            raise ValueError("rows must contain column/value dictionaries")
        object.__setattr__(self, "rows", tuple(dict(row) for row in self.rows))
        _text(self.question, "question")
        _text(self.log_id, "log_id")
        _integer(self.elapsed_ms, "elapsed_ms")
        if self.applied_conditions is not None and not isinstance(
            self.applied_conditions, AppliedConditions
        ):
            raise ValueError("applied_conditions must be AppliedConditions")
        if self.evidence is not None and not isinstance(self.evidence, DataEvidence):
            raise ValueError("evidence must be DataEvidence")
        for value in self.columns + self.examples + self.warnings:
            _text(value, "response text")
        if len(set(self.columns)) != len(self.columns):
            raise ValueError("columns must be unique")
        if len(self.rows) > MAX_ROWS:
            raise ValueError(f"rows must not exceed {MAX_ROWS}")
        if any(set(row) != set(self.columns) for row in self.rows):
            raise ValueError("row keys must match columns")
        if any(not isinstance(item, NameClarification) for item in self.clarifications):
            raise ValueError("clarifications must contain NameClarification values")
        if self.summary is not None:
            _text(self.summary, "summary")
        if self.message is not None:
            _text(self.message, "message")
        if self.kind == ResponseKind.ANSWER:
            _text(self.sql, "answer.sql")
            if self.applied_conditions is None:
                raise ValueError("answer requires actual applied conditions")
            if self.clarifications:
                raise ValueError("answer must not contain unresolved names")
        else:
            _text(self.message, "message")
            if self.sql is not None or self.columns or self.rows or self.summary is not None:
                raise ValueError("Non-answer responses must not contain SQL, results or a summary")
            if (self.kind == ResponseKind.CLARIFICATION) != bool(self.clarifications):
                raise ValueError("Only clarification responses require unresolved names")

    def to_dict(self) -> dict[str, Any]:
        """JSON-compatible payload. Decimal strings preserve database precision."""
        return json_value(self)
