from __future__ import annotations

import os
from dataclasses import dataclass

from dotenv import load_dotenv

from .paths import REPOSITORY_ROOT


@dataclass(frozen=True)
class Settings:
    database_url: str
    gemini_api_key: str
    gemini_model: str
    season_id: int = 41

    def __post_init__(self) -> None:
        if type(self.season_id) is not int or self.season_id <= 0:
            raise ValueError("ER_SEASON_ID must be a positive integer")

    @classmethod
    def from_env(cls) -> Settings:
        root_env = REPOSITORY_ROOT / ".env"
        load_dotenv(root_env)
        return cls(
            database_url=os.getenv("AGENT_DATABASE_URL", ""),
            gemini_api_key=os.getenv("GEMINI_API_KEY", ""),
            gemini_model=os.getenv("GEMINI_MODEL", ""),
            season_id=int(os.getenv("ER_SEASON_ID", "41")),
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
