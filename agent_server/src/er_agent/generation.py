"""Strict JSON boundary between model meaning extraction and local validation."""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from .context import QueryIntent, TierSelection, TimeRange, UnsupportedReason
from .contracts import NameCategory, PatchFilter, PatchMode, PatchVersion
from .names import NameTerm


def _object(properties: dict[str, Any]) -> dict[str, Any]:
    return {
        "type": "object",
        "properties": properties,
        "required": list(properties),
        "additionalProperties": False,
    }


def _nullable(schema: dict[str, Any]) -> dict[str, Any]:
    return {"anyOf": [schema, {"type": "null"}]}


NAME_SCHEMA = _object(
    {
        "text": {"type": "string"},
        "category": _nullable({"type": "string", "enum": [item.value for item in NameCategory]}),
    }
)
INTENT_SCHEMA = _object(
    {
        "names": {"type": "array", "items": NAME_SCHEMA},
        "patch": _nullable(
            _object(
                {
                    "mode": {"type": "string", "enum": [item.value for item in PatchMode]},
                    "versions": {
                        "type": "array",
                        "items": _object(
                            {
                                "major": {"type": "integer", "minimum": 0},
                                "minor": {"type": "integer", "minimum": 0},
                            }
                        ),
                    },
                }
            )
        ),
        "tier": _nullable(
            _object(
                {
                    "tier": _nullable({"type": "string"}),
                    "division": _nullable({"type": "integer", "minimum": 0}),
                }
            )
        ),
        "character": _nullable(NAME_SCHEMA),
        "all_characters": {"type": "boolean"},
        "period": _nullable(
            _object(
                {
                    "start": {"type": "string"},
                    "end": {"type": "string"},
                }
            )
        ),
        "unsupported_reason": _nullable(
            {
                "type": "string",
                "enum": [reason.value for reason in UnsupportedReason],
            }
        ),
    }
)
GENERATION_SCHEMA = _object({"intent": INTENT_SCHEMA, "sql": _nullable({"type": "string"})})


class GenerationFormatError(ValueError):
    def __init__(self) -> None:
        super().__init__("Model response does not match the generation contract")


@dataclass(frozen=True)
class Generation:
    intent: QueryIntent
    sql: str | None


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
        item = _fields(json.loads(text, object_pairs_hook=_unique_object), {"intent", "sql"})
        if item["sql"] is not None and not isinstance(item["sql"], str):
            raise GenerationFormatError
        return Generation(_intent(item["intent"]), item["sql"])
    except (ValueError, TypeError, KeyError, OverflowError):
        # Do not echo untrusted model output or conversion errors to logs/clients.
        raise GenerationFormatError from None
