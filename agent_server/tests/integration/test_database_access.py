from __future__ import annotations

import json
import os
import re
from collections.abc import Callable
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock

import psycopg
import pytest
from psycopg.conninfo import conninfo_to_dict, make_conninfo
from psycopg.rows import dict_row

from er_agent.cli import MANUAL_QUERIES
from er_agent.context import QueryIntent, ResolvedContext, TierSelection, prepare_context
from er_agent.contracts import (
    NameCategory,
    PatchFilter,
    PatchMode,
    PatchVersion,
    QueryConditions,
    QueryRequest,
    ResponseKind,
)
from er_agent.database import execute_select, load_context_snapshot, sample_rows
from er_agent.guard import SqlGuardError, guard_sql
from er_agent.names import NameTerm
from er_agent.nl2sql import run_query
from er_agent.prompt import METRICS_PATH, build_prompt
from er_agent.query_schema import TABLE_COLUMNS
from er_agent.settings import Settings
from er_agent.sql_scope import scope_sql

pytestmark = pytest.mark.postgres
GAME_IDS = (9400001, 9400002)
PRIVATE_NICKNAMES = tuple(f"stage4_private_player_{i}" for i in range(5))


@pytest.fixture(scope="module")
def database() -> dict[str, str]:
    url = os.getenv("STAGE4_TEST_DATABASE_URL")
    if not url:
        pytest.skip("Set STAGE4_TEST_DATABASE_URL for the dedicated V5 integration database")
    settings = conninfo_to_dict(url)
    # This fixture writes synthetic rows. Never accept an ordinary/forwarded DB URL.
    assert settings.get("host") in {"127.0.0.1", "localhost"}
    assert settings.get("port") == "15444"
    assert settings.get("dbname") == "er_stage4_test"
    assert settings.get("user") == "stage4_test"
    with psycopg.connect(url, row_factory=dict_row) as owner:
        identity = owner.execute("SELECT current_database() AS db, current_user AS role").fetchone()
        assert identity == {"db": "er_stage4_test", "role": "stage4_test"}
        applied = owner.execute(
            "SELECT max(version::integer) AS v FROM flyway_schema_history WHERE success AND version IS NOT NULL"
        ).fetchone()
        assert applied["v"] == 5, "Prepare V1-V5 with the existing independent Flyway runner"
        owner.execute("DELETE FROM games WHERE game_id = ANY(%s)", (list(GAME_IDS),))
        owner.execute(
            """
            INSERT INTO games(game_id, season_id, matching_mode, matching_team_mode,
                              version_major, version_minor, start_dtm, team_count, fetched_at)
            VALUES (%s,41,3,3,5,0,'2026-10-08 20:00:00+09',2,now()),
                   (%s,41,3,3,4,0,'2026-09-30 20:00:00+09',1,now())
            ON CONFLICT DO NOTHING
        """,
            GAME_IDS,
        )
        mmrs = (3599, 5000, 8000, None, 5500)
        tiers = ("플래티넘 미만", "다이아몬드", "미스릴 이상", None, "다이아몬드")
        participant_ids = []
        for index, (nickname, mmr, tier) in enumerate(
            zip(PRIVATE_NICKNAMES, mmrs, tiers, strict=True)
        ):
            user = owner.execute(
                """
                INSERT INTO users(season_id,nickname,last_mmr,tier)
                VALUES (41,%s,%s,%s)
                ON CONFLICT (season_id,nickname) DO UPDATE SET last_mmr=EXCLUDED.last_mmr
                RETURNING user_id
            """,
                (nickname, mmr, tier),
            ).fetchone()
            participant = owner.execute(
                """
                INSERT INTO participants(game_id,nickname,user_id,team_number,character_num,
                  best_weapon,game_rank,player_kill,player_assistant,damage_to_player,
                  mmr_before,mmr_after,version_major,version_minor,tier,tier_division,
                  trait_first_core,tactical_skill_group,skill_order,credit_source,raw)
                VALUES (%s,%s,%s,%s,%s,16,%s,2,3,1000,%s,%s,%s,0,%s,%s,123,1,
                        ARRAY[101,102],'{"credit":100}'::jsonb,%s::jsonb)
                RETURNING participant_id
            """,
                (
                    GAME_IDS[0 if index < 4 else 1],
                    nickname,
                    user["user_id"],
                    index // 2 + 1,
                    index % 2 + 1,
                    1 if index in {0, 1, 4} else 4,
                    mmr,
                    mmr,
                    5 if index < 4 else 4,
                    tier,
                    4 if tier == "다이아몬드" else 0,
                    json.dumps({"killDetail": PRIVATE_NICKNAMES[0]}),
                ),
            ).fetchone()
            participant_ids.append(participant["participant_id"])
        owner.execute(
            "INSERT INTO characters(character_code,name_ko,name_en) VALUES (1,'합성 실험체 A','SyntheticA'),(2,'합성 실험체 B','SyntheticB') ON CONFLICT DO NOTHING"
        )
        owner.execute(
            "INSERT INTO weapon_types(code,name_ko) VALUES (16,'합성 무기') ON CONFLICT DO NOTHING"
        )
        for table in (
            "items",
            "trait_names",
            "tactical_skills",
            "skill_groups",
            "areas",
            "weather_types",
        ):
            # Identifiers are a fixed test allowlist; no user input is interpolated.
            extra_columns = ",skill_group_code" if table == "tactical_skills" else ""
            extra_values = ",123" if table == "tactical_skills" else ""
            owner.execute(
                f"INSERT INTO {table}(code,name_ko,source,l10n_key,checked_at{extra_columns}) "
                f"VALUES (123,'합성 이름','fixture','fixture/name',now(){extra_values}) ON CONFLICT DO NOTHING"
            )
        # Two child rows per participant exercise aggregation without join inflation.
        for participant_id in participant_ids:
            owner.execute(
                "INSERT INTO participant_equipment VALUES (%s,'FINAL',0,100001,4),(%s,'FINAL',1,100002,4)",
                (participant_id, participant_id),
            )
            owner.execute(
                "INSERT INTO participant_traits VALUES (%s,'CORE',123)", (participant_id,)
            )
            owner.execute("INSERT INTO participant_mastery VALUES (%s,16,20)", (participant_id,))
            owner.execute("INSERT INTO participant_matchups VALUES (%s,1,2,2,1)", (participant_id,))
            owner.execute(
                """INSERT INTO participant_deaths(participant_id,death_seq,killer_type,killer_participant_id,
                killer_character_num,killer_name,killer_weapon,cause_of_death,place_of_death)
                VALUES (%s,1,'player',%s,1,'SyntheticA','TwoHandSword','SyntheticSkill',10)""",
                (participant_id, participant_ids[0]),
            )
    settings["user"] = "agent_ro"
    settings.pop("password", None)
    return {"owner": url, "agent": make_conninfo(**settings)}


