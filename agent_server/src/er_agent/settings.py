from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv


@dataclass(frozen=True)
class Settings:
    database_url: str
    gemini_api_key: str
    gemini_model: str

    @classmethod
    def from_env(cls) -> "Settings":
        root_env = Path(__file__).resolve().parents[3] / ".env"
        load_dotenv(root_env)
        return cls(
            database_url=os.getenv("AGENT_DATABASE_URL", ""),
            gemini_api_key=os.getenv("GEMINI_API_KEY", ""),
            gemini_model=os.getenv("GEMINI_MODEL", ""),
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
