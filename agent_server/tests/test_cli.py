from __future__ import annotations

import json
from unittest.mock import patch

import pytest

from er_agent.bootstrap import run_manual
from er_agent.cli import main
from er_agent.models.names import NameClarification
from er_agent.models.query import (
    AppliedConditions,
    PatchFilter,
    PatchMode,
    PatchVersion,
    QueryConditions,
    QueryRequest,
    QueryResponse,
    ResponseKind,
)
from er_agent.settings import Settings

SETTINGS = Settings("test-url", "test-key", "test-model")


def test_ask_cli_prints_complete_response(capsys: pytest.CaptureFixture[str]) -> None:
    response = QueryResponse(
        ResponseKind.ANSWER,
        "질문",
        "test-log",
        0,
        applied_conditions=AppliedConditions(
            41, QueryConditions(patch=PatchFilter(PatchMode.EXACT, (PatchVersion(5, 0),)))
        ),
        sql="SELECT count(*) AS samples FROM participants",
        columns=("samples",),
        rows=({"samples": 4},),
        summary="표본은 4건입니다.",
    )
    with (
        patch("sys.argv", ["er-agent", "ask", "질문"]),
        patch("er_agent.cli.Settings.from_env", return_value=SETTINGS),
        patch("er_agent.cli.run_query", return_value=response) as query,
    ):
        main()
    query.assert_called_once_with(QueryRequest("질문"), SETTINGS)
    assert json.loads(capsys.readouterr().out) == response.to_dict()


@pytest.mark.parametrize("kind", [ResponseKind.NOTICE, ResponseKind.CLARIFICATION])
def test_non_answer_cli_prints_json_and_exit_code(
    capsys: pytest.CaptureFixture[str], kind: ResponseKind
) -> None:
    response = QueryResponse(
        kind,
        "질문",
        "test-log",
        0,
        message="정식 명칭을 확인해 주세요.",
        clarifications=(NameClarification("미확인"),) if kind == ResponseKind.CLARIFICATION else (),
    )
    with (
        patch("sys.argv", ["er-agent", "ask", "질문"]),
        patch("er_agent.cli.Settings.from_env", return_value=SETTINGS),
        patch("er_agent.cli.run_query", return_value=response),
        pytest.raises(SystemExit) as failure,
    ):
        main()
    assert failure.value.code == 1
    output = capsys.readouterr()
    assert json.loads(output.out) == response.to_dict()
    assert not output.err


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
        patch("er_agent.cli.run_query") as query,
        patch("er_agent.cli.run_manual") as manual,
    ):
        main()
    query.assert_not_called()
    manual.assert_not_called()
    assert "LIMIT 200" in json.loads(capsys.readouterr().out)["sql"]
