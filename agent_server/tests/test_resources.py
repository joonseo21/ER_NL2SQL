from __future__ import annotations

from pathlib import Path
from unittest.mock import patch

import pytest

from er_agent.generation.prompts import METRICS_PATH, SCHEMA_CONTEXT_PATH, build_prompt
from er_agent.paths import AGENT_ROOT, REPOSITORY_ROOT
from er_agent.recording.jsonl import DEFAULT_LOG_PATH
from er_agent.settings import Settings


def test_moved_packages_keep_resource_paths_independent_of_cwd(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    expected = Path(__file__).resolve().parents[1]
    monkeypatch.chdir(tmp_path)
    assert expected == AGENT_ROOT
    assert expected / "schema_context.md" == SCHEMA_CONTEXT_PATH
    assert expected / "metrics.md" == METRICS_PATH
    assert expected / "nl2sql_log.jsonl" == DEFAULT_LOG_PATH
    assert "Metric and terminology definitions" in build_prompt("질문", {})
    with patch("er_agent.settings.load_dotenv") as load:
        Settings.from_env()
    load.assert_called_once_with(REPOSITORY_ROOT / ".env")