def test_column_contract_matches_database_grants(database: dict[str, str]) -> None:
    with psycopg.connect(database["owner"]) as owner:
        actual = owner.execute("""
            SELECT c.table_name,c.column_name FROM information_schema.columns c
            WHERE c.table_schema='public' AND has_column_privilege('agent_ro',
                  'public.' || quote_ident(c.table_name),c.column_name,'SELECT')
            ORDER BY c.table_name,c.ordinal_position
        """).fetchall()
    found: dict[str, list[str]] = {}
    for table, column in actual:
        found.setdefault(table, []).append(column)
    assert {table: set(columns) for table, columns in found.items()} == {
        table: set(columns) for table, columns in TABLE_COLUMNS.items()
    }


@pytest.mark.parametrize(
    "major,picks,total,rate",
    [
        (5, 2, 4, 50),
        (4, 1, 1, 100),
        (6, 0, 0, None),
    ],
)
def test_metric_document_pick_rate_denominator_and_empty_scope(
    database: dict[str, str],
    major: int,
    picks: int,
    total: int,
    rate: int | None,
) -> None:
    # Execute the reviewed example itself, not a separately maintained copy.
    text = METRICS_PATH.read_text(encoding="utf-8")
    examples = re.findall(r"```sql\n(.*?)```", text, flags=re.DOTALL)
    assert len(examples) == 1
    query = examples[0].replace("g.version_major = 5", f"g.version_major = {major}")
    rows = execute_select(database["agent"], query)
    assert len(rows) == 1
    assert rows[0]["picks"] == picks
    assert rows[0]["total_appearances"] == total
    assert rows[0]["pick_rate"] == rate


