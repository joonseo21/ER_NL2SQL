from datetime import UTC, datetime
from unittest.mock import MagicMock, patch

from er_agent.database.context_repository import _load_scopes
from er_agent.models.query import PatchVersion


def test_scope_aggregation_paginates_each_patch_separately() -> None:
    day = datetime(2026, 10, 30, tzinfo=UTC)

    def scope(major: int, tier: str, count: int) -> dict[str, object]:
        return {
            "version_major": major,
            "version_minor": 0,
            "tier": tier,
            "tier_division": 0,
            "appearances": count,
            "first_match": day,
            "last_match": day,
            "data_as_of": day,
        }

    connection = MagicMock()
    connection.execute.return_value.fetchall.side_effect = [
        [{"version_major": 4, "version_minor": 0}, {"version_major": 5, "version_minor": 0}],
        [scope(4, "A", 2), scope(4, "B", 3)],
        [],
        [scope(5, "C", 5)],
    ]
    with patch("er_agent.database.context_repository.MAX_ROWS", 2):
        scopes = _load_scopes(connection, 41)

    assert [scope.patch for scope in scopes] == [
        PatchVersion(4, 0),
        PatchVersion(4, 0),
        PatchVersion(5, 0),
    ]
    assert [scope.appearances for scope in scopes] == [2, 3, 5]
    assert connection.execute.call_args_list[0].args[0] == "SET LOCAL enable_hashjoin = off"
    assert connection.execute.call_args_list[1].args[0] == "SET LOCAL enable_mergejoin = off"
    assert connection.execute.call_args_list[3].args[1] == (41, 4, 0, 2, 0)
    assert connection.execute.call_args_list[4].args[1] == (41, 4, 0, 2, 2)
    assert connection.execute.call_args_list[5].args[1] == (41, 5, 0, 2, 0)
