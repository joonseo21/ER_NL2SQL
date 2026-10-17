"""Run the real agent sequentially against one explicitly selected read-only DB."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import time
from dataclasses import replace
from datetime import UTC, datetime
from pathlib import Path
from typing import Any
from uuid import uuid4

from er_agent.bootstrap import run_query
from er_agent.database.connection import _analytics_connection
from er_agent.database.context_repository import load_context_snapshot
from er_agent.models.query import QueryRequest
from er_agent.paths import AGENT_ROOT
from er_agent.settings import Settings

from .report import read_metrics, write_json

EVALUATION_ROOT = Path(__file__).resolve().parent
MAX_CASES = 10
MAX_REPEAT = 5


def load_cases(path: Path) -> tuple[tuple[str, QueryRequest], ...]:
    values = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(values, list) or not 1 <= len(values) <= MAX_CASES:
        raise ValueError(f"An observation batch requires 1 to {MAX_CASES} cases")
    cases = []
    for value in values:
        if not isinstance(value, dict) or set(value) != {"id", "question"}:
            raise ValueError("Each observation case requires only id and question")
        case_id = value["id"]
        if not isinstance(case_id, str) or not case_id.strip():
            raise ValueError("Case id must be nonempty text")
        cases.append((case_id, QueryRequest(value["question"])))
    if len({case_id for case_id, _ in cases}) != len(cases):
        raise ValueError("Case ids must be unique")
    return tuple(cases)


def selected_settings(target: str, snapshot_id: str | None) -> Settings:
    settings = Settings.from_env()
    if target == "snapshot":
        if not snapshot_id or not snapshot_id.strip():
            raise ValueError("A declared snapshot id is required")
        url = os.getenv("AGENT_EVAL_DATABASE_URL", "")
        if not url:
            raise ValueError("AGENT_EVAL_DATABASE_URL is required; no production fallback")
        if url == settings.database_url:
            raise ValueError("Snapshot and production URLs must differ")
        settings = replace(settings, database_url=url)
    elif target != "production":
        raise ValueError("Unknown evaluation target")
    elif snapshot_id is not None:
        raise ValueError("Production observations must not claim a fixed snapshot")
    settings.require_database()
    return settings


def with_overrides(
    settings: Settings,
    timeout_seconds: int | None,
    thinking_level: str | None,
    provider: str | None = None,
    model: str | None = None,
    reasoning_effort: str | None = None,
) -> Settings:
    """Apply only the selected provider's options, preserving legacy Gemini defaults."""
    changes: dict[str, Any] = {}
    provider = provider or settings.llm_provider
    if provider not in ("gemini", "openai"):
        raise ValueError("Unsupported LLM provider")
    changes["llm_provider"] = provider
    if timeout_seconds is not None:
        changes[f"{provider}_timeout_seconds"] = timeout_seconds
    if model is not None:
        changes[f"{provider}_model"] = model
    if thinking_level is not None:
        if provider != "gemini":
            raise ValueError("--thinking-level is Gemini-only; use --reasoning-effort for OpenAI")
        changes["gemini_thinking_level"] = thinking_level
    if reasoning_effort is not None:
        if provider != "openai":
            raise ValueError("--reasoning-effort is OpenAI-only")
        changes["openai_reasoning_effort"] = (
            "" if reasoning_effort == "default" else reasoning_effort
        )
    return replace(settings, **changes)


def preflight(settings: Settings, expected_database: str) -> dict[str, Any]:
    # Fixed administrative identity query, never generated SQL. The shared
    # connection still enforces agent_ro, read-only transaction and 5s timeout.
    with _analytics_connection(settings.database_url) as connection:
        identity = connection.execute("SELECT current_database() AS database").fetchone()
    if identity != {"database": expected_database}:
        raise ValueError("Connected database does not match the explicitly expected name")
    # Uses agent_ro/read-only/5s, and checks access to V5 name sources before Gemini.
    snapshot = load_context_snapshot(settings.database_url, settings.season_id)
    return {
        "database": expected_database,
        "role": "agent_ro",
        "v5_name_sources_readable": True,
        "name_count": len(snapshot.catalog.entries),
        "participant_appearances": sum(scope.appearances for scope in snapshot.scopes),
        "data_as_of": (
            max(scope.data_as_of for scope in snapshot.scopes).isoformat()
            if snapshot.scopes
            else None
        ),
    }


