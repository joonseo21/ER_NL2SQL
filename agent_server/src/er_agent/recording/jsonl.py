"""File storage only: encode fully before writing a single JSONL record."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from ..paths import AGENT_ROOT

DEFAULT_LOG_PATH = AGENT_ROOT / "nl2sql_log.jsonl"


class JsonlSink:
    def __init__(self, path: Path) -> None:
        self.path = path

    def write(self, record: dict[str, Any]) -> None:
        line = json.dumps(record, ensure_ascii=False, allow_nan=False)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.path.open("a", encoding="utf-8") as log_file:
            log_file.write(line + "\n")
