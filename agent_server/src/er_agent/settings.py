from __future__ import annotations

import os
from dataclasses import dataclass

from dotenv import load_dotenv

from .paths import REPOSITORY_ROOT

DEFAULT_GEMINI_TIMEOUT_SECONDS = 30
MAX_GEMINI_TIMEOUT_SECONDS = 600
# "" keeps the model default. MINIMAL is rejected by the current model.
THINKING_LEVELS = ("", "low", "high")


def _timeout_from_env() -> int:
    value = os.getenv("GEMINI_TIMEOUT_SECONDS", "").strip()
    if not value:
        return DEFAULT_GEMINI_TIMEOUT_SECONDS
    if not value.isdecimal():
        raise ValueError(
            f"GEMINI_TIMEOUT_SECONDS must be an integer from 1 to {MAX_GEMINI_TIMEOUT_SECONDS}"
        )
    return int(value)


@dataclass(frozen=True)
class Settings:
    database_url: str
    gemini_api_key: str
    gemini_model: str
    season_id: int = 41
    gemini_timeout_seconds: int = DEFAULT_GEMINI_TIMEOUT_SECONDS
    gemini_thinking_level: str = ""

    def __post_init__(self) -> None:
        if type(self.season_id) is not int or self.season_id <= 0:
            raise ValueError("ER_SEASON_ID must be a positive integer")
        if (
            type(self.gemini_timeout_seconds) is not int
            or not 1 <= self.gemini_timeout_seconds <= MAX_GEMINI_TIMEOUT_SECONDS
        ):
            raise ValueError(
                f"GEMINI_TIMEOUT_SECONDS must be an integer from 1 to {MAX_GEMINI_TIMEOUT_SECONDS}"
            )
        if self.gemini_thinking_level not in THINKING_LEVELS:
            raise ValueError("GEMINI_THINKING_LEVEL must be empty, low or high")

    @classmethod
    def from_env(cls) -> Settings:
        root_env = REPOSITORY_ROOT / ".env"
        load_dotenv(root_env)
        return cls(
            database_url=os.getenv("AGENT_DATABASE_URL", ""),
            gemini_api_key=os.getenv("GEMINI_API_KEY", ""),
            gemini_model=os.getenv("GEMINI_MODEL", ""),
            season_id=int(os.getenv("ER_SEASON_ID", "41")),
            gemini_timeout_seconds=_timeout_from_env(),
            gemini_thinking_level=os.getenv("GEMINI_THINKING_LEVEL", "").strip().lower(),
        )

    def require_database(self) -> None:
        if not self.database_url:
            raise ValueError("AGENT_DATABASE_URL is required")

    def require_gemini(self) -> None:
        missing = [
            name
            for name, value in (
                ("GEMINI_API_KEY", self.gemini_api_key),
                ("GEMINI_MODEL", self.gemini_model),
            )
            if not value
        ]
        if missing:
            raise ValueError(f"Missing required environment variables: {', '.join(missing)}")
