from __future__ import annotations

import argparse
import json
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest
from evaluations import run
from evaluations.report import read_metrics

from er_agent.models.query import QueryRequest
from er_agent.settings import Settings

SETTINGS = Settings("production-url", "secret-key", "model")


def arguments(cases: Path, **changes: object) -> argparse.Namespace:
    values = {
        "target": "production",
        "snapshot_id": None,
        "expected_database": "production",
        "cases": cases,
        "allow_live_gemini": True,
    }
    values.update(changes)
    return argparse.Namespace(**values)


@pytest.fixture
def cases(tmp_path: Path) -> Path:
    path = tmp_path / "cases.json"
    path.write_text(json.dumps([{"id": "one", "question": "질문"}]), encoding="utf-8")
    return path


def test_live_requires_opt_in_before_any_settings_or_network(cases: Path) -> None:
    with patch.object(run, "selected_settings") as settings:
        with pytest.raises(ValueError, match="allow-live-gemini"):
            run.run_batch(arguments(cases, allow_live_gemini=False))
        settings.assert_not_called()


@pytest.mark.parametrize("url", ["", "production-url"])
def test_snapshot_never_falls_back_to_production(url: str) -> None:
    with (
        patch.object(run.Settings, "from_env", return_value=SETTINGS),
        patch.dict("os.environ", {"AGENT_EVAL_DATABASE_URL": url}),
        pytest.raises(ValueError),
    ):
        run.selected_settings("snapshot", "snapshot-1")


def test_snapshot_selects_separate_url_and_keeps_model() -> None:
    with (
        patch.object(run.Settings, "from_env", return_value=SETTINGS),
        patch.dict("os.environ", {"AGENT_EVAL_DATABASE_URL": "copy-url"}),
    ):
        settings = run.selected_settings("snapshot", "snapshot-1")
    assert settings.database_url == "copy-url"
    assert settings.gemini_api_key == SETTINGS.gemini_api_key
    assert settings.gemini_model == SETTINGS.gemini_model


@pytest.mark.parametrize(
    "target,snapshot_id", [("snapshot", None), ("snapshot", " "), ("production", "copy")]
)
def test_snapshot_metadata_does_not_claim_production_is_fixed(
    target: str, snapshot_id: str | None
) -> None:
    with patch.object(run.Settings, "from_env", return_value=SETTINGS), pytest.raises(ValueError):
        run.selected_settings(target, snapshot_id)


@pytest.mark.parametrize(
    "values",
    [
        [],
        {},
        [{"id": "x", "question": "질문"}] * 2,
        [{"id": "x", "question": ""}],
        [{"id": "x", "question": "질문", "sql": "SELECT 1"}],
        [{"id": str(index), "question": "질문"} for index in range(run.MAX_CASES + 1)],
    ],
)
def test_cases_are_validated_before_live_execution(tmp_path: Path, values: object) -> None:
    path = tmp_path / "cases.json"
    path.write_text(json.dumps(values), encoding="utf-8")
    with pytest.raises(ValueError):
        run.load_cases(path)


def test_preflight_checks_database_before_v5_context() -> None:
    connection = MagicMock()
    connection.execute.return_value.fetchone.return_value = {"database": "wrong"}
    with (
        patch.object(run, "_analytics_connection") as session,
        patch.object(run, "load_context_snapshot") as context,
    ):
        session.return_value.__enter__.return_value = connection
        with pytest.raises(ValueError):
            run.preflight(SETTINGS, "production")
    context.assert_not_called()


def test_preflight_checks_v5_and_reports_only_public_metadata(query_snapshot: object) -> None:
    connection = MagicMock()
    connection.execute.return_value.fetchone.return_value = {"database": "production"}
    with (
        patch.object(run, "_analytics_connection") as session,
        patch.object(run, "load_context_snapshot", return_value=query_snapshot) as context,
    ):
        session.return_value.__enter__.return_value = connection
        result = run.preflight(SETTINGS, "production")
    context.assert_called_once_with(SETTINGS.database_url, SETTINGS.season_id)
    assert result["v5_name_sources_readable"] is True
    assert result["participant_appearances"] == 30
    assert "production-url" not in json.dumps(result)


