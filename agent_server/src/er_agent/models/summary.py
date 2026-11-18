from __future__ import annotations

from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal, localcontext
from typing import Any

from .serialization import json_value
from .validation import _integer, _text


def summary_cell_value(value: Any) -> Any:
    """Format typed numeric cells for prose, preserving text codes and table data."""
    if isinstance(value, Decimal) or type(value) is float:
        number = Decimal(str(value))
        if not number.is_finite():
            raise ValueError("Summary numbers must be finite")
        with localcontext() as context:
            context.rounding = ROUND_HALF_UP
            rendered = format(number, ".2f")
        return "0.00" if Decimal(rendered) == 0 else rendered
    return json_value(value)


@dataclass(frozen=True)
class CellReference:
    row: int
    column: str

    def __post_init__(self) -> None:
        _integer(self.row, "cell.row")
        _text(self.column, "cell.column")


@dataclass(frozen=True)
class SummarySentence:
    template: str
    cells: tuple[CellReference, ...]

    def __post_init__(self) -> None:
        _text(self.template, "summary.template")
        object.__setattr__(self, "cells", tuple(self.cells))
        if len(self.template) > 500 or not 1 <= len(self.cells) <= 20:
            raise ValueError("Summary sentence is too large or has no references")
        if any(not isinstance(cell, CellReference) for cell in self.cells):
            raise ValueError("Summary cells must be references")


@dataclass(frozen=True)
class SummaryDraft:
    sentences: tuple[SummarySentence, ...]

    def __post_init__(self) -> None:
        object.__setattr__(self, "sentences", tuple(self.sentences))
        if not 1 <= len(self.sentences) <= 2:
            raise ValueError("Summary requires one or two sentences")
        if any(not isinstance(sentence, SummarySentence) for sentence in self.sentences):
            raise ValueError("Summary sentences must be typed")
