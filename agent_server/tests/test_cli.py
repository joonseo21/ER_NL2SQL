from __future__ import annotations

import json
from unittest.mock import patch

import pytest

from er_agent.bootstrap import QueryFailed, run_manual
from er_agent.cli import main
from er_agent.settings import Settings

SETTINGS = Settings("test-url", "test-key", "test-model")


def test_ask_cli_still_prints_row_array(capsys: pytest.CaptureFixture[str]) -> None:
    with (
        patch("sys.argv", ["er-agent", "ask", "질문"]),
        patch("er_agent.cli.Settings.from_env", return_value=SETTINGS),
        patch("er_agent.cli.run_question", return_value=[{"samples": 4}]) as query,
    ):
        main()
    query.assert_called_once_with("질문", SETTINGS)
    assert json.loads(capsys.readouterr().out) == [{"samples": 4}]


def test_notice_cli_keeps_stderr_and_exit_code(capsys: pytest.CaptureFixture[str]) -> None:
    with (
        patch("sys.argv", ["er-agent", "ask", "질문"]),
        patch("er_agent.cli.Settings.from_env", return_value=SETTINGS),
        patch("er_agent.cli.run_question", side_effect=QueryFailed("정식 명칭을 확인해 주세요.")),
        pytest.raises(SystemExit) as failure,
    ):
        main()
    assert failure.value.code == 1
    output = capsys.readouterr()
    assert not output.out
    assert output.err.strip() == "정식 명칭을 확인해 주세요."


def test_manual_cli_delegates_execution(capsys: pytest.CaptureFixture[str]) -> None:
    with (
        patch("sys.argv", ["er-agent", "manual", "game-count"]),
        patch("er_agent.cli.Settings.from_env", return_value=SETTINGS),
        patch("er_agent.cli.run_manual", return_value=[{"game_count": 2}]) as manual,
    ):
        main()
    manual.assert_called_once_with("game-count", SETTINGS)
    assert json.loads(capsys.readouterr().out) == [{"game_count": 2}]


def test_manual_bootstrap_uses_fixed_query() -> None:
    with patch("er_agent.bootstrap.execute_select", return_value=[]) as execute:
        assert run_manual("game-count", SETTINGS) == []
    execute.assert_called_once_with("test-url", "SELECT count(*) AS game_count FROM games")


def test_guard_cli_uses_no_execution_adapter(capsys: pytest.CaptureFixture[str]) -> None:
    with (
        patch("sys.argv", ["er-agent", "guard", "SELECT game_id FROM games"]),
        patch("er_agent.cli.Settings.from_env", return_value=SETTINGS),
        patch("er_agent.cli.run_question") as query,
        patch("er_agent.cli.run_manual") as manual,
    ):
        main()
    query.assert_not_called()
    manual.assert_not_called()
    assert "LIMIT 200" in json.loads(capsys.readouterr().out)["sql"]
