from __future__ import annotations

from dataclasses import dataclass

from .validation import _integer, _text


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