def test_name_left_join_preserves_codes_without_names(database: dict[str, str]) -> None:
    rows = execute_select(
        database["agent"],
        """
        SELECT e.item_code,i.name_ko,count(DISTINCT e.participant_id) AS samples
        FROM participant_equipment e LEFT JOIN items i ON i.code=e.item_code
        GROUP BY e.item_code,i.name_ko ORDER BY e.item_code
    """,
    )
    assert [(row["item_code"], row["name_ko"], row["samples"]) for row in rows] == [
        (100001, None, 5),
        (100002, None, 5),
    ]


def test_named_trait_cte_counts_participants_once(database: dict[str, str]) -> None:
    rows = execute_select(
        database["agent"],
        """
        WITH picks AS (
            SELECT t.trait_code,count(DISTINCT t.participant_id) AS samples
            FROM participant_traits t GROUP BY t.trait_code
        )
        SELECT p.trait_code,n.name_ko,p.samples FROM picks p
        LEFT JOIN trait_names n ON n.code=p.trait_code
    """,
    )
    assert rows == [{"trait_code": 123, "name_ko": "합성 이름", "samples": 5}]


def test_context_snapshot_matches_ranked_squad_scope_and_resolves_names(
    database: dict[str, str],
) -> None:
    snapshot = load_context_snapshot(database["agent"], 41)
    assert sum(scope.appearances for scope in snapshot.scopes) == 5
    assert {scope.patch for scope in snapshot.scopes} == {PatchVersion(4, 0), PatchVersion(5, 0)}
    assert {entry.category for entry in snapshot.catalog.entries} == set(NameCategory)
    character = snapshot.catalog.character(2)
    assert character is not None
    result = prepare_context(
        QueryRequest("합성 실험체 승률"),
        QueryIntent(
            character=NameTerm(character.name),
            tier=TierSelection("다이아몬드", 4),
            patch=PatchFilter(PatchMode.EXACT, (PatchVersion(4, 0),)),
        ),
        snapshot,
        "postgres-context",
    )
    assert isinstance(result, ResolvedContext)
    assert result.applied_conditions.conditions.character_code == 2
    assert result.applied_conditions.conditions.patch.versions == (PatchVersion(4, 0),)
    assert result.evidence.sample_size is None
    assert all(scope.data_as_of.utcoffset() is not None for scope in snapshot.scopes)


def test_internal_metadata_pagination_does_not_truncate_item_names(
    database: dict[str, str],
) -> None:
    codes = list(range(990100, 990305))
    with psycopg.connect(database["owner"]) as owner:
        assert (
            owner.execute("SELECT count(*) FROM items WHERE code = ANY(%s)", (codes,)).fetchone()[0]
            == 0
        )
        owner.cursor().executemany(
            "INSERT INTO items(code,name_ko,source,l10n_key,checked_at) "
            "VALUES (%s,%s,'fixture','fixture/pagination',now())",
            [(code, f"페이지 합성 장비 {code}") for code in codes],
        )
    try:
        snapshot = load_context_snapshot(database["agent"], 41)
        found = {
            entry.code for entry in snapshot.catalog.entries if entry.category == NameCategory.ITEM
        }
        assert set(codes) <= found
        last = snapshot.catalog.resolve(NameTerm("페이지 합성 장비 990304", NameCategory.ITEM))
        assert last.candidate.code == 990304
    finally:
        with psycopg.connect(database["owner"]) as owner:
            owner.execute("DELETE FROM items WHERE code = ANY(%s)", (codes,))


def test_context_snapshot_never_uses_another_season_as_a_fallback(database: dict[str, str]) -> None:
    assert load_context_snapshot(database["agent"], 999).scopes == ()


def test_context_snapshot_requires_agent_role(database: dict[str, str]) -> None:
    with pytest.raises(ValueError, match="agent_ro"):
        load_context_snapshot(database["owner"], 41)


