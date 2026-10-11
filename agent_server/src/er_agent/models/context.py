from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from enum import StrEnum

from .names import NameCatalog, NameCategory, NameTerm, ResolvedName
from .query import AppliedConditions, DataEvidence, PatchFilter, PatchVersion, QueryConditions


class UnsupportedReason(StrEnum):
    PERSONAL_HISTORY = "personal_history"
    PREDICTION = "prediction"
    UNRELATED = "unrelated"
    UNAVAILABLE_DATA = "unavailable_data"


@dataclass(frozen=True)
class TimeRange:
    start: datetime
    end: datetime

    def __post_init__(self) -> None:
        for value in (self.start, self.end):
            if not isinstance(value, datetime) or value.utcoffset() is None:
                raise ValueError("Period boundaries must be timezone-aware datetimes")
        if self.start >= self.end:
            raise ValueError("Period requires start < end (end is exclusive)")


@dataclass(frozen=True)
class TierSelection:
    tier: str | None = None
    division: int | None = None

    def __post_init__(self) -> None:
        QueryConditions(tier=self.tier, tier_division=self.division)


@dataclass(frozen=True)
class QueryIntent:
    names: tuple[NameTerm, ...] = ()
    patch: PatchFilter | None = None
    tier: TierSelection | None = None
    character: NameTerm | None = None
    all_characters: bool = False
    period: TimeRange | None = None
    unsupported_reason: UnsupportedReason | None = None

    def __post_init__(self) -> None:
        names = tuple(self.names)
        if any(not isinstance(term, NameTerm) for term in names):
            raise ValueError("Intent names must contain NameTerm values")
        object.__setattr__(self, "names", names)
        for value, expected in (
            (self.patch, PatchFilter),
            (self.tier, TierSelection),
            (self.character, NameTerm),
            (self.period, TimeRange),
            (self.unsupported_reason, UnsupportedReason),
        ):
            if value is not None and not isinstance(value, expected):
                raise ValueError("Invalid extracted intent type")
        if type(self.all_characters) is not bool or (self.character and self.all_characters):
            raise ValueError("Character override must be a name or all characters")
        if self.character and self.character.category not in {None, NameCategory.CHARACTER}:
            raise ValueError("Character override must refer to a character")


@dataclass(frozen=True)
class ScopeBucket:
    patch: PatchVersion
    tier: str | None
    division: int | None
    appearances: int
    first_match: datetime | None
    last_match: datetime | None
    data_as_of: datetime


@dataclass(frozen=True)
class ContextSnapshot:
    season_id: int
    catalog: NameCatalog
    scopes: tuple[ScopeBucket, ...]
    tiers: tuple[TierSelection, ...]


@dataclass(frozen=True)
class ResolvedContext:
    applied_conditions: AppliedConditions
    names: tuple[ResolvedName, ...]
    period: TimeRange | None
    evidence: DataEvidence
    warnings: tuple[str, ...] = ()
