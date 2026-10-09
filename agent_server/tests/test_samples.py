from unittest.mock import patch

from er_agent.cli import MANUAL_QUERIES
from er_agent.database import sample_rows
from er_agent.guard import guard_sql
from er_agent.prompt import build_prompt
from er_agent.query_schema import BLOCKED_COLUMNS, TABLE_COLUMNS


def test_samples_only_request_public_contract_columns() -> None:
    queries: list[str] = []

    def select(_database_url: str, query: str) -> list[dict]:
        queries.append(guard_sql(query).sql)
        return []

    with patch("er_agent.database.execute_select", side_effect=select):
        samples = sample_rows("unused")
    assert set(samples) == set(TABLE_COLUMNS)
    assert len(queries) == len(TABLE_COLUMNS)
    for query in queries:
        assert "nickname" not in query and '"raw"' not in query and "is_ranker" not in query


def test_context_describes_v4_access_and_approved_tier_policy() -> None:
    prompt = build_prompt("티어별 집계", {"participants": [{"user_id": 123, "tier": "플래티넘 미만"}]})
    assert "Non-identifying sample rows" in prompt
    assert "Both remain in aggregates without a tier condition" in prompt
    assert "Non-recursive WITH" in prompt
    assert "Player nicknames" in prompt
    for table, columns in TABLE_COLUMNS.items():
        assert f"## `{table}`" in prompt
        assert all(f"`{column}`" in prompt for column in columns)
    assert not (set(TABLE_COLUMNS["participants"]) & BLOCKED_COLUMNS)


def test_manual_queries_use_v4_and_pass_guard() -> None:
    assert "ranker-average-rank" not in MANUAL_QUERIES
    assert "tier-average-rank" in MANUAL_QUERIES
    for sql in MANUAL_QUERIES.values():
        guard_sql(sql)
