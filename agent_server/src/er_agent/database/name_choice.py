"""Pick one code per official name, so a name shared by several codes needs no clarification.

Users care about the Korean name, not the code (spec D-20, which replaces the clarification of
D-17). Only the preferred code is kept in the name catalog.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass

COBALT_KIND = "Cobalt"
CORE_KIND = "Core"


@dataclass(frozen=True)
class NameRow:
    code: int
    name: str
    # Used in the configured season's ranked squad matches; None when unknown (older database,
    # not aggregated yet, or another season).
    used: bool | None = None
    # trait_type for traits; None for other categories.
    kind: str | None = None


def _preference(row: NameRow) -> tuple[bool, bool, bool, int]:
    # False sorts first. Cobalt traits belong to another game mode and are chosen last, then a
    # code seen in matches wins, then a Core trait, then the lowest code (deterministic).
    return (row.kind == COBALT_KIND, row.used is not True, row.kind != CORE_KIND, row.code)


def one_code_per_name(rows: Iterable[NameRow]) -> list[NameRow]:
    """Keep the preferred row for each name; names compare by their stripped text.

    The database layer may not import unicodedata (see test_architecture), so a name written in a
    different Unicode form stays a separate entry and the resolver asks about it as before.
    """
    best: dict[str, NameRow] = {}
    for row in rows:
        name = row.name.strip()
        current = best.get(name)
        if current is None or _preference(row) < _preference(current):
            best[name] = row
    return sorted(best.values(), key=lambda row: row.code)
