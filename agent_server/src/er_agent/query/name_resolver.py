from __future__ import annotations

import unicodedata
from difflib import SequenceMatcher

from ..models.names import (
    NameCandidate,
    NameCatalog,
    NameCategory,
    NameClarification,
    NameTerm,
    ResolvedName,
)


def _normalized(text: str) -> str:
    # Boundary whitespace and Unicode representation only; no alias expansion.
    return unicodedata.normalize("NFC", text.strip())


def _candidate_key(entry: NameCandidate) -> tuple[str, str, int]:
    return entry.category.value, entry.name, entry.code


class NameResolver:
    def __init__(self, catalog: NameCatalog) -> None:
        self.catalog = catalog

    def resolve(self, term: NameTerm) -> ResolvedName | NameClarification:
        entries = tuple(
            entry
            for entry in self.catalog.entries
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
                for entry in self.catalog.entries
                if entry.category == NameCategory.CHARACTER and entry.code == code
            ),
            None,
        )
