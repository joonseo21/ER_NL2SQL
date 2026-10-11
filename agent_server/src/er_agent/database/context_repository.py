from __future__ import annotations

from typing import Any

import psycopg

from ..models.context import ContextSnapshot, ScopeBucket, TierSelection
from ..models.errors import DatabaseQueryError
from ..models.limits import MAX_ROWS
from ..models.names import NameCandidate, NameCatalog, NameCategory
from ..models.query import PatchVersion
from .connection import _analytics_connection

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


class PostgresContextRepository:
    def __init__(self, database_url: str) -> None:
        self.database_url = database_url

    def load(self, season_id: int) -> ContextSnapshot:
        try:
            return load_context_snapshot(self.database_url, season_id)
        except psycopg.Error as exception:
            raise DatabaseQueryError(exception.sqlstate) from None
