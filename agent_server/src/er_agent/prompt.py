from __future__ import annotations

import json
from dataclasses import asdict
from datetime import datetime
from pathlib import Path
from typing import Any

from .context import ContextSnapshot, ResolvedContext
from .contracts import QueryRequest
from .generation import GENERATION_SCHEMA
from .sql_scope import name_parameters

SCHEMA_CONTEXT_PATH = Path(__file__).resolve().parents[2] / "schema_context.md"
METRICS_PATH = Path(__file__).resolve().parents[2] / "metrics.md"


def build_prompt(question: str, samples: dict[str, Any]) -> str:
    schema_context = SCHEMA_CONTEXT_PATH.read_text(encoding="utf-8")
    metrics = METRICS_PATH.read_text(encoding="utf-8")
    sample_json = json.dumps(samples, ensure_ascii=False, default=str, indent=2)
    return f"""You translate a Korean analytics question into safe PostgreSQL.

{schema_context}

Metric and terminology definitions (apply these definitions to SQL calculations):
{metrics}

Non-identifying sample rows from allowed columns (examples only; never filter by example IDs):
{sample_json}

Question:
{question}
"""


SQL_SCOPE_RULES = """Common season/mode/patch/tier/period scope is enforced by the server on
every games/participants/participant_* source. Do not add predicates on season_id,
matching_mode, matching_team_mode, version_major/minor, tier/division or start_dtm.
You may SELECT/GROUP BY those columns, and join columns to columns.
The server does NOT prefilter the target character: apply %(character_code)s to
the target group; for pick rate use it ONLY in the numerator, never its denominator.
Official named entities use %(name_CATEGORY_CODE)s, e.g. %(name_character_1)s.
Use every verified named entity in an appropriate code predicate, never as a
free-standing SELECT constant. Do not use literal codes or name strings as filters.
Use no other placeholders. SQL must read at least one game/participant fact source.
Aggregate arithmetic and joins must follow the metric definitions.
"""


def _json(value: Any) -> str:
    def encode(item: Any) -> str:
        if isinstance(item, datetime):
            return item.isoformat()
        raise TypeError("Unsupported prompt value")

    return json.dumps(value, ensure_ascii=False, default=encode)


def build_generation_prompt(request: QueryRequest, snapshot: ContextSnapshot) -> str:
    available = {
        "season_id": snapshot.season_id,
        "official_names": [asdict(entry) for entry in snapshot.catalog.entries],
        "scopes": [asdict(scope) for scope in snapshot.scopes],
        "tiers": [asdict(tier) for tier in snapshot.tiers],
    }
    return (
        build_prompt(request.question, {})
        + f"""
For this generation call, replace the SQL-only output rule with this JSON contract:
{_json(GENERATION_SCHEMA)}
Return exactly one JSON object, without markdown, with intent and sql.
Extract only conditions explicitly stated in the question. null overrides mean use
the selected conditions. An explicit all-tier override is {{"tier":null,"division":null}}.
Keep named terms as actually written; never normalize a typo or expand an alias.
Deduplicate identical named terms. Distinguish a target character from an opponent.
Include all named entities in intent.names; character is only the target override.
Period boundaries must be ISO datetimes with offsets, start inclusive/end exclusive.
An ambiguous/missing official name, unsupported question or unavailable requested
scope requires sql=null. Do not substitute another name, patch, tier or date range.
Question overrides take precedence over selected conditions. Resolve latest in the
whole season/mode first, before tier filtering. Never guess a patch display label.
Derive name bindings only from unique exact matches in the official catalog.
For each deduplicated resolved term use name_CATEGORY_CODE. If a target character
remains after merging question and selected conditions, use character_code INSTEAD
of name_character_CODE for that same target. Other named characters still use
name_character_CODE. Use every such binding in the appropriate code predicate.
The server will independently verify these names and conditions before execution.
{SQL_SCOPE_RULES}
Selected request:
{_json(asdict(request))}
Read-only available context (coverage bounds do not prove continuous collection):
{_json(available)}
"""
    )


def build_repair_prompt(
    request: QueryRequest, context: ResolvedContext, sql: str, error: str
) -> str:
    verified = {"context": asdict(context), "name_bindings": name_parameters(context)}
    return (
        build_prompt(request.question, {})
        + f"""
Repair the failed SQL once. Return only a single SELECT SQL statement.
Do not reinterpret the question, names or applied scope. Use this verified context:
{_json(verified)}
{SQL_SCOPE_RULES}
Failed model SQL:
{sql}
Safe error classification (no database rows or API response body):
{error}
"""
    )


if __name__ == "__main__":
    print(build_prompt("게임 수를 알려줘", {"games": [{"game_id": 1, "game_date": "2023-01-01"}]}))
