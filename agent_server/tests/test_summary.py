from __future__ import annotations

import json
from datetime import date
from decimal import Decimal
from typing import Any

import pytest

from er_agent.generation.summary import build_summary_prompt, parse_summary
from er_agent.models.errors import SummaryFormatError
from er_agent.models.query import (
    AppliedConditions,
    PatchFilter,
    PatchMode,
    PatchVersion,
    QueryConditions,
    QueryResponse,
    ResponseKind,
)
from er_agent.query.summary import render_summary


def answer(value: Any = Decimal("12.345678901234567890")) -> QueryResponse:
    return QueryResponse(
        ResponseKind.ANSWER,
        "PRIVATE question",
        "log",
        0,
        applied_conditions=AppliedConditions(
            41, QueryConditions(patch=PatchFilter(PatchMode.EXACT, (PatchVersion(5, 0),)))
        ),
        sql="PRIVATE SQL",
        columns=("rate",),
        rows=({"rate": value},),
    )


def draft(template: str = "비율은 {0}%입니다.", row: Any = 0, column: Any = "rate") -> str:
    return json.dumps(
        {"sentences": [{"template": template, "cells": [{"row": row, "column": column}]}]},
        ensure_ascii=False,
    )


@pytest.mark.parametrize(
    "value,expected",
    [
        (Decimal("12.345678901234567890"), "12.35"),
        (Decimal("1.005"), "1.01"),
        (Decimal("-1.005"), "-1.01"),
        (Decimal("-0.004"), "0.00"),
        (Decimal("9999999999999999999999999999.995"), "10000000000000000000000000000.00"),
        (1.6362794672744107, "1.64"),
        (Decimal("2.4398126980623394"), "2.44"),
        (Decimal("0.8035332307879287"), "0.80"),
        (1392, "1392"),
        ("1.2345678901234567", "1.2345678901234567"),
        ("v12.5.0", "v12.5.0"),
        ("00123", "00123"),
        (None, "자료 없음"),
        (date(2026, 10, 8), "2026-10-08"),
        ("합성 이름", "합성 이름"),
    ],
)
def test_summary_formats_numeric_cells_and_preserves_other_values(
    value: Any, expected: str
) -> None:
    assert (
        render_summary(parse_summary(draft("결과는 {0}입니다.")), answer(value))
        == f"결과는 {expected}입니다."
    )


@pytest.mark.parametrize(
    "template",
    [
        "비율은 99%입니다.",
        "비율은 {1}입니다.",
        "비율은 {0:.2f}입니다.",
        "비율은 {0!r}입니다.",
        "비율은 {0.real}입니다.",
        "비율은 {}입니다.",
        "비율은 {0}입니다.\n",
        "비율은 {0}입니다. {{99}}",
        "비율은 {0입니다.",
    ],
)
def test_invalid_template_cannot_introduce_or_transform_values(template: str) -> None:
    with pytest.raises(SummaryFormatError):
        render_summary(parse_summary(draft(template)), answer())


def metric_answer() -> QueryResponse:
    base = answer()
    return QueryResponse(
        base.kind,
        base.question,
        base.log_id,
        0,
        applied_conditions=base.applied_conditions,
        sql=base.sql,
        columns=("character_name", "top3_rate"),
        rows=({"character_name": "합성 이름", "top3_rate": "46.30"},),
    )


@pytest.mark.parametrize(
    "template,expected",
    [
        ("TOP 3 비율은 {0}입니다.", "TOP 3 비율은 46.30입니다."),
        ("탑3 비율은 {0}입니다.", "탑3 비율은 46.30입니다."),
        ("top3 비율은 {0}입니다.", "top3 비율은 46.30입니다."),
        ("top3_rate는 {0}입니다.", "top3_rate는 46.30입니다."),
    ],
)
def test_metric_names_with_a_digit_are_text_not_model_numbers(template: str, expected: str) -> None:
    response = metric_answer()
    data = {"sentences": [{"template": template, "cells": [{"row": 0, "column": "top3_rate"}]}]}
    assert render_summary(parse_summary(json.dumps(data, ensure_ascii=False)), response) == expected


