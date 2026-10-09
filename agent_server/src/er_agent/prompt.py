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

Non-identifying sample rows from allowed columns (examples only; never filter by example IDs):
{sample_json}

Question:
{question}
"""

if __name__ == "__main__":
    print(build_prompt("게임 수를 알려줘", {"games": [{"game_id": 1, "game_date": "2023-01-01"}]}))
