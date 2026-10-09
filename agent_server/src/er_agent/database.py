from __future__ import annotations

from collections.abc import Sequence
from typing import Any

import psycopg
from psycopg.rows import dict_row

from .guard import guard_sql
from .query_schema import MAX_ROWS


def execute_select(database_url: str, sql: str) -> list[dict[str, Any]]:
    guarded = guard_sql(sql)
    with psycopg.connect(database_url, autocommit=False, row_factory=dict_row) as connection:
        with connection.transaction():
            connection.execute("SET TRANSACTION READ ONLY")
            connection.execute("SET LOCAL statement_timeout = '5s'")
            connection.execute("SET LOCAL search_path = pg_catalog, public")
            identity = connection.execute("SELECT current_user AS role").fetchone()
            if identity is None or identity["role"] != "agent_ro":
                raise ValueError("Analytics queries must execute as agent_ro")
            rows = connection.execute(guarded.sql).fetchmany(MAX_ROWS)
            return [dict(row) for row in rows]


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
        "characters": "SELECT character_code, name_ko, name_en FROM characters ORDER BY character_code LIMIT 3",
        "weapon_types": "SELECT code, name_ko FROM weapon_types ORDER BY code LIMIT 3",
        "tiers": "SELECT season_id, tier, division, min_mmr, sort_order FROM tiers ORDER BY season_id, sort_order LIMIT 20",
        "participant_equipment": "SELECT participant_id, kind, slot, item_code, grade FROM participant_equipment ORDER BY participant_id, kind, slot, item_code LIMIT 3",
        "participant_traits": "SELECT participant_id, slot_type, trait_code FROM participant_traits ORDER BY participant_id, slot_type, trait_code LIMIT 3",
        "participant_mastery": "SELECT participant_id, mastery_code, mastery_level FROM participant_mastery ORDER BY participant_id, mastery_code LIMIT 3",
        "participant_matchups": "SELECT participant_id, character_num, opponent_character_num, kills, deaths FROM participant_matchups ORDER BY participant_id, opponent_character_num LIMIT 3",
        "participant_deaths": "SELECT participant_id, death_seq, killer_type, killer_participant_id, killer_character_num, killer_name, killer_weapon, cause_of_death, place_of_death FROM participant_deaths ORDER BY participant_id, death_seq LIMIT 3",
    }
    samples: dict[str, Sequence[dict[str, Any]]] = {}
    for table, query in queries.items():
        rows = execute_select(database_url, query)
        samples[table] = rows
    return samples
