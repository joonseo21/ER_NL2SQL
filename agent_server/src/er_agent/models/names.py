from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum

from .validation import _integer, _text


class NameCategory(StrEnum):
    CHARACTER = "character"
    WEAPON = "weapon"
    ITEM = "item"
    TRAIT = "trait"
    TACTICAL = "tactical"


@dataclass(frozen=True)
class NameCandidate:
    category: NameCategory
    code: int
    name: str

    def __post_init__(self) -> None:
        if not isinstance(self.category, NameCategory):
            raise ValueError("candidate.category must be a NameCategory")
        _integer(self.code, "candidate.code", 1)
        _text(self.name, "candidate.name")


@dataclass(frozen=True)
class NameClarification:
    term: str
    candidates: tuple[NameCandidate, ...] = ()

    def __post_init__(self) -> None:
        _text(self.term, "clarification.term")
        if any(not isinstance(candidate, NameCandidate) for candidate in self.candidates):
            raise ValueError("clarification.candidates must contain NameCandidate values")
        object.__setattr__(self, "candidates", tuple(self.candidates))


@dataclass(frozen=True)
class NameTerm:
    text: str
    category: NameCategory | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.text, str) or not self.text.strip():
            raise ValueError("Name term must be nonempty text")
        if self.category is not None and not isinstance(self.category, NameCategory):
            raise ValueError("Name category must be a NameCategory")


@dataclass(frozen=True)
class ResolvedName:
    term: NameTerm
    candidate: NameCandidate


@dataclass(frozen=True)
class NameCatalog:
    entries: tuple[NameCandidate, ...]

    def __post_init__(self) -> None:
        entries = tuple(self.entries)
        if any(not isinstance(entry, NameCandidate) for entry in entries):
            raise ValueError("Catalog must contain NameCandidate values")
        identities = [(entry.category, entry.code) for entry in entries]
        if len(set(identities)) != len(identities):
            raise ValueError("Catalog codes must be unique within each category")
        object.__setattr__(self, "entries", entries)