@pytest.mark.parametrize(
    "sql",
    [
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
    ],
)
def test_guard_and_database_independently_block_private_access(
    database: dict[str, str], sql: str
) -> None:
    with pytest.raises(SqlGuardError):
        guard_sql(sql)
    with (
        psycopg.connect(database["agent"], autocommit=True) as agent,
        pytest.raises(psycopg.errors.InsufficientPrivilege),
    ):
        agent.execute(sql).fetchall()


def test_samples_and_prompt_never_receive_nicknames(database: dict[str, str]) -> None:
    samples = sample_rows(database["agent"])
    assert set(samples) == set(TABLE_COLUMNS)
    assert all(samples.values())
    payload = json.dumps(samples, ensure_ascii=False, default=str)
    prompt = build_prompt("티어별 집계", samples)
    for nickname in PRIVATE_NICKNAMES:
        assert nickname not in payload and nickname not in prompt
    assert "killDetail" not in payload
    for row in samples["participants"]:
        assert {"nickname", "raw", "is_ranker"}.isdisjoint(row)


def test_tier_cte_includes_below_platinum_and_unknown(database: dict[str, str]) -> None:
    rows = execute_select(
        database["agent"],
        """
        WITH counts AS (
          SELECT tier,count(*) AS samples FROM participants WHERE version_major=5 GROUP BY tier
        ) SELECT tier,samples FROM counts
    """,
    )
    counts = {row["tier"]: row["samples"] for row in rows}
    assert counts == {"플래티넘 미만": 1, "다이아몬드": 1, "미스릴 이상": 1, None: 1}


def test_child_filter_does_not_inflate_samples(database: dict[str, str]) -> None:
    rows = execute_select(
        database["agent"],
        """
        SELECT count(*) AS samples FROM participants p
        WHERE EXISTS (SELECT 1 FROM participant_equipment e
                      WHERE e.participant_id=p.participant_id AND e.kind='FINAL')
    """,
    )
    assert rows == [{"samples": 5}]


@pytest.mark.parametrize(
    "sql",
    [
        "SELECT credit_source->>'credit' AS credit, skill_order[1] AS skill FROM participants",
        "SELECT x.skill FROM participants p CROSS JOIN LATERAL unnest(p.skill_order) AS x(skill)",
        "SELECT string_agg(tier,',') AS tiers FROM participants",
        "SELECT date_trunc('day',start_dtm AT TIME ZONE 'Asia/Seoul') AS day FROM games",
        "SELECT CASE WHEN game_rank=1 THEN 1 ELSE 0 END AS win FROM participants",
    ],
)
def test_allowed_expressions_execute(database: dict[str, str], sql: str) -> None:
    assert execute_select(database["agent"], sql)


@pytest.mark.parametrize("query_name", list(MANUAL_QUERIES))
def test_manual_queries_work_with_v4_privileges(database: dict[str, str], query_name: str) -> None:
    assert execute_select(database["agent"], MANUAL_QUERIES[query_name])


def test_all_allowed_columns_execute(database: dict[str, str]) -> None:
    for table, columns in TABLE_COLUMNS.items():
        query = f"SELECT {', '.join(columns)} FROM {table} LIMIT 1"
        assert execute_select(database["agent"], query)


def test_execution_requires_agent_role(database: dict[str, str]) -> None:
    with pytest.raises(ValueError, match="agent_ro"):
        execute_select(database["owner"], "SELECT count(*) FROM games")


def test_execution_is_capped_to_200(database: dict[str, str]) -> None:
    # More than 200 rows using allowed sources; no fixture bulk inserts needed.
    sql = "SELECT a.game_id FROM games a CROSS JOIN participants b CROSS JOIN participants c CROSS JOIN participants d LIMIT 999"
    assert len(execute_select(database["agent"], sql)) == 200


def test_readonly_account_cannot_write_or_own_schema(database: dict[str, str]) -> None:
    with psycopg.connect(database["agent"], autocommit=True) as agent:
        assert agent.execute("SHOW default_transaction_read_only").fetchone()[0] == "on"
        assert agent.execute("SHOW statement_timeout").fetchone()[0] == "5s"
        with pytest.raises(psycopg.errors.ReadOnlySqlTransaction):
            agent.execute("CREATE TABLE stage4_forbidden(id integer)")


