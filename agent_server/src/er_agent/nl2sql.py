from __future__ import annotations

import json
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from google import genai
from google.genai import errors as genai_errors

from .database import execute_select, sample_rows
from .guard import guard_sql
from .prompt import build_prompt
from .settings import Settings


DEFAULT_LOG_PATH = Path(__file__).resolve().parents[2] / "nl2sql_log.jsonl"


@dataclass
class TrialLog:
    timestamp: str
    question: str
    model: str
    generated_sql: str | None = None
    guarded_sql: str | None = None
    guard_passed: bool = False
    execution_succeeded: bool = False
    result_row_count: int = 0
    elapsed_ms: int = 0
    model_attempts: int = 0
    error: str | None = None


def run_question(question: str, settings: Settings, log_path: Path = DEFAULT_LOG_PATH) -> list[dict[str, Any]]:
    settings.require_database()
    settings.require_gemini()
    started = time.perf_counter()
    trial = TrialLog(
        timestamp=datetime.now(timezone.utc).isoformat(),
        question=question,
        model=settings.gemini_model,
    )

    try:
        prompt = build_prompt(question, sample_rows(settings.database_url))
        client = genai.Client(api_key=settings.gemini_api_key)
        trial.generated_sql, trial.model_attempts = _generate_sql(
            client, settings.gemini_model, prompt
        )
        guarded = guard_sql(trial.generated_sql)
        trial.guarded_sql = guarded.sql
        trial.guard_passed = True
        rows = execute_select(settings.database_url, guarded.sql)
        trial.execution_succeeded = True
        trial.result_row_count = len(rows)
        return rows
    except Exception as exception:
        trial.error = f"{type(exception).__name__}: {exception}"
        raise
    finally:
        trial.elapsed_ms = int((time.perf_counter() - started) * 1000)
        _append_log(log_path, trial)


def _generate_sql(client: genai.Client, model: str, prompt: str) -> tuple[str, int]:
    chat = client.chats.create(model=model)
    for attempt in range(1, 4):
        try:
            response = chat.send_message(prompt)
            return response.text or "", attempt
        except genai_errors.ServerError as exception:
            if exception.code not in {500, 502, 503, 504} or attempt == 3:
                raise
            time.sleep(2 ** attempt)
    raise RuntimeError("Unreachable Gemini retry state")


def _append_log(path: Path, trial: TrialLog) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as log_file:
        log_file.write(json.dumps(asdict(trial), ensure_ascii=False) + "\n")
