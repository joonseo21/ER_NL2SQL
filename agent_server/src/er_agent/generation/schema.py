from __future__ import annotations

from typing import Any

from ..models.context import UnsupportedReason
from ..models.evidence import SampleUnit
from ..models.names import NameCategory
from ..models.query import PatchMode


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


GENERATION_SCHEMA = _object(
    {
        "intent": INTENT_SCHEMA,
        "sql": _nullable({"type": "string"}),
        "sample": _nullable(
            _object(
                {
                    "column": {"type": "string"},
                    "unit": {"type": "string", "enum": [unit.value for unit in SampleUnit]},
                }
            )
        ),
    }
)
