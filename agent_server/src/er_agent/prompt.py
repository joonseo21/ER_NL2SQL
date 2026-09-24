from __future__ import annotations

import json
from pathlib import Path
from typing import Any


SCHEMA_CONTEXT_PATH = Path(__file__).resolve().parents[2] / "schema_context.md"


def build_prompt(question: str, samples: dict[str, Any]) -> str:
    schema_context = SCHEMA_CONTEXT_PATH.read_text(encoding="utf-8")
    sample_json = json.dumps(samples, ensure_ascii=False, default=str, indent=2)
    return f"""You translate a Korean analytics question into safe PostgreSQL.

{schema_context}

Masked sample rows (these are context only; never filter by the masked nicknames):
{sample_json}

Question:
{question}
"""
