from __future__ import annotations

import os
from dataclasses import dataclass, field

from dotenv import load_dotenv

from .models.generation import ModelSettings
from .paths import REPOSITORY_ROOT

DEFAULT_GEMINI_TIMEOUT_SECONDS = 30
MAX_GEMINI_TIMEOUT_SECONDS = 600
# "" keeps the model default. MINIMAL is rejected by the current model.
THINKING_LEVELS = ("", "low", "high")
# Unset uses low (2026-10-17 user decision): with the model default, 15 of 60 pilot
# observations exhausted three 30-second attempts. "default" restores the model default.
DEFAULT_THINKING_LEVEL = "low"
MODEL_DEFAULT_THINKING = "default"
OPENAI_REASONING_LEVELS = ("", "none", "minimal", "low", "medium", "high", "xhigh", "max")


def _thinking_from_env() -> str:
    value = os.getenv("GEMINI_THINKING_LEVEL", "").strip().lower()
    if not value:
        return DEFAULT_THINKING_LEVEL
    return "" if value == MODEL_DEFAULT_THINKING else value


def _reasoning_from_env() -> str:
    value = os.getenv("OPENAI_REASONING_EFFORT", "").strip().lower()
    return "" if value == "default" else value


def _timeout_from_env(name: str = "GEMINI_TIMEOUT_SECONDS") -> int:
    value = os.getenv(name, "").strip()
    if not value:
        return DEFAULT_GEMINI_TIMEOUT_SECONDS
    if not value.isdecimal():
        raise ValueError(f"{name} must be an integer from 1 to {MAX_GEMINI_TIMEOUT_SECONDS}")
    return int(value)


@dataclass(frozen=True)
class Settings:
    database_url: str
    gemini_api_key: str = field(default="", repr=False)
    gemini_model: str = ""
    season_id: int = 41
    gemini_timeout_seconds: int = DEFAULT_GEMINI_TIMEOUT_SECONDS
    gemini_thinking_level: str = DEFAULT_THINKING_LEVEL
    llm_provider: str = "gemini"
    openai_api_key: str = field(default="", repr=False)
    openai_model: str = ""
    openai_timeout_seconds: int = 30
    # Empty omits reasoning entirely, allowing non-reasoning GPT models too.
    openai_reasoning_effort: str = ""

    def __post_init__(self) -> None:
        if self.llm_provider not in ("gemini", "openai"):
            raise ValueError("LLM_PROVIDER must be gemini or openai")
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
            raise ValueError("GEMINI_THINKING_LEVEL must be empty, low, high or default")
        if (
            type(self.openai_timeout_seconds) is not int
            or not 1 <= self.openai_timeout_seconds <= 600
        ):
            raise ValueError("OPENAI_TIMEOUT_SECONDS must be an integer from 1 to 600")
        if self.openai_reasoning_effort not in OPENAI_REASONING_LEVELS:
            raise ValueError("Invalid OPENAI_REASONING_EFFORT")

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
            gemini_thinking_level=_thinking_from_env(),
            llm_provider=os.getenv("LLM_PROVIDER", "gemini").strip().lower(),
            openai_api_key=os.getenv("OPENAI_API_KEY", ""),
            openai_model=os.getenv("OPENAI_MODEL", ""),
            openai_timeout_seconds=_timeout_from_env("OPENAI_TIMEOUT_SECONDS"),
            openai_reasoning_effort=_reasoning_from_env(),
        )

    @property
    def llm(self) -> ModelSettings:
        if self.llm_provider == "openai":
            return ModelSettings(
                "openai",
                self.openai_model,
                self.openai_api_key,
                self.openai_timeout_seconds,
                self.openai_reasoning_effort,
            )
        return ModelSettings(
            "gemini",
            self.gemini_model,
            self.gemini_api_key,
            self.gemini_timeout_seconds,
            self.gemini_thinking_level,
        )

    def require_llm(self) -> None:
        selected = self.llm
        prefix = selected.provider.upper()
        missing = [
            f"{prefix}_{name}"
            for name, value in (("API_KEY", selected.api_key), ("MODEL", selected.model))
            if not value.strip()
        ]
        if missing:
            raise ValueError(f"Missing required environment variables: {', '.join(missing)}")

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
