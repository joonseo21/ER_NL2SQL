from __future__ import annotations

import json
from collections.abc import Callable

import pytest

from er_agent.generation.decoder import parse_generation
from er_agent.models.errors import GenerationFormatError


def test_structured_question_meaning_is_typed(generation_text: Callable[..., str]) -> None:
    generated = parse_generation(
        generation_text(
            character={"text": "합성 실험체 A", "category": "character"},
            patch={"mode": "exact", "versions": [{"major": 4, "minor": 0}]},
            tier={"tier": None, "division": None},
            period={"start": "2026-10-08T00:00:00+09:00", "end": "2026-10-09T00:00:00+09:00"},
        )
    )
    assert generated.intent.patch.versions[0].major == 4
    assert generated.intent.tier.tier is None
    assert generated.intent.character.text == "합성 실험체 A"
    assert generated.intent.period.start.hour == 0


@pytest.mark.parametrize(
    "changes",
    [
        {"all_characters": "false"},
        {"names": {}},
        {"names": [{"text": "", "category": "character"}]},
        {"names": [{"text": "A", "category": "unknown"}]},
        {"patch": {"mode": "exact", "versions": [{"major": True, "minor": 0}]}},
        {"patch": {"mode": "exact", "versions": []}},
        {"tier": {"tier": None, "division": 4}},
        {"period": {"start": "2026-10-08", "end": "2026-10-09"}},
        {"unsupported_reason": "guess"},
        {"extra": 1},
    ],
)
def test_invalid_meaning_is_rejected(generation_text: Callable[..., str], changes: dict) -> None:
    with pytest.raises(GenerationFormatError):
        parse_generation(generation_text(**changes))


@pytest.mark.parametrize(
    "text",
    [
        "SELECT count(*) FROM participants",
        "```json\n{}\n```",
        "[]",
        '{"intent":{},"sql":null,"sql":"SELECT 1"}',
        '{"intent":{},"sql":null,"extra":true}',
    ],
)
def test_malformed_or_duplicate_json_is_rejected(text: str) -> None:
    with pytest.raises(GenerationFormatError):
        parse_generation(text)


def test_sql_type_is_not_coerced(generation_text: Callable[..., str]) -> None:
    item = json.loads(generation_text())
    item["sql"] = ["SELECT 1"]
    with pytest.raises(GenerationFormatError):
        parse_generation(json.dumps(item))


@pytest.mark.parametrize(
    "sample",
    [
        {"column": "samples", "unit": "users"},
        {"column": "", "unit": "games"},
        {"column": 1, "unit": "games"},
        {"column": "samples", "unit": "games", "extra": True},
    ],
)
def test_invalid_sample_declaration_is_not_coerced(
    generation_text: Callable[..., str], sample: dict
) -> None:
    with pytest.raises(GenerationFormatError):
        parse_generation(generation_text(sample=sample))
