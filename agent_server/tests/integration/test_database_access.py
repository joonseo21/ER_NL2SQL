from __future__ import annotations

import json
import os

import psycopg
import pytest
from psycopg.conninfo import conninfo_to_dict, make_conninfo
from psycopg.rows import dict_row

from er_agent.cli import MANUAL_QUERIES
from er_agent.database import execute_select, sample_rows
from er_agent.guard import SqlGuardError, guard_sql
from er_agent.prompt import build_prompt
from er_agent.query_schema import TABLE_COLUMNS


pytestmark = pytest.mark.postgres
GAME_IDS = (9400001, 9400002)
PRIVATE_NICKNAMES = tuple(f"stage4_private_player_{i}" for i in range(5))


@pytest.fixture(scope="module")
def database() -> dict[str, str]:
    url = os.getenv("STAGE4_TEST_DATABASE_URL")
    if not url:
        pytest.skip("Set STAGE4_TEST_DATABASE_URL for the dedicated V4 integration database")
    settings = conninfo_to_dict(url)
    # This fixture writes synthetic rows. Never accept an ordinary/forwarded DB URL.
    assert settings.get("host") in {"127.0.0.1", "localhost"}
    assert settings.get("port") == "15444"
    assert settings.get("dbname") == "er_stage4_test"
    assert settings.get("user") == "stage4_test"
    with psycopg.connect(url, row_factory=dict_row) as owner:
        identity = owner.execute("SELECT current_database() AS db, current_user AS role").fetchone()
        assert identity == {"db": "er_stage4_test", "role": "stage4_test"}
        applied = owner.execute("SELECT max(version::integer) AS v FROM flyway_schema_history WHERE success AND version IS NOT NULL").fetchone()
        assert applied["v"] == 4, "Prepare V1-V4 with the existing independent Flyway runner"
        owner.execute("DELETE FROM games WHERE game_id = ANY(%s)", (list(GAME_IDS),))
        owner.execute("""
            INSERT INTO games(game_id, season_id, matching_mode, matching_team_mode,
                              version_major, version_minor, start_dtm, team_count, fetched_at)
            VALUES (%s,41,3,3,5,0,'2026-10-08 20:00:00+09',2,now()),
                   (%s,41,3,3,4,0,'2026-09-30 20:00:00+09',1,now())
            ON CONFLICT DO NOTHING
        """, GAME_IDS)
        mmrs = (3599, 5000, 8000, None, 5500)
        tiers = ("플래티넘 미만", "다이아몬드", "미스릴 이상", None, "다이아몬드")
        participant_ids = []
        for index, (nickname, mmr, tier) in enumerate(zip(PRIVATE_NICKNAMES, mmrs, tiers)):
            user = owner.execute("""
                INSERT INTO users(season_id,nickname,last_mmr,tier)
                VALUES (41,%s,%s,%s)
                ON CONFLICT (season_id,nickname) DO UPDATE SET last_mmr=EXCLUDED.last_mmr
                RETURNING user_id
            """, (nickname, mmr, tier)).fetchone()
            participant = owner.execute("""
                INSERT INTO participants(game_id,nickname,user_id,team_number,character_num,
                  best_weapon,game_rank,player_kill,player_assistant,damage_to_player,
                  mmr_before,mmr_after,version_major,version_minor,tier,tier_division,
                  trait_first_core,tactical_skill_group,skill_order,credit_source,raw)
                VALUES (%s,%s,%s,%s,%s,16,%s,2,3,1000,%s,%s,%s,0,%s,%s,123,1,
                        ARRAY[101,102],'{"credit":100}'::jsonb,%s::jsonb)
                RETURNING participant_id
            """, (GAME_IDS[0 if index < 4 else 1], nickname, user["user_id"], index//2+1,
                  index%2+1, 1 if index in {0,1,4} else 4, mmr, mmr,
                  5 if index < 4 else 4, tier, 4 if tier == "다이아몬드" else 0,
                  json.dumps({"killDetail": PRIVATE_NICKNAMES[0]}))).fetchone()
            participant_ids.append(participant["participant_id"])
        owner.execute("INSERT INTO characters VALUES (1,'합성 실험체 A','SyntheticA'),(2,'합성 실험체 B','SyntheticB') ON CONFLICT DO NOTHING")
        owner.execute("INSERT INTO weapon_types VALUES (16,'합성 무기') ON CONFLICT DO NOTHING")
        # Two child rows per participant exercise aggregation without join inflation.
        for participant_id in participant_ids:
            owner.execute("INSERT INTO participant_equipment VALUES (%s,'FINAL',0,100001,4),(%s,'FINAL',1,100002,4)", (participant_id, participant_id))
            owner.execute("INSERT INTO participant_traits VALUES (%s,'CORE',123)", (participant_id,))
            owner.execute("INSERT INTO participant_mastery VALUES (%s,16,20)", (participant_id,))
            owner.execute("INSERT INTO participant_matchups VALUES (%s,1,2,2,1)", (participant_id,))
            owner.execute("""INSERT INTO participant_deaths(participant_id,death_seq,killer_type,killer_participant_id,
                killer_character_num,killer_name,killer_weapon,cause_of_death,place_of_death)
                VALUES (%s,1,'player',%s,1,'SyntheticA','TwoHandSword','SyntheticSkill',10)""",
                          (participant_id,participant_ids[0]))
    settings["user"] = "agent_ro"
    settings.pop("password", None)
    return {"owner": url, "agent": make_conninfo(**settings)}


