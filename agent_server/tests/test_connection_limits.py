from __future__ import annotations

from unittest.mock import MagicMock, patch

from er_agent.database import connection
from er_agent.models.limits import SQL_TIMEOUT_SECONDS


def test_analytics_session_sets_the_shared_statement_timeout() -> None:
    session = MagicMock()
    session.__enter__.return_value = session
    session.execute.return_value.fetchone.return_value = {"role": "agent_ro"}
    with (
        patch.object(connection.psycopg, "connect", return_value=session),
        connection._analytics_connection("url"),
    ):
        pass
    statements = [call.args[0] for call in session.execute.call_args_list]
    assert SQL_TIMEOUT_SECONDS == 20
    assert "SET LOCAL statement_timeout = '20s'" in statements
    assert "SET TRANSACTION READ ONLY" in statements
