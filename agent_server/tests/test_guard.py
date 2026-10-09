import pytest
from sqlglot import exp, parse_one

from er_agent.guard import SqlGuardError, guard_sql


def test_adds_limit_to_safe_select() -> None:
    guarded = guard_sql("SELECT game_id FROM games")
    assert guarded.sql.endswith("LIMIT 200")
    assert guarded.limit_added is True


def test_keeps_existing_limit() -> None:
    guarded = guard_sql("```sql\nSELECT participant_id FROM participants LIMIT 10;\n```")
    assert int(parse_one(guarded.sql, dialect="postgres").args["limit"].expression.this) == 10
    assert guarded.limit_added is False


@pytest.mark.parametrize(
    "sql",
    [
        "DELETE FROM games",
        "SELECT * FROM games; DROP TABLE games",
        "SELECT * FROM collect_queue",
        'SELECT * FROM "collect_queue"',
        "SELECT games.game_id FROM games, collect_queue",
        "SELECT * FROM pg_catalog.pg_tables",
        "WITH RECURSIVE rows AS (SELECT game_id FROM games) SELECT game_id FROM rows",
        "SELECT * FROM games -- unsafe comment",
    ],
)
def test_rejects_unsafe_sql(sql: str) -> None:
    with pytest.raises(SqlGuardError):
        guard_sql(sql)


def test_caps_excessive_limit() -> None:
    guarded = guard_sql("SELECT game_id FROM games LIMIT 1000")
    assert guarded.sql.endswith("LIMIT 200")
    assert guarded.limit_added is True


@pytest.mark.parametrize("sql", [
    "SELECT nickname AS hidden FROM participants",
    "SELECT p.nickname FROM participants p",
    'SELECT p."nickname" FROM participants p',
    "SELECT raw ->> 'killDetail' AS hidden FROM participants",
    "SELECT is_ranker FROM participants",
    "SELECT lower(nickname) FROM participants",
    "SELECT concat('hidden', nickname) FROM participants",
    "SELECT count(*) FROM participants WHERE nickname = 'sample'",
    "SELECT participant_id FROM participants ORDER BY nickname",
    "SELECT count(*) FROM participants p JOIN participants q ON p.nickname=q.nickname",
    "SELECT (SELECT nickname FROM participants LIMIT 1) AS hidden",
    "WITH x AS (SELECT nickname AS id FROM participants) SELECT id FROM x",
    "WITH x AS (SELECT raw AS r FROM participants) SELECT r FROM x",
    "WITH x AS (SELECT user_id FROM users) SELECT user_id FROM x",
    "WITH participants AS (SELECT nickname FROM users) SELECT nickname FROM participants",
    "SELECT nickname FROM rankers",
    "SELECT uid FROM rankers",
    "SELECT user_id FROM users",
    "SELECT id FROM api_call_log",
    "SELECT installed_rank FROM flyway_schema_history",
    "SELECT table_name FROM information_schema.tables",
    "SELECT relname FROM pg_catalog.pg_class",
    "SELECT game_id FROM other_schema.games",
    "SELECT * FROM participants",
    "SELECT p.* FROM participants p",
    "SELECT p FROM participants p",
    "SELECT row_to_json(p) FROM participants p",
    "SELECT to_jsonb(p) FROM participants p",
    "SELECT to_jsonb(p.*) FROM participants p",
    "SELECT p::text FROM participants p",
    "SELECT p.b FROM participants AS p(a,b)",
    "SELECT p.game_id FROM participants p NATURAL JOIN participants q",
    "SELECT count(*) FROM participants p JOIN participants q USING(nickname)",
    "SELECT (p).nickname FROM participants p",
    "SELECT tableoid FROM participants",
    "SELECT ctid FROM participants",
    "SELECT pg_sleep(10)",
    "SELECT current_setting('data_directory')",
    "SELECT set_config('statement_timeout','0',true)",
    "SELECT query_to_xml('SELECT nickname FROM participants',true,false,'')",
    "SELECT pg_catalog.sum(game_rank) FROM participants",
    "SELECT made_up_function(game_rank) FROM participants",
    "SELECT game_id INTO another_table FROM games",
    "SELECT game_id FROM games FOR UPDATE",
    "WITH changed AS (DELETE FROM games RETURNING game_id) SELECT game_id FROM changed",
    "WITH changed AS (UPDATE games SET season_id=1 RETURNING game_id) SELECT game_id FROM changed",
    "WITH changed AS (INSERT INTO games(game_id) VALUES (1) RETURNING game_id) SELECT game_id FROM changed",
    "WITH x AS (WITH RECURSIVE y AS (SELECT game_id FROM games) SELECT game_id FROM y) SELECT game_id FROM x",
    "SELECT unknown_column FROM participants",
    "SELECT game_id FROM games LIMIT -1",
    "SELECT game_id FROM games LIMIT (SELECT 1)",
    "SELECT game_id FROM games LIMIT 1.5",
])
def test_blocks_private_columns_and_bypasses(sql: str) -> None:
    with pytest.raises(SqlGuardError):
        guard_sql(sql)


