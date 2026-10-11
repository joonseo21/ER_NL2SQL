"""Render only validated result-cell references; never model-supplied numbers."""

from __future__ import annotations

from string import Formatter

from ..models.errors import SummaryFormatError
from ..models.query import QueryResponse
from ..models.serialization import json_value
from ..models.summary import SummaryDraft, SummarySentence


def _render(sentence: SummarySentence, response: QueryResponse) -> str:
    pieces = []
    used = set()
    try:
        for literal, field, spec, conversion in Formatter().parse(sentence.template):
            if any(character.isnumeric() or character in "\r\n{}" for character in literal):
                raise SummaryFormatError
            pieces.append(literal)
            if field is None:
                continue
            if spec or conversion or not field.isascii() or not field.isdecimal():
                raise SummaryFormatError
            index = int(field)
            cell = sentence.cells[index]
            if cell.column not in response.columns:
                raise SummaryFormatError
            value = json_value(response.rows[cell.row][cell.column])
            if isinstance(value, (dict, list)):
                raise SummaryFormatError
            pieces.append("자료 없음" if value is None else str(value))
            used.add(index)
        if used != set(range(len(sentence.cells))):
            raise SummaryFormatError
    except (ValueError, IndexError, KeyError, TypeError):
        raise SummaryFormatError from None
    return "".join(pieces)


def render_summary(draft: SummaryDraft, response: QueryResponse) -> str:
    if not isinstance(draft, SummaryDraft):
        raise SummaryFormatError
    summary = " ".join(_render(sentence, response) for sentence in draft.sentences)
    if not summary.strip() or len(summary) > 2000:
        raise SummaryFormatError
    return summary
