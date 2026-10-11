"""Official-name lookup. Similar names are suggestions, never automatic replacements."""

from __future__ import annotations

import unicodedata
from dataclasses import dataclass
from difflib import SequenceMatcher

from .contracts import NameCandidate, NameCategory, NameClarification


@dataclass(frozen=True)
class NameTerm:
    text: str
    category: NameCategory | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.text, str) or not self.text.strip():
            raise ValueError("Name term must be nonempty text")
        if self.category is not None and not isinstance(self.category, NameCategory):
            raise ValueError("Name category must be a NameCategory")


def _normalized(text: str) -> str:
    # Boundary whitespace and Unicode representation only; no alias expansion.
    return unicodedata.normalize("NFC", text.strip())


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

    def resolve(self, term: NameTerm) -> ResolvedName | NameClarification:
        entries = tuple(
            entry
            for entry in self.entries
            if term.category is None or entry.category == term.category
        )
        exact = tuple(
            entry for entry in entries if _normalized(entry.name) == _normalized(term.text)
        )
        if len(exact) == 1:
            return ResolvedName(term, exact[0])
        if exact:
            # Retain every exact homonym; do not arbitrarily select the lowest code.
            return NameClarification(term.text, tuple(sorted(exact, key=_candidate_key)))
        ranked = sorted(
            (
                (
                    SequenceMatcher(None, _normalized(term.text), _normalized(entry.name)).ratio(),
                    entry,
                )
                for entry in entries
            ),
            key=lambda item: (-item[0], *_candidate_key(item[1])),
        )
        return NameClarification(
            term.text, tuple(entry for score, entry in ranked[:5] if score >= 0.5)
        )

    def character(self, code: int) -> NameCandidate | None:
        return next(
            (
                entry
                for entry in self.entries
                if entry.category == NameCategory.CHARACTER and entry.code == code
            ),
            None,
        )


def _candidate_key(entry: NameCandidate) -> tuple[str, str, int]:
    return entry.category.value, entry.name, entry.code
