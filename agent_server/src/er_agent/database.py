from __future__ import annotations

from collections.abc import Iterator, Sequence
from contextlib import contextmanager
from typing import Any
from uuid import uuid4

import psycopg
from psycopg.rows import dict_row
from sqlglot import exp, parse_one

from .context import ContextSnapshot, ScopeBucket, TierSelection
from .contracts import NameCandidate, NameCategory, PatchVersion
from .guard import guard_sql
from .names import NameCatalog
from .query_schema import MAX_ROWS


def execute_select(
    database_url: str, sql: str, parameters: dict[str, Any] | None = None
) -> list[dict[str, Any]]:
    guarded = guard_sql(sql)
    query = _parameter_query(guarded.sql) if parameters is not None else guarded.sql
    with _analytics_connection(database_url) as connection:
        rows = connection.execute(query, parameters).fetchmany(MAX_ROWS)
        return [dict(row) for row in rows]


def _parameter_query(sql: str) -> str:
    """Escape percent literals/operators without changing real pyformat placeholders."""
    statement = parse_one(sql, dialect="postgres")
    markers: dict[str, str] = {}
    prefix = "__er_bind_" + uuid4().hex
    for index, placeholder in enumerate(list(statement.find_all(exp.Placeholder))):
        marker = f"{prefix}_{index}__"
        markers[marker] = placeholder.sql(dialect="postgres")
        placeholder.replace(exp.Var(this=marker))
    query = statement.sql(dialect="postgres").replace("%", "%%")
    for marker, placeholder_sql in markers.items():
        query = query.replace(marker, placeholder_sql)
    return query


@contextmanager
def _analytics_connection(
    database_url: str,
    *,
    repeatable_read: bool = False,
) -> Iterator[psycopg.Connection[dict[str, Any]]]:
    with (
        psycopg.connect(
            database_url,
            autocommit=False,
            row_factory=dict_row,
            connect_timeout=5,
        ) as connection,
        connection.transaction(),
    ):
        if repeatable_read:
            connection.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY")
        else:
            connection.execute("SET TRANSACTION READ ONLY")
        connection.execute("SET LOCAL statement_timeout = '5s'")
        connection.execute("SET LOCAL search_path = pg_catalog, public")
        identity = connection.execute("SELECT current_user AS role").fetchone()
        if identity is None or identity["role"] != "agent_ro":
            raise ValueError("Analytics queries must execute as agent_ro")
        yield connection


# Internal metadata queries are fixed SQL, independent of generated SQL. Pagination
# avoids silently losing names beyond the 200-row generated-result limit.
_NAME_SOURCES = (
    (NameCategory.CHARACTER, "characters", "character_code"),
    (NameCategory.WEAPON, "weapon_types", "code"),
    (NameCategory.ITEM, "items", "code"),
    (NameCategory.TRAIT, "trait_names", "code"),
    (NameCategory.TACTICAL, "tactical_skills", "code"),
)


def _load_names(connection: psycopg.Connection[dict[str, Any]]) -> NameCatalog:
    entries: list[NameCandidate] = []
    for category, table, code_column in _NAME_SOURCES:
        last_code = 0
        while True:
            # All interpolated identifiers come exclusively from _NAME_SOURCES.
            query = (
                f"SELECT {code_column} AS code, name_ko FROM {table} "
                f"WHERE {code_column} > %s ORDER BY {code_column} LIMIT %s"
            )
            rows = connection.execute(query, (last_code, MAX_ROWS)).fetchall()
            for row in rows:
                if row["name_ko"] and row["name_ko"].strip():
                    entries.append(NameCandidate(category, row["code"], row["name_ko"]))
            if len(rows) < MAX_ROWS:
                break
            last_code = rows[-1]["code"]
    return NameCatalog(tuple(entries))


_SCOPE_QUERY = """
    SELECT g.version_major, g.version_minor, p.tier, p.tier_division,
           COUNT(p.participant_id) AS appearances,
           MIN(g.start_dtm) AS first_match, MAX(g.start_dtm) AS last_match,
           MAX(g.fetched_at) AS data_as_of
    FROM games g LEFT JOIN participants p ON p.game_id = g.game_id
    WHERE g.season_id = %s AND g.matching_mode = 3 AND g.matching_team_mode = 3
    GROUP BY g.version_major, g.version_minor, p.tier, p.tier_division
    ORDER BY g.version_major, g.version_minor, p.tier NULLS FIRST, p.tier_division NULLS FIRST
    LIMIT %s OFFSET %s
"""


