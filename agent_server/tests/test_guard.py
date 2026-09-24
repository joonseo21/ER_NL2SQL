import pytest

from er_agent.guard import SqlGuardError, guard_sql


def test_adds_limit_to_safe_select() -> None:
    guarded = guard_sql("SELECT game_id FROM games")
    assert guarded.sql.endswith("LIMIT 200")
    assert guarded.limit_added is True


def test_keeps_existing_limit() -> None:
    guarded = guard_sql("```sql\nSELECT * FROM participants LIMIT 10;\n```")
    assert guarded.sql == "SELECT * FROM participants LIMIT 10"
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
        "WITH rows AS (SELECT * FROM games) SELECT * FROM rows",
        "SELECT * FROM games -- unsafe comment",
    ],
)
def test_rejects_unsafe_sql(sql: str) -> None:
    with pytest.raises(SqlGuardError):
        guard_sql(sql)


def test_caps_excessive_limit() -> None:
    guarded = guard_sql("SELECT * FROM games LIMIT 1000")
    assert guarded.sql.endswith("LIMIT 200")
    assert "guarded_query" in guarded.sql
