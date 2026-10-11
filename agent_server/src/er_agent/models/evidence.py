from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum

from .validation import _integer, _text


class SampleUnit(StrEnum):
    PARTICIPANTS = "participant_appearances"
    GAMES = "games"
    TEAMS = "teams"


@dataclass(frozen=True)
class SampleColumn:
    column: str
    unit: SampleUnit

    def __post_init__(self) -> None:
        _text(self.column, "sample.column")
        if not isinstance(self.unit, SampleUnit):
            raise ValueError("sample.unit must be a SampleUnit")


@dataclass(frozen=True)
class GroupEvidence:
    row_index: int
    sample_size: int | None = None
    sample_unit: SampleUnit | None = None
    warnings: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        _integer(self.row_index, "row_index")
        if self.sample_size is not None:
            _integer(self.sample_size, "sample_size")
            if not isinstance(self.sample_unit, SampleUnit):
                raise ValueError("Known samples require a unit")
        object.__setattr__(self, "warnings", tuple(self.warnings))
        for warning in self.warnings:
            _text(warning, "warning")
