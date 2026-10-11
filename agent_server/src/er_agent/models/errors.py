from __future__ import annotations


class LlmCallError(RuntimeError):
    def __init__(self, category: str, code: int | None = None) -> None:
        self.category = category
        self.code = code
        super().__init__(f"Gemini {category}" + (f" (HTTP {code})" if code is not None else ""))


class GenerationFormatError(ValueError):
    def __init__(self) -> None:
        super().__init__("Model response does not match the generation contract")


class DatabaseQueryError(RuntimeError):
    """Only SQLSTATE crosses the driver boundary; never a raw database message."""

    def __init__(self, sqlstate: str | None) -> None:
        self.sqlstate = sqlstate
        super().__init__(f"database: SQLSTATE {sqlstate or 'unavailable'}")
