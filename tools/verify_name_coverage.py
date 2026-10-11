"""Measure code/name coverage using a saved mapper contract and agent_ro only.

Run with agent_server/.venv Python. Reads AGENT_DATABASE_URL from the environment
or the project's .env; prints only counts, rates and unmatched numeric codes.
The contract is exported by capturedOfficialMetadataMapsAndPersistsWithoutMoreApiCalls.
No migration, API request, name update or individual player data is performed.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

import psycopg
from dotenv import dotenv_values

# Fixed SQL identifiers only. Code values are bound, never interpolated.
FIELDS = {
    "characters": ("CHARACTER", "SELECT character_num AS code FROM participants"),
    "weapons": ("WEAPON", "SELECT best_weapon AS code FROM participants"),
    "equipment": ("ITEM", "SELECT item_code AS code FROM participant_equipment"),
    "traits": ("TRAIT", "SELECT trait_code AS code FROM participant_traits"),
    "core_traits": ("TRAIT", "SELECT trait_first_core AS code FROM participants"),
    "tactical_skills": ("TACTICAL", "SELECT tactical_skill_group AS code FROM participants"),
    "skill_order": ("SKILL_GROUP", "SELECT unnest(skill_order) AS code FROM participants"),
    "start_areas": ("AREA", "SELECT place_of_start AS code FROM participants"),
    "death_areas": ("AREA", "SELECT place_of_death AS code FROM participant_deaths"),
    "main_weather": ("WEATHER", "SELECT main_weather AS code FROM games"),
    "sub_weather": ("WEATHER", "SELECT sub_weather AS code FROM games"),
}


def measure(connection: psycopg.Connection, contract: dict) -> dict:
    report = {}
    for field, (kind, query) in FIELDS.items():
        codes = contract[kind]
        if not codes or any(type(code) is not int or code <= 0 for code in codes):
            raise ValueError("Expected positive integer codes from the verified metadata mapper")
        row = connection.execute(
            f"""
            WITH code_values AS ({query})
            SELECT count(*) FILTER (WHERE code > 0),
                   count(*) FILTER (WHERE code > 0 AND code = ANY(%s)),
                   count(DISTINCT code) FILTER (WHERE code > 0),
                   count(DISTINCT code) FILTER (WHERE code > 0 AND code = ANY(%s)),
                   count(*) FILTER (WHERE code IS NULL OR code = 0)
            FROM code_values
        """,
            (codes, codes),
        ).fetchone()
        unmatched = connection.execute(
            f"""
            WITH code_values AS ({query})
            SELECT code,count(*) FROM code_values
            WHERE code > 0 AND NOT (code = ANY(%s))
            GROUP BY code ORDER BY count(*) DESC,code LIMIT 12
        """,
            (codes,),
        ).fetchall()
        total, matched, distinct, matched_distinct, missing = row
        report[field] = {
            "positive_occurrences": total,
            "matched_occurrences": matched,
            "match_percent": round(100 * matched / total, 4) if total else None,
            "distinct_codes": distinct,
            "matched_distinct_codes": matched_distinct,
            "distinct_match_percent": round(100 * matched_distinct / distinct, 4)
            if distinct
            else None,
            "null_or_zero": missing,
            "unmatched_top12": [{"code": code, "occurrences": count} for code, count in unmatched],
        }
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("contract", type=Path)
    args = parser.parse_args()
    contract = json.loads(args.contract.read_text(encoding="utf-8"))
    url = os.getenv("AGENT_DATABASE_URL") or dotenv_values(
        Path(__file__).resolve().parents[1] / ".env"
    ).get("AGENT_DATABASE_URL")
    if not url:
        raise SystemExit("AGENT_DATABASE_URL is required")
    try:
        with psycopg.connect(url, connect_timeout=5) as connection:
            connection.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY")
            connection.execute("SET LOCAL statement_timeout = '5s'")
            if connection.execute("SELECT current_user").fetchone()[0] != "agent_ro":
                raise ValueError("Use the actual agent_ro database role")
            checked_at, games, participants = connection.execute(
                "SELECT current_timestamp,"
                "(SELECT count(*) FROM games),(SELECT count(*) FROM participants)"
            ).fetchone()
            report = measure(connection, contract)
        print(
            json.dumps(
                {
                    "checked_at": checked_at.isoformat(),
                    "games": games,
                    "participants": participants,
                    "metadata_source": (
                        "saved official API/l10n mapped and persisted in isolated V5 DB"
                    ),
                    "denominator": (
                        "positive code occurrences; NULL/0 excluded and reported separately"
                    ),
                    "fields": report,
                },
                ensure_ascii=False,
                indent=2,
            )
        )
    except (psycopg.Error, ValueError) as error:
        # Connection strings and server detail messages can contain credentials.
        raise SystemExit(
            f"Read-only coverage verification failed: {type(error).__name__}"
        ) from None


if __name__ == "__main__":
    main()
