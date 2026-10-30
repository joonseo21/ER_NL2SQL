from __future__ import annotations

from typing import Any

import psycopg

from ..models.context import ContextSnapshot, ScopeBucket, TierSelection
from ..models.errors import DatabaseQueryError
from ..models.limits import MAX_ROWS
from ..models.names import NameCandidate, NameCatalog, NameCategory
from ..models.query import PatchVersion
from .connection import _analytics_connection
from .name_choice import NameRow, one_code_per_name

_NAME_SOURCES = (
    (NameCategory.CHARACTER, "characters", "character_code"),
    (NameCategory.WEAPON, "weapon_types", "code"),
    (NameCategory.ITEM, "items", "code"),
    (NameCategory.TRAIT, "trait_names", "code"),
    (NameCategory.TACTICAL, "tactical_skills", "code"),
)
# V10 records whether a code was seen in ranked squad matches. Tables that share one name across
# several codes keep only the preferred code (spec D-20); the other tables are read unchanged.
_ONE_CODE_PER_NAME_TABLES = frozenset({"items", "trait_names", "tactical_skills"})
_EXTRA_NAME_COLUMNS = {
    "items": ("seen_in_matches", "usage_season_id"),
    "trait_names": ("seen_in_matches", "usage_season_id", "trait_type"),
    "tactical_skills": ("seen_in_matches", "usage_season_id"),
}


def _available_name_columns(
    connection: psycopg.Connection[dict[str, Any]],
) -> set[tuple[str, str]]:
    """Columns that exist in this database; older databases lack the V10 columns."""
    tables = sorted(_EXTRA_NAME_COLUMNS)
    columns = sorted({column for names in _EXTRA_NAME_COLUMNS.values() for column in names})
    rows = connection.execute(
        "SELECT table_name, column_name FROM information_schema.columns "
        "WHERE table_schema = 'public' AND table_name = ANY(%s) AND column_name = ANY(%s)",
        (tables, columns),
    ).fetchall()
    return {(row["table_name"], row["column_name"]) for row in rows}


def _load_names(
    connection: psycopg.Connection[dict[str, Any]],
    season_id: int,
) -> NameCatalog:
    available = _available_name_columns(connection)
    entries: list[NameCandidate] = []
    for category, table, code_column in _NAME_SOURCES:
        extra = tuple(
            column for column in _EXTRA_NAME_COLUMNS.get(table, ()) if (table, column) in available
        )
        rows_for_table: list[NameRow] = []
        last_code = 0
        while True:
            # All interpolated identifiers come exclusively from _NAME_SOURCES and
            # _EXTRA_NAME_COLUMNS.
            columns = ", ".join((f"{code_column} AS code", "name_ko", *extra))
            query = (
                f"SELECT {columns} FROM {table} "
                f"WHERE {code_column} > %s ORDER BY {code_column} LIMIT %s"
            )
            rows = connection.execute(query, (last_code, MAX_ROWS)).fetchall()
            for row in rows:
                if row["name_ko"] and row["name_ko"].strip():
                    # Another season's usage says nothing about the configured season.
                    seen = row.get("seen_in_matches")
                    used = seen if row.get("usage_season_id") == season_id else None
                    rows_for_table.append(
                        NameRow(row["code"], row["name_ko"], used, row.get("trait_type"))
                    )
            if len(rows) < MAX_ROWS:
                break
            last_code = rows[-1]["code"]
        if table in _ONE_CODE_PER_NAME_TABLES:
            rows_for_table = one_code_per_name(rows_for_table)
        entries.extend(NameCandidate(category, row.code, row.name) for row in rows_for_table)
    return NameCatalog(tuple(entries))


_SCOPE_QUERY = """
    SELECT g.version_major, g.version_minor, p.tier, p.tier_division,
           COUNT(p.participant_id) AS appearances,
           MIN(g.start_dtm) AS first_match, MAX(g.start_dtm) AS last_match,
           MAX(g.fetched_at) AS data_as_of
    FROM games g LEFT JOIN participants p ON p.game_id = g.game_id
    WHERE g.season_id = %s AND g.matching_mode = 3 AND g.matching_team_mode = 3
      AND g.version_major = %s AND g.version_minor = %s
    GROUP BY g.version_major, g.version_minor, p.tier, p.tier_division
    ORDER BY g.version_major, g.version_minor, p.tier NULLS FIRST, p.tier_division NULLS FIRST
    LIMIT %s OFFSET %s
"""


def _load_scopes(
    connection: psycopg.Connection[dict[str, Any]],
    season_id: int,
) -> tuple[ScopeBucket, ...]:
    scopes: list[ScopeBucket] = []
    # The default hash/merge plan scans the entire participants index even after
    # filtering one patch. Use the game_id covering index for these small patch slices.
    connection.execute("SET LOCAL enable_hashjoin = off")
    connection.execute("SET LOCAL enable_mergejoin = off")
    versions = connection.execute(
        "SELECT DISTINCT version_major, version_minor FROM games "
        "WHERE season_id = %s AND matching_mode = 3 AND matching_team_mode = 3 "
        "ORDER BY version_major, version_minor",
        (season_id,),
    ).fetchall()
    for version in versions:
        offset = 0
        while True:
            rows = connection.execute(
                _SCOPE_QUERY,
                (season_id, version["version_major"], version["version_minor"], MAX_ROWS, offset),
            ).fetchall()
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
                break
            offset += MAX_ROWS
    return tuple(scopes)


def load_context_snapshot(database_url: str, season_id: int) -> ContextSnapshot:
    """Read official names and ranked-squad coverage in one consistent transaction."""
    if type(season_id) is not int or season_id < 1:
        raise ValueError("season_id must be a positive integer")
    with _analytics_connection(database_url, repeatable_read=True) as connection:
        catalog = _load_names(connection, season_id)
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