def git_revision() -> str | None:
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=EVALUATION_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    return result.stdout.strip() if result.returncode == 0 else None


def source_fingerprint() -> str:
    """Include local agent edits, prompt resources and harness; never read .env."""
    paths = (
        list((AGENT_ROOT / "src/er_agent").rglob("*.py"))
        + list((AGENT_ROOT / "evaluations").glob("*.py"))
        + [AGENT_ROOT / name for name in ("metrics.md", "schema_context.md", "pyproject.toml")]
    )
    digest = hashlib.sha256()
    for path in sorted(paths):
        digest.update(path.relative_to(AGENT_ROOT).as_posix().encode())
        digest.update(b"\0" + path.read_bytes() + b"\0")
    return digest.hexdigest()


def observe_case(request: QueryRequest, settings: Settings, log_path: Path) -> dict[str, Any]:
    started = time.perf_counter()
    response = None
    error = None
    try:
        response = run_query(request, settings, log_path).to_dict()
    except Exception as exception:
        # Never persist raw driver/SDK errors, which can contain credentials.
        error = type(exception).__name__
    return {
        "response": response,
        "error_category": error,
        "wall_ms": int((time.perf_counter() - started) * 1000),
        "accuracy": None,
        "review_status": "not_reviewed",
        **read_metrics(log_path),
    }


def run_batch(arguments: argparse.Namespace) -> int:
    return execute_batch(arguments)[0]


def execute_batch(arguments: argparse.Namespace) -> tuple[int, Path]:
    """Run one batch and return its exit code with the result folder it wrote."""
    preflight_only = getattr(arguments, "preflight_only", False)
    allow_live_llm = getattr(arguments, "allow_live_llm", False)
    if not (allow_live_llm or arguments.allow_live_gemini or preflight_only):
        raise ValueError("Explicit --allow-live-llm (legacy: --allow-live-gemini) is required")
    repeat = getattr(arguments, "repeat", 1)
    if type(repeat) is not int or not 1 <= repeat <= MAX_REPEAT:
        raise ValueError(f"Repeat must be an integer from 1 to {MAX_REPEAT}")
    continue_on_failure = getattr(arguments, "continue_on_failure", False)
    cases = load_cases(arguments.cases)
    settings = with_overrides(
        selected_settings(arguments.target, arguments.snapshot_id),
        getattr(arguments, "timeout_seconds", None),
        getattr(arguments, "thinking_level", None),
        getattr(arguments, "provider", None),
        getattr(arguments, "model", None),
        getattr(arguments, "reasoning_effort", None),
    )
    settings.require_llm()
    if settings.llm_provider == "openai" and not (allow_live_llm or preflight_only):
        raise ValueError("OpenAI calls require --allow-live-llm")
    output = EVALUATION_ROOT / "results" / str(uuid4())
    output.mkdir(parents=True)
    manifest = {
        "started_at": datetime.now(UTC).isoformat(),
        "target": arguments.target,
        "snapshot_id_declared": arguments.snapshot_id,
        "git_revision": git_revision(),
        "agent_source_sha256": source_fingerprint(),
        "model": settings.llm.model,
        "provider": settings.llm.provider,
        "llm": {
            "timeout_seconds": settings.llm.timeout_seconds,
            "reasoning_level": settings.llm.reasoning_level or "model_default",
        },
        # Keep the old manifest field for existing Gemini analysis tools.
        **(
            {
                "gemini": {
                    "timeout_seconds": settings.gemini_timeout_seconds,
                    "thinking_level": settings.gemini_thinking_level or "model_default",
                }
            }
            if settings.llm_provider == "gemini"
            else {}
        ),
        "season_id": settings.season_id,
        "label": getattr(arguments, "label", None),
        "cases_file": Path(arguments.cases).name,
        "repeat": repeat,
        "continue_on_failure": continue_on_failure,
        "preflight_only": preflight_only,
        "accuracy": None,
        "cases": [{"id": case_id, "question": request.question} for case_id, request in cases],
    }
    write_json(output / "manifest.json", manifest)
    try:
        manifest["database_context"] = preflight(settings, arguments.expected_database)
    except Exception as exception:
        manifest["preflight_error_category"] = type(exception).__name__
        write_json(output / "manifest.json", manifest)
        print(json.dumps({"status": "preflight_failed", "directory": str(output)}))
        return 2, output
    write_json(output / "manifest.json", manifest)
    if preflight_only:
        # No Gemini call and no generated SQL; only the fixed read-only checks ran.
        print(json.dumps({"status": "preflight_ok", "directory": str(output)}))
        return 0, output
    completed: list[dict[str, Any]] = []
    stopped = False
    # Repetitions form the outer loop so each configuration is spread over time.
    for repetition in range(1, repeat + 1):
        for case_id, request in cases:
            index = len(completed)
            item = {
                "id": case_id,
                "repetition": repetition,
                **observe_case(request, settings, output / f"{index}.jsonl"),
            }
            completed.append(item)
            write_json(output / "observations.json", completed)
            if (item["error_category"] or item["exhausted_llm_call"]) and not continue_on_failure:
                stopped = True
                break
        if stopped:
            break
    failed = len(completed) != len(cases) * repeat or any(
        item["error_category"] or item["exhausted_llm_call"] for item in completed
    )
    # A completed observation is not a passed correctness test.
    print(json.dumps({"status": "incomplete" if failed else "recorded", "directory": str(output)}))
    return (2 if failed else 0), output