@pytest.mark.parametrize(
    "template",
    [
        "TOP 4 비율은 {0}입니다.",
        "표본은 3건이고 비율은 {0}입니다.",
        "top 33 비율은 {0}입니다.",
        "top3_rate_2는 {0}입니다.",
        "TOP 3 비율은 {0}이고 3입니다.",
    ],
)
def test_digit_allowance_does_not_admit_other_literal_numbers(template: str) -> None:
    data = {"sentences": [{"template": template, "cells": [{"row": 0, "column": "top3_rate"}]}]}
    with pytest.raises(SummaryFormatError):
        render_summary(parse_summary(json.dumps(data, ensure_ascii=False)), metric_answer())


@pytest.mark.parametrize(
    "row,column", [(1, "rate"), (-1, "rate"), (True, "rate"), (0, "missing"), (0, 42)]
)
def test_nonexistent_and_invalid_cell_references_fail(row: Any, column: Any) -> None:
    with pytest.raises(SummaryFormatError):
        render_summary(parse_summary(draft(row=row, column=column)), answer())


@pytest.mark.parametrize(
    "text",
    [
        "not JSON PRIVATE",
        "[]",
        '{"sentences":[]}',
        '{"sentences":[],"extra":true}',
        '{"sentences":[],"sentences":[]}',
        json.dumps({"sentences": [{"template": "값", "cells": []}]}),
    ],
)
def test_invalid_summary_json_has_safe_error(text: str) -> None:
    with pytest.raises(SummaryFormatError) as failure:
        parse_summary(text)
    assert "PRIVATE" not in str(failure.value)


@pytest.mark.parametrize("value", [[1, 2], {"nested": 1}])
def test_nested_result_values_are_not_flattened_into_summary(value: Any) -> None:
    with pytest.raises(SummaryFormatError):
        render_summary(parse_summary(draft()), answer(value))


def test_summary_prompt_uses_result_without_raw_question_or_sql() -> None:
    prompt = build_summary_prompt(answer())
    assert "PRIVATE" not in prompt
    assert '"rate": "12.35"' in prompt
    assert "12.345678901234567890" not in prompt
    assert "No advice, recommendations, causality, predictions" in prompt
    assert "never SQL column identifiers" in prompt
    assert "pick_rate_before = 변경 전 픽률" in prompt
    assert "pick_rate_after = 변경 후 픽률" in prompt
    assert "pick_rate_change = 픽률 변화" in prompt
    assert "percentage points" in prompt.replace("\n", " ")


def test_summary_formatting_does_not_change_table_precision() -> None:
    response = answer()
    original = response.to_dict()
    build_summary_prompt(response)
    assert render_summary(parse_summary(draft()), response) == "비율은 12.35%입니다."
    assert response.to_dict() == original
    assert response.to_dict()["rows"][0]["rate"] == "12.345678901234567890"


def test_empty_rendered_summary_is_a_summary_failure() -> None:
    with pytest.raises(SummaryFormatError):
        render_summary(parse_summary(draft("{0}")), answer(""))


def test_two_sentences_use_separate_cell_references() -> None:
    data = json.loads(draft("값은 {0}입니다."))
    data["sentences"].append(data["sentences"][0])
    assert (
        render_summary(parse_summary(json.dumps(data)), answer(4)) == "값은 4입니다. 값은 4입니다."
    )
    data["sentences"].append(data["sentences"][0])
    with pytest.raises(SummaryFormatError):
        parse_summary(json.dumps(data))


def test_unused_cell_is_rejected() -> None:
    data = json.loads(draft())
    data["sentences"][0]["cells"].append({"row": 0, "column": "rate"})
    with pytest.raises(SummaryFormatError):
        render_summary(parse_summary(json.dumps(data)), answer())
