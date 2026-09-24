from __future__ import annotations

from collections.abc import Sequence
from typing import Any

import psycopg
from psycopg.rows import dict_row


def execute_select(database_url: str, sql: str) -> list[dict[str, Any]]:
    with psycopg.connect(database_url, autocommit=False, row_factory=dict_row) as connection:
        with connection.transaction():
            connection.execute("SET TRANSACTION READ ONLY")
            connection.execute("SET LOCAL statement_timeout = '5s'")
            rows = connection.execute(sql).fetchall()
            return [dict(row) for row in rows]


def sample_rows(database_url: str) -> dict[str, Sequence[dict[str, Any]]]:
    queries = {
        "games": "SELECT * FROM games ORDER BY game_id LIMIT 3",
        "participants": """
            SELECT game_id, nickname, team_number, character_num, best_weapon,
                   game_rank, player_kill, player_assistant, damage_to_player,
                   is_ranker
            FROM participants ORDER BY game_id, nickname LIMIT 3
        """,
        "characters": "SELECT * FROM characters ORDER BY character_code LIMIT 3",
        "weapon_types": "SELECT * FROM weapon_types ORDER BY code LIMIT 3",
    }
    samples: dict[str, Sequence[dict[str, Any]]] = {}
    for table, query in queries.items():
        rows = execute_select(database_url, query)
        if table == "participants":
            for index, row in enumerate(rows, start=1):
                row["nickname"] = f"player_{index}"
        samples[table] = rows
    return samples