def add_model_arguments(parser: argparse.ArgumentParser) -> None:
    """Shared provider options for observation and scored batch entry points."""
    parser.add_argument("--allow-live-llm", action="store_true")
    parser.add_argument(
        "--allow-live-gemini", action="store_true", help="Legacy Gemini-only opt-in"
    )
    parser.add_argument("--provider", choices=("gemini", "openai"))
    parser.add_argument("--model", help="Override the selected provider's model ID")
    parser.add_argument(
        "--reasoning-effort",
        choices=("default", "none", "minimal", "low", "medium", "high", "xhigh", "max"),
    )
    parser.add_argument(
        "--timeout-seconds", type=int, help="Override the selected provider's timeout"
    )
    parser.add_argument(
        "--thinking-level", choices=("low", "high"), help="Override GEMINI_THINKING_LEVEL"
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=("production", "snapshot"), required=True)
    parser.add_argument("--expected-database", required=True)
    parser.add_argument("--snapshot-id")
    parser.add_argument(
        "--cases", type=Path, default=EVALUATION_ROOT / "cases/recommendations.json"
    )
    add_model_arguments(parser)
    parser.add_argument("--repeat", type=int, default=1, help=f"1 to {MAX_REPEAT} repetitions")
    parser.add_argument(
        "--continue-on-failure",
        action="store_true",
        help="Keep running after an API or unexpected failure (default: stop)",
    )
    parser.add_argument(
        "--preflight-only",
        action="store_true",
        help="Run only the read-only database checks; no LLM call, no opt-in flag needed",
    )
    parser.add_argument("--label", help="Short purpose recorded in manifest.json")
    arguments = parser.parse_args()
    try:
        code = run_batch(arguments)
    except (ValueError, OSError) as exception:
        # Argument/config failures must not print connection strings or file contents.
        print(
            json.dumps(
                {"status": "configuration_failed", "error_category": type(exception).__name__}
            )
        )
        code = 2
    raise SystemExit(code)


if __name__ == "__main__":
    main()
