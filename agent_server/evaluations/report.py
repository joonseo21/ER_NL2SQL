"""Observed timings and outcomes are not correctness scores."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from er_agent.models.generation import TOKEN_FIELDS


def read_metrics(log_path: Path) -> dict[str, Any]:
    records = (
        [json.loads(line) for line in log_path.read_text(encoding="utf-8").splitlines()]
        if log_path.exists()
        else []
    )
    attempts = [item for item in records if item["event"] == "llm_attempt"]
    sql_attempts = [item for item in records if item["event"] == "sql_attempt"]
    ends = [item for item in records if item["event"] == "query_end"]
    end = ends[-1] if ends else {}
    tokens = {}
    for field in TOKEN_FIELDS:
        values = [item.get("tokens", {}).get(field) for item in attempts]
        tokens[field] = (
            sum(values) if values and all(type(value) is int for value in values) else None
        )
    return {
        "logging_complete": bool(ends),
        "logical_calls": end.get("logical_calls"),
        "physical_calls": end.get("physical_calls"),
        "llm_attempt_ms": [item["elapsed_ms"] for item in attempts],
        # Includes scope/guard, connection and SQL; not isolated database time.
        "sql_stage_ms": [item["elapsed_ms"] for item in sql_attempts],
        "sql_repaired": len(sql_attempts) > 1,
        "tokens": tokens,
        "exhausted_llm_call": any(
            not item["succeeded"] and not item["retry_scheduled"] for item in attempts
        ),
    }


def write_json(path: Path, payload: Any) -> None:
    text = json.dumps(payload, ensure_ascii=False, indent=2, allow_nan=False)
    path.write_text(text + "\n", encoding="utf-8")
