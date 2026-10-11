from typing import Any, Protocol


class RecordSink(Protocol):
    def write(self, record: dict[str, Any]) -> None: ...
