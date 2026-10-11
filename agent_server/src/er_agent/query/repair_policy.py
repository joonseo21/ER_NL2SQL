"""SQL self-repair budget, separate from physical model-request retries."""

from ..models.sql import SqlAttempt

MAX_SQL_ATTEMPTS = 2


def repairable_sqlstate(code: str | None) -> bool:
    return (
        code is not None
        and code != "42501"
        and (code[:2] in {"21", "22", "42", "54"} or code == "57014")
    )


def should_repair(attempt: int, result: SqlAttempt) -> bool:
    return attempt < MAX_SQL_ATTEMPTS and result.repairable