def test_column_contract_matches_database_grants(database: dict[str,str]) -> None:
    with psycopg.connect(database["owner"]) as owner:
        actual = owner.execute("""
            SELECT c.table_name,c.column_name FROM information_schema.columns c
            WHERE c.table_schema='public' AND has_column_privilege('agent_ro',
                  'public.' || quote_ident(c.table_name),c.column_name,'SELECT')
            ORDER BY c.table_name,c.ordinal_position
        """).fetchall()
    found: dict[str,list[str]] = {}
    for table,column in actual:
        found.setdefault(table,[]).append(column)
    assert {table:set(columns) for table,columns in found.items()} == {
        table:set(columns) for table,columns in TABLE_COLUMNS.items()
    }


@pytest.mark.parametrize("sql", [
    "SELECT nickname FROM participants",
    "SELECT raw FROM participants",
    "SELECT is_ranker FROM participants",
    "SELECT p.nickname AS hidden FROM participants p",
    "SELECT concat(nickname,'suffix') FROM participants",
    "SELECT raw->>'killDetail' FROM participants",
    "SELECT p FROM participants p",
    "SELECT row_to_json(p) FROM participants p",
    "SELECT to_jsonb(p) FROM participants p",
    "SELECT p.b FROM participants p(a,b)",
    "SELECT p.game_id FROM participants p NATURAL JOIN participants q",
    "SELECT count(*) FROM participants p JOIN participants q USING(nickname)",
    "SELECT p.* FROM participants p",
    "SELECT user_id FROM users",
    "SELECT uid FROM rankers",
    "SELECT installed_rank FROM flyway_schema_history",
    "SELECT id FROM collect_queue",
    "SELECT id FROM api_call_log",
    "WITH x AS (SELECT nickname AS id FROM participants) SELECT id FROM x",
])
def test_guard_and_database_independently_block_private_access(database: dict[str,str], sql: str) -> None:
    with pytest.raises(SqlGuardError):
        guard_sql(sql)
    with psycopg.connect(database["agent"], autocommit=True) as agent:
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            agent.execute(sql).fetchall()