def test_five_second_timeout_and_recovery(database: dict[str, str]) -> None:
    joins = " ".join(f"CROSS JOIN participants p{i}" for i in range(1, 15))
    with pytest.raises(psycopg.errors.QueryCanceled):
        execute_select(database["agent"], f"SELECT count(*) FROM participants p0 {joins}")
    assert execute_select(database["agent"], "SELECT count(*) AS n FROM participants") == [{"n": 5}]


# Stage 7-3 uses a fake model and the real agent_ro execution boundary.


def test_stage7_generated_pick_rate_keeps_all_character_denominator(
    database: dict[str, str],
    tmp_path: Path,
    fake_client: MagicMock,
    generation_text: Callable[..., str],
) -> None:
    sql = """
        SELECT count(*) FILTER (WHERE character_num = %(character_code)s) AS samples,
               count(*) AS denominator,
               100.0 * count(*) FILTER (WHERE character_num = %(character_code)s)
                     / NULLIF(count(*),0) AS pick_rate,
               100.0 * count(*) FILTER (WHERE character_num = %(character_code)s AND game_rank=1)
                     / NULLIF(count(*) FILTER (WHERE character_num = %(character_code)s),0) AS win_rate
        FROM participants
    """
    fake_client.models.generate_content.return_value = SimpleNamespace(
        text=generation_text(sql), usage_metadata=None
    )
    request = QueryRequest("픽률과 승률", QueryConditions(character_code=1))
    result = run_query(
        request, Settings(database["agent"], "fake-key", "fake-model"), tmp_path / "log.jsonl"
    )
    assert result.kind == ResponseKind.ANSWER
    assert result.rows[0]["samples"] == 2 and result.rows[0]["denominator"] == 4
    assert result.rows[0]["pick_rate"] == result.rows[0]["win_rate"] == 50
    assert result.applied_conditions.conditions.patch.versions == (PatchVersion(5, 0),)
    prompt = fake_client.models.generate_content.call_args.kwargs["contents"]
    assert all(nickname not in prompt for nickname in PRIVATE_NICKNAMES)


@pytest.mark.parametrize(
    "tier,expected", [(None, 4), ("다이아몬드", 1), ("미확인", 1), ("플래티넘 미만", 1)]
)
def test_stage7_applied_tier_changes_results(
    database: dict[str, str],
    tmp_path: Path,
    fake_client: MagicMock,
    generation_text: Callable[..., str],
    tier: str | None,
    expected: int,
) -> None:
    fake_client.models.generate_content.return_value = SimpleNamespace(
        text=generation_text(), usage_metadata=None
    )
    result = run_query(
        QueryRequest("참가자 수", QueryConditions(tier=tier)),
        Settings(database["agent"], "fake-key", "fake-model"),
        tmp_path / "log.jsonl",
    )
    assert result.kind == ResponseKind.ANSWER
    assert result.rows == ({"samples": expected},)
    assert result.applied_conditions.conditions.tier == tier


@pytest.mark.parametrize(
    "table,expected", [("participant_equipment", 8), ("participant_traits", 4)]
)
def test_stage7_direct_child_source_is_scoped_to_latest_patch(
    database: dict[str, str],
    tmp_path: Path,
    fake_client: MagicMock,
    generation_text: Callable[..., str],
    table: str,
    expected: int,
) -> None:
    sql = f"SELECT count(*) AS samples FROM {table}"
    fake_client.models.generate_content.return_value = SimpleNamespace(
        text=generation_text(sql), usage_metadata=None
    )
    result = run_query(
        QueryRequest("표본 수"),
        Settings(database["agent"], "fake-key", "fake-model"),
        tmp_path / "log.jsonl",
    )
    assert result.kind == ResponseKind.ANSWER and result.rows == ({"samples": expected},)


