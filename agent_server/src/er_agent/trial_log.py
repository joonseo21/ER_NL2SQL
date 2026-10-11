"""Append each completed model/SQL attempt immediately, then the final response."""

from __future__ import annotations

import json
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any
from uuid import uuid4

DEFAULT_LOG_PATH = Path(__file__).resolve().parents[2] / "nl2sql_log.jsonl"


class TrialLog:
    def __init__(self, path: Path, question: str, model: str) -> None:
        self.path = path
        self.question = question
        self.model = model
        self.log_id = str(uuid4())
        self.started = time.perf_counter()
        self.logical_calls = 0
        self.physical_calls = 0

    @property
    def elapsed_ms(self) -> int:
        return int((time.perf_counter() - self.started) * 1000)

    def append(self, event: str, **values: Any) -> None:
        record = {
            "event": event,
            "log_id": self.log_id,
            "timestamp": datetime.now(UTC).isoformat(),
            "question": self.question,
            "model": self.model,
            **values,
        }
        # Serialization errors must not leave a partial JSON line in the log.
        line = json.dumps(record, ensure_ascii=False, allow_nan=False)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.path.open("a", encoding="utf-8") as log_file:
            log_file.write(line + "\n")

    def finish(self, **values: Any) -> None:
        self.append(
            "query_end",
            elapsed_ms=self.elapsed_ms,
            logical_calls=self.logical_calls,
            physical_calls=self.physical_calls,
            **values,
        )