def _load_scopes(
    connection: psycopg.Connection[dict[str, Any]],
    season_id: int,
) -> tuple[ScopeBucket, ...]:
    scopes: list[ScopeBucket] = []
    offset = 0
    while True:
        rows = connection.execute(_SCOPE_QUERY, (season_id, MAX_ROWS, offset)).fetchall()
        scopes.extend(
            ScopeBucket(
                PatchVersion(row["version_major"], row["version_minor"]),
                row["tier"],
                row["tier_division"],
                row["appearances"],
                row["first_match"],
                row["last_match"],
                row["data_as_of"],
            )
            for row in rows
        )
        if len(rows) < MAX_ROWS:
            return tuple(scopes)
        offset += MAX_ROWS


def load_context_snapshot(database_url: str, season_id: int) -> ContextSnapshot:
    """Read official names and ranked-squad coverage in one consistent transaction."""
    if type(season_id) is not int or season_id < 1:
        raise ValueError("season_id must be a positive integer")
    with _analytics_connection(database_url, repeatable_read=True) as connection:
        catalog = _load_names(connection)
        scopes = _load_scopes(connection, season_id)
        rows = connection.execute(
            "SELECT tier, division FROM tiers WHERE season_id = %s ORDER BY sort_order",
            (season_id,),
        ).fetchall()
        tiers = tuple(TierSelection(row["tier"], row["division"]) for row in rows)
        return ContextSnapshot(season_id, catalog, scopes, tiers)


def sample_rows(database_url: str) -> dict[str, Sequence[dict[str, Any]]]:
    queries = {
        "games": """
            SELECT game_id, season_id, matching_mode, matching_team_mode,
                   version_major, version_minor, start_dtm, team_count, main_weather, sub_weather
            FROM games ORDER BY game_id LIMIT 3
        """,
        "participants": """
            SELECT participant_id, user_id, game_id, team_number, character_num, best_weapon,
                   game_rank, player_kill, player_assistant, damage_to_player,
                   mmr_before, version_major, version_minor, tier, tier_division,
                   trait_first_core, tactical_skill_group
            FROM participants ORDER BY participant_id LIMIT 3
        """,
        "characters": """
            SELECT character_code, name_ko, name_en
            FROM characters ORDER BY character_code LIMIT 3
        """,
        "weapon_types": "SELECT code, name_ko FROM weapon_types ORDER BY code LIMIT 3",
        "tiers": """
            SELECT season_id, tier, division, min_mmr, sort_order
            FROM tiers ORDER BY season_id, sort_order LIMIT 20
        """,
        "participant_equipment": """
            SELECT participant_id, kind, slot, item_code, grade
            FROM participant_equipment ORDER BY participant_id, kind, slot, item_code LIMIT 3
        """,
        "participant_traits": """
            SELECT participant_id, slot_type, trait_code
            FROM participant_traits ORDER BY participant_id, slot_type, trait_code LIMIT 3
        """,
        "participant_mastery": """
            SELECT participant_id, mastery_code, mastery_level
            FROM participant_mastery ORDER BY participant_id, mastery_code LIMIT 3
        """,
        "participant_matchups": """
            SELECT participant_id, character_num, opponent_character_num, kills, deaths
            FROM participant_matchups ORDER BY participant_id, opponent_character_num LIMIT 3
        """,
        "participant_deaths": """
            SELECT participant_id, death_seq, killer_type, killer_participant_id,
                   killer_character_num, killer_name, killer_weapon, cause_of_death, place_of_death
            FROM participant_deaths ORDER BY participant_id, death_seq LIMIT 3
        """,
        "items": """
            SELECT code, name_ko, item_type, item_grade, grade_name_ko, equipment_type, checked_at
            FROM items ORDER BY code LIMIT 3
        """,
        "trait_names": """
            SELECT code, name_ko, trait_type, trait_group, active, checked_at
            FROM trait_names ORDER BY code LIMIT 3
        """,
        "tactical_skills": """
            SELECT code, name_ko, skill_group_code, checked_at
            FROM tactical_skills ORDER BY code LIMIT 3
        """,
        "skill_groups": "SELECT code, name_ko, checked_at FROM skill_groups ORDER BY code LIMIT 3",
        "areas": "SELECT code, name_ko, name_en, checked_at FROM areas ORDER BY code LIMIT 3",
        "weather_types": """
            SELECT code, name_ko, checked_at FROM weather_types ORDER BY code LIMIT 3
        """,
    }
    samples: dict[str, Sequence[dict[str, Any]]] = {}
    for table, query in queries.items():
        rows = execute_select(database_url, query)
        samples[table] = rows
    return samples