def test_preflight_failure_never_calls_gemini_and_never_prints_secrets(
    cases: Path, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    with (
        patch.object(run, "EVALUATION_ROOT", tmp_path),
        patch.object(run, "selected_settings", return_value=SETTINGS),
        patch.object(run, "git_revision", return_value="revision"),
        patch.object(run, "preflight", side_effect=RuntimeError("secret-key production-url")),
        patch.object(run, "run_query") as query,
    ):
        assert run.run_batch(arguments(cases)) == 2
    query.assert_not_called()
    manifest = next((tmp_path / "results").glob("*/manifest.json")).read_text(encoding="utf-8")
    assert "secret-key" not in manifest + capsys.readouterr().out
    assert "production-url" not in manifest
    assert json.loads(manifest)["preflight_error_category"] == "RuntimeError"


def test_observation_error_is_redacted(tmp_path: Path) -> None:
    with patch.object(run, "run_query", side_effect=RuntimeError("secret-key production-url")):
        result = run.observe_case(QueryRequest("질문"), SETTINGS, tmp_path / "absent.jsonl")
    assert result["error_category"] == "RuntimeError"
    assert "secret-key" not in json.dumps(result)
    assert result["accuracy"] is None
    assert result["logging_complete"] is False


def test_missing_usage_is_unknown_and_retries_are_counted(tmp_path: Path) -> None:
    path = tmp_path / "events.jsonl"
    records = [
        {
            "event": "llm_attempt",
            "elapsed_ms": 10,
            "succeeded": False,
            "retry_scheduled": True,
            "tokens": {},
        },
        {
            "event": "llm_attempt",
            "elapsed_ms": 20,
            "succeeded": True,
            "retry_scheduled": False,
            "tokens": {"total_token_count": 7},
        },
        {"event": "sql_attempt", "elapsed_ms": 4, "error": "database: SQLSTATE 57014"},
        {"event": "sql_attempt", "elapsed_ms": 5, "error": None},
        {"event": "query_end", "logical_calls": 1, "physical_calls": 2},
    ]
    path.write_text("\n".join(json.dumps(item) for item in records), encoding="utf-8")
    result = read_metrics(path)
    assert result["tokens"]["total_token_count"] is None
    assert result["physical_calls"] == 2
    assert result["sql_repaired"] is True
    assert result["exhausted_llm_call"] is False
    assert result["sql_stage_ms"] == [4, 5]
    assert result["sql_timeouts"] == 1


@pytest.mark.parametrize("exhausted", [False, True])
def test_batch_records_without_calling_it_accuracy_and_stops_on_api_failure(
    tmp_path: Path, exhausted: bool
) -> None:
    path = tmp_path / "cases.json"
    path.write_text(
        json.dumps([{"id": "one", "question": "질문1"}, {"id": "two", "question": "질문2"}]),
        encoding="utf-8",
    )
    observation = {
        "response": {"kind": "notice"},
        "accuracy": None,
        "error_category": None,
        "exhausted_llm_call": exhausted,
    }
    with (
        patch.object(run, "EVALUATION_ROOT", tmp_path),
        patch.object(run, "selected_settings", return_value=SETTINGS),
        patch.object(run, "git_revision", return_value="revision"),
        patch.object(run, "preflight", return_value={"database": "production"}),
        patch.object(run, "observe_case", return_value=observation) as observe,
    ):
        assert run.run_batch(arguments(path)) == (2 if exhausted else 0)
    assert observe.call_count == (1 if exhausted else 2)
    report = json.loads(next((tmp_path / "results").glob("*/observations.json")).read_text())
    assert all(item["accuracy"] is None for item in report)


def test_observation_preserves_answer_with_failed_summary(tmp_path: Path) -> None:
    response = {"kind": "answer", "summary": None, "sql": "SELECT 1", "rows": [{"x": 1}]}
    query = MagicMock()
    query.to_dict.return_value = response
    with patch.object(run, "run_query", return_value=query):
        result = run.observe_case(QueryRequest("질문"), SETTINGS, tmp_path / "absent.jsonl")
    assert result["response"] == response
    assert result["review_status"] == "not_reviewed"


def two_case_file(tmp_path: Path) -> Path:
    tmp_path.mkdir(parents=True, exist_ok=True)
    path = tmp_path / "two.json"
    path.write_text(
        json.dumps([{"id": "one", "question": "질문1"}, {"id": "two", "question": "질문2"}]),
        encoding="utf-8",
    )
    return path


def run_with_observations(
    tmp_path: Path, observations: list[dict[str, object]], **changes: object
) -> tuple[int, MagicMock, dict, list[dict]]:
    with (
        patch.object(run, "EVALUATION_ROOT", tmp_path),
        patch.object(run, "selected_settings", return_value=SETTINGS),
        patch.object(run, "git_revision", return_value="revision"),
        patch.object(run, "preflight", return_value={"database": "production"}),
        patch.object(run, "observe_case", side_effect=observations) as observe,
    ):
        code = run.run_batch(arguments(two_case_file(tmp_path), **changes))
    manifest = json.loads(next((tmp_path / "results").glob("*/manifest.json")).read_text())
    report = json.loads(next((tmp_path / "results").glob("*/observations.json")).read_text())
    return code, observe, manifest, report


def good() -> dict[str, object]:
    return {
        "response": {"kind": "answer"},
        "accuracy": None,
        "error_category": None,
        "exhausted_llm_call": False,
    }


def failed() -> dict[str, object]:
    return {**good(), "response": None, "error_category": "RuntimeError"}


def test_repetitions_are_the_outer_loop_and_configuration_is_recorded(tmp_path: Path) -> None:
    code, observe, manifest, report = run_with_observations(
        tmp_path,
        [good() for _ in range(4)],
        repeat=2,
        timeout_seconds=90,
        thinking_level="low",
    )
    assert code == 0 and observe.call_count == 4
    assert [(item["id"], item["repetition"]) for item in report] == [
        ("one", 1),
        ("two", 1),
        ("one", 2),
        ("two", 2),
    ]
    assert manifest["repeat"] == 2
    assert manifest["gemini"] == {"timeout_seconds": 90, "thinking_level": "low"}


def test_default_configuration_is_recorded(tmp_path: Path) -> None:
    _, _, manifest, _ = run_with_observations(tmp_path, [good(), good()])
    assert manifest["gemini"] == {"timeout_seconds": 30, "thinking_level": "low"}
    assert manifest["repeat"] == 1 and manifest["continue_on_failure"] is False


def test_failure_stops_by_default_and_continue_flag_runs_the_rest(tmp_path: Path) -> None:
    code, observe, _, report = run_with_observations(tmp_path, [failed(), good(), good(), good()])
    assert code == 2 and observe.call_count == 1 and len(report) == 1
    code, observe, manifest, report = run_with_observations(
        tmp_path / "continue",
        [failed(), good(), good(), good()],
        repeat=2,
        continue_on_failure=True,
    )
    assert code == 2 and observe.call_count == 4 and len(report) == 4
    assert manifest["continue_on_failure"] is True


def test_preflight_only_needs_no_opt_in_and_never_calls_gemini(tmp_path: Path) -> None:
    with (
        patch.object(run, "EVALUATION_ROOT", tmp_path),
        patch.object(run, "selected_settings", return_value=SETTINGS),
        patch.object(run, "git_revision", return_value="revision"),
        patch.object(run, "preflight", return_value={"database": "production"}),
        patch.object(run, "run_query") as query,
    ):
        code = run.run_batch(
            arguments(two_case_file(tmp_path), allow_live_gemini=False, preflight_only=True)
        )
    assert code == 0
    query.assert_not_called()
    manifest = json.loads(next((tmp_path / "results").glob("*/manifest.json")).read_text())
    assert manifest["preflight_only"] is True
    assert manifest["database_context"] == {"database": "production"}
    assert not list((tmp_path / "results").glob("*/observations.json"))


def test_manifest_records_label_and_case_file(tmp_path: Path) -> None:
    _, _, manifest, _ = run_with_observations(tmp_path, [good(), good()], label="step 0")
    assert manifest["label"] == "step 0"
    assert manifest["cases_file"] == "two.json"


@pytest.mark.parametrize("changes", [{"repeat": 0}, {"repeat": 6}, {"timeout_seconds": 0}])
def test_invalid_run_options_fail_before_any_gemini_call(
    tmp_path: Path, changes: dict[str, object]
) -> None:
    with (
        patch.object(run, "EVALUATION_ROOT", tmp_path),
        patch.object(run, "selected_settings", return_value=SETTINGS),
        patch.object(run, "run_query") as query,
        pytest.raises(ValueError),
    ):
        run.run_batch(arguments(two_case_file(tmp_path), **changes))
    query.assert_not_called()