@pytest.mark.parametrize("sql", [
    "WITH picks AS (SELECT tier, COUNT(*) AS n FROM participants GROUP BY tier) SELECT tier,n FROM picks",
    "WITH x(id) AS (SELECT participant_id FROM participants), y AS (SELECT id FROM x) SELECT id FROM y",
    "WITH participants AS (SELECT game_id FROM games) SELECT game_id FROM participants",
    "SELECT p.participant_id, e.item_code FROM participants p JOIN participant_equipment e ON e.participant_id=p.participant_id",
    "SELECT p.tier, count(*) n FROM participants p WHERE EXISTS (SELECT 1 FROM participant_traits t WHERE t.participant_id=p.participant_id AND t.trait_code=123) GROUP BY p.tier",
    "SELECT participant_id, skill_order, credit_source FROM participants",
    "SELECT skill_order[1], credit_source->>'credit' FROM participants",
    "SELECT CASE WHEN game_rank=1 THEN 1 ELSE 0 END AS win FROM participants",
    "SELECT date_trunc('day',start_dtm) AS day FROM games",
    "SELECT count(DISTINCT participant_id) AS samples FROM participant_equipment",
    "SELECT count(*) FROM public.participants",
    "SELECT COUNT(*) FROM PARTICIPANTS",
    "SELECT user_id AS anonymous_id FROM participants",
    "SELECT game_id, ROW_NUMBER() OVER (ORDER BY game_id) AS n FROM games",
    "SELECT tier, count(*) n FROM participants GROUP BY tier HAVING count(*) > 0 ORDER BY n",
    "SELECT x.value FROM participants p CROSS JOIN LATERAL UNNEST(p.skill_order) AS x(value)",
    "SELECT ROUND(100.0 * COUNT(*) FILTER (WHERE game_rank=1) / NULLIF(COUNT(*),0),2) AS win_rate FROM participants",
    "SELECT (start_dtm AT TIME ZONE 'Asia/Seoul')::date AS date_kst FROM games",
    "SELECT jsonb_build_object('user_id',user_id,'tier',tier) AS data FROM participants",
    "SELECT string_agg(tier,',') FROM participants",
    "SELECT 'literal; not another statement' AS note",
    "SELECT game_id FROM games LIMIT 0",
    "SELECT game_id FROM games LIMIT ALL",
])
def test_allows_safe_v4_analytics(sql: str) -> None:
    guarded = guard_sql(sql)
    statement = parse_one(guarded.sql, dialect="postgres")
    assert isinstance(statement, exp.Select)
    assert int(statement.args["limit"].expression.this) <= 200
    assert guard_sql(guarded.sql).sql == guarded.sql


@pytest.mark.parametrize("max_rows", [0, -1, 201, True, 1.5])
def test_invalid_row_caps_are_rejected(max_rows: int) -> None:
    with pytest.raises(SqlGuardError):
        guard_sql("SELECT game_id FROM games", max_rows=max_rows)


def test_cte_limit_applies_to_final_result() -> None:
    guarded = guard_sql("WITH x AS (SELECT game_id FROM games LIMIT 1000) SELECT game_id FROM x")
    parsed = parse_one(guarded.sql, dialect="postgres")
    assert int(parsed.args["limit"].expression.this) == 200
    assert int(parsed.args["with_"].expressions[0].this.args["limit"].expression.this) == 1000
