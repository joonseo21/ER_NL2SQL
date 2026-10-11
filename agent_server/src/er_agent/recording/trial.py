"""Query identity, timing and counters independent of file storage."""

from __future__ import annotations

import time
from datetime import UTC, datetime
from typing import Any
from uuid import uuid4

from .ports import RecordSink


class TrialRecorder:
    def __init__(self, sink: RecordSink, question: str, model: str) -> None:
        self.sink = sink
        self.question = question
        self.model = model
        self.log_id = str(uuid4())
        self.started = time.perf_counter()
        self.logical_calls = 0
        self.physical_calls = 0

    @property
    def elapsed_ms(self) -> int:
        return int((time.perf_counter() - self.started) * 1000)

    def begin_call(self) -> int:
        self.logical_calls += 1
        return self.logical_calls

    def begin_attempt(self) -> None:
        self.physical_calls += 1

    def append(self, event: str, **values: Any) -> None:
        self.sink.write(
            {
                "event": event,
                "log_id": self.log_id,
                "timestamp": datetime.now(UTC).isoformat(),
                "question": self.question,
                "model": self.model,
                **values,
            }
        )

    def finish(self, **values: Any) -> None:
        self.append(
            "query_end",
            elapsed_ms=self.elapsed_ms,
            logical_calls=self.logical_calls,
            physical_calls=self.physical_calls,
            **values,
        )
