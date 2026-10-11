from __future__ import annotations

import json
from datetime import datetime
from typing import Any

from ..models.context import QueryIntent, TierSelection, TimeRange, UnsupportedReason
from ..models.errors import GenerationFormatError
from ..models.evidence import SampleColumn, SampleUnit
from ..models.generation import Generation
from ..models.names import NameCategory, NameTerm
from ..models.query import PatchFilter, PatchMode, PatchVersion
from .schema import INTENT_SCHEMA


def _fields(value: Any, keys: set[str]) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != keys:
        raise GenerationFormatError
    return value


def _list(value: Any) -> list[Any]:
    if not isinstance(value, list):
        raise GenerationFormatError
    return value


def _name(value: Any) -> NameTerm:
    item = _fields(value, {"text", "category"})
    category = NameCategory(item["category"]) if item["category"] is not None else None
    return NameTerm(item["text"], category)


def _patch(value: Any) -> PatchFilter | None:
    if value is None:
        return None
    item = _fields(value, {"mode", "versions"})
    versions = []
    for version in _list(item["versions"]):
        version = _fields(version, {"major", "minor"})
        versions.append(PatchVersion(version["major"], version["minor"]))
    return PatchFilter(PatchMode(item["mode"]), tuple(versions))


def _intent(value: Any) -> QueryIntent:
    item = _fields(value, set(INTENT_SCHEMA["properties"]))
    tier = _fields(item["tier"], {"tier", "division"}) if item["tier"] is not None else None
    period = _fields(item["period"], {"start", "end"}) if item["period"] is not None else None
    return QueryIntent(
        names=tuple(_name(name) for name in _list(item["names"])),
        patch=_patch(item["patch"]),
        tier=TierSelection(tier["tier"], tier["division"]) if tier else None,
        character=_name(item["character"]) if item["character"] is not None else None,
        all_characters=item["all_characters"],
        period=TimeRange(
            datetime.fromisoformat(period["start"]), datetime.fromisoformat(period["end"])
        )
        if period
        else None,
        unsupported_reason=UnsupportedReason(item["unsupported_reason"])
        if item["unsupported_reason"] is not None
        else None,
    )


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise GenerationFormatError
        result[key] = value
    return result


def parse_generation(text: str) -> Generation:
    try:
        item = _fields(
            json.loads(text, object_pairs_hook=_unique_object), {"intent", "sql", "sample"}
        )
        if item["sql"] is not None and not isinstance(item["sql"], str):
            raise GenerationFormatError
        sample = _fields(item["sample"], {"column", "unit"}) if item["sample"] is not None else None
        return Generation(
            _intent(item["intent"]),
            item["sql"],
            SampleColumn(sample["column"], SampleUnit(sample["unit"])) if sample else None,
        )
    except (ValueError, TypeError, KeyError, OverflowError):
        # Do not echo untrusted model output or conversion errors to logs/clients.
        raise GenerationFormatError from None