@pytest.mark.parametrize(
    "first", ["DELETE FROM participants", "SELECT count(*)/0 AS samples FROM participants"]
)
def test_stage7_one_repair_recovers_guard_or_database_error(
    database: dict[str, str],
    tmp_path: Path,
    fake_client: MagicMock,
    generation_text: Callable[..., str],
    first: str,
) -> None:
    fake_client.models.generate_content.side_effect = [
        SimpleNamespace(text=generation_text(first), usage_metadata=None),
        SimpleNamespace(text="SELECT count(*) AS samples FROM participants", usage_metadata=None),
    ]
    path = tmp_path / "log.jsonl"
    result = run_query(
        QueryRequest("표본 수"), Settings(database["agent"], "fake-key", "fake-model"), path
    )
    assert result.kind == ResponseKind.ANSWER and result.rows == ({"samples": 4},)
    assert fake_client.models.generate_content.call_count == 2
    attempts = [
        json.loads(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if json.loads(line)["event"] == "sql_attempt"
    ]
    assert (
        len(attempts) == 2
        and not attempts[0]["execution_succeeded"]
        and attempts[1]["execution_succeeded"]
    )


def test_stage7_question_patch_overrides_selection_and_period_is_bound(
    database: dict[str, str],
    tmp_path: Path,
    fake_client: MagicMock,
    generation_text: Callable[..., str],
) -> None:
    fake_client.models.generate_content.return_value = SimpleNamespace(
        text=generation_text(
            patch={"mode": "exact", "versions": [{"major": 4, "minor": 0}]},
            period={"start": "2026-09-30T00:00:00+09:00", "end": "2026-10-01T00:00:00+09:00"},
        ),
        usage_metadata=None,
    )
    result = run_query(
        QueryRequest("4.0 패치 9월 30일의 참가자 수"),
        Settings(database["agent"], "fake-key", "fake-model"),
        tmp_path / "log.jsonl",
    )
    assert result.kind == ResponseKind.ANSWER and result.rows == ({"samples": 1},)
    assert result.applied_conditions.conditions.patch.versions == (PatchVersion(4, 0),)


def test_stage7_parameterized_sql_preserves_percent_literals_and_modulo(
    database: dict[str, str],
) -> None:
    snapshot = load_context_snapshot(database["agent"], 41)
    context = prepare_context(QueryRequest("질문"), QueryIntent(), snapshot, "test")
    assert isinstance(context, ResolvedContext)
    scoped = scope_sql("SELECT '50% %(fake)s' AS label, 5 % 2 AS remainder FROM games", context)
    assert execute_select(database["agent"], scoped.sql, scoped.parameters) == [
        {"label": "50% %(fake)s", "remainder": 1}
    ]


def test_stage7_official_metadata_name_binding_is_locally_resolved(
    database: dict[str, str],
    tmp_path: Path,
    fake_client: MagicMock,
    generation_text: Callable[..., str],
) -> None:
    sql = """SELECT count(*) AS samples FROM participants p JOIN characters c
             ON c.character_code=p.character_num WHERE c.character_code=%(name_character_1)s"""
    fake_client.models.generate_content.return_value = SimpleNamespace(
        text=generation_text(sql, names=[{"text": "합성 실험체 A", "category": "character"}]),
        usage_metadata=None,
    )
    result = run_query(
        QueryRequest("합성 실험체 A의 출현 수"),
        Settings(database["agent"], "fake-key", "fake-model"),
        tmp_path / "log.jsonl",
    )
    assert result.kind == ResponseKind.ANSWER and result.rows == ({"samples": 2},)


def test_stage7_second_failure_does_not_execute_unsafe_repair(
    database: dict[str, str],
    tmp_path: Path,
    fake_client: MagicMock,
    generation_text: Callable[..., str],
) -> None:
    fake_client.models.generate_content.side_effect = [
        SimpleNamespace(
            text=generation_text("SELECT count(*)/0 AS samples FROM participants"),
            usage_metadata=None,
        ),
        SimpleNamespace(text="DELETE FROM participants", usage_metadata=None),
    ]
    result = run_query(
        QueryRequest("표본 수"),
        Settings(database["agent"], "fake-key", "fake-model"),
        tmp_path / "log.jsonl",
    )
    assert result.kind == ResponseKind.NOTICE and result.sql is None and not result.rows
    assert fake_client.models.generate_content.call_count == 2
    assert execute_select(database["agent"], "SELECT count(*) AS samples FROM participants") == [
        {"samples": 5}
    ]