def test_samples_and_prompt_never_receive_nicknames(database: dict[str,str]) -> None:
    samples = sample_rows(database["agent"])
    assert set(samples) == set(TABLE_COLUMNS)
    assert all(samples.values())
    payload = json.dumps(samples,ensure_ascii=False,default=str)
    prompt = build_prompt("티어별 집계",samples)
    for nickname in PRIVATE_NICKNAMES:
        assert nickname not in payload and nickname not in prompt
    assert "killDetail" not in payload
    for row in samples["participants"]:
        assert {"nickname","raw","is_ranker"}.isdisjoint(row)


def test_tier_cte_includes_below_platinum_and_unknown(database: dict[str,str]) -> None:
    rows = execute_select(database["agent"], """
        WITH counts AS (
          SELECT tier,count(*) AS samples FROM participants WHERE version_major=5 GROUP BY tier
        ) SELECT tier,samples FROM counts
    """)
    counts = {row["tier"]:row["samples"] for row in rows}
    assert counts == {"플래티넘 미만":1,"다이아몬드":1,"미스릴 이상":1,None:1}


def test_child_filter_does_not_inflate_samples(database: dict[str,str]) -> None:
    rows = execute_select(database["agent"], """
        SELECT count(*) AS samples FROM participants p
        WHERE EXISTS (SELECT 1 FROM participant_equipment e
                      WHERE e.participant_id=p.participant_id AND e.kind='FINAL')
    """)
    assert rows == [{"samples":5}]


@pytest.mark.parametrize("sql", [
    "SELECT credit_source->>'credit' AS credit, skill_order[1] AS skill FROM participants",
    "SELECT x.skill FROM participants p CROSS JOIN LATERAL unnest(p.skill_order) AS x(skill)",
    "SELECT string_agg(tier,',') AS tiers FROM participants",
    "SELECT date_trunc('day',start_dtm AT TIME ZONE 'Asia/Seoul') AS day FROM games",
    "SELECT CASE WHEN game_rank=1 THEN 1 ELSE 0 END AS win FROM participants",
])
def test_allowed_expressions_execute(database: dict[str,str], sql: str) -> None:
    assert execute_select(database["agent"],sql)


@pytest.mark.parametrize("query_name", list(MANUAL_QUERIES))
def test_manual_queries_work_with_v4_privileges(database: dict[str,str], query_name: str) -> None:
    assert execute_select(database["agent"],MANUAL_QUERIES[query_name])


def test_all_allowed_columns_execute(database: dict[str,str]) -> None:
    for table,columns in TABLE_COLUMNS.items():
        query = f'SELECT {", ".join(columns)} FROM {table} LIMIT 1'
        assert execute_select(database["agent"],query)


def test_execution_requires_agent_role(database: dict[str,str]) -> None:
    with pytest.raises(ValueError,match="agent_ro"):
        execute_select(database["owner"],"SELECT count(*) FROM games")


def test_execution_is_capped_to_200(database: dict[str,str]) -> None:
    # More than 200 rows using allowed sources; no fixture bulk inserts needed.
    sql = "SELECT a.game_id FROM games a CROSS JOIN participants b CROSS JOIN participants c CROSS JOIN participants d LIMIT 999"
    assert len(execute_select(database["agent"],sql)) == 200


def test_readonly_account_cannot_write_or_own_schema(database: dict[str,str]) -> None:
    with psycopg.connect(database["agent"], autocommit=True) as agent:
        assert agent.execute("SHOW default_transaction_read_only").fetchone()[0] == "on"
        assert agent.execute("SHOW statement_timeout").fetchone()[0] == "5s"
        with pytest.raises(psycopg.errors.ReadOnlySqlTransaction):
            agent.execute("CREATE TABLE stage4_forbidden(id integer)")


def test_five_second_timeout_and_recovery(database: dict[str,str]) -> None:
    joins = " ".join(f"CROSS JOIN participants p{i}" for i in range(1,15))
    with pytest.raises(psycopg.errors.QueryCanceled):
        execute_select(database["agent"],f"SELECT count(*) FROM participants p0 {joins}")
    assert execute_select(database["agent"],"SELECT count(*) AS n FROM participants") == [{"n":5}]
