"""HTTP-only configuration, separate from CLI and model settings."""

from __future__ import annotations

import os
import re
from dataclasses import dataclass, field
from pathlib import Path

from dotenv import load_dotenv

from ..paths import REPOSITORY_ROOT
from ..recording.jsonl import DEFAULT_LOG_PATH


@dataclass(frozen=True)
class HttpSettings:
    internal_token: str = field(repr=False)
    log_path: Path = DEFAULT_LOG_PATH

    def __post_init__(self) -> None:
        if not isinstance(self.internal_token, str) or not re.fullmatch(
            r"[0-9a-f]{64}", self.internal_token
        ):
            raise ValueError(
                "AGENT_INTERNAL_TOKEN must contain 64 lowercase hexadecimal characters"
            )

    @classmethod
    def from_env(cls) -> HttpSettings:
        load_dotenv(REPOSITORY_ROOT / ".env")
        return cls(
            internal_token=os.getenv("AGENT_INTERNAL_TOKEN", ""),
            log_path=Path(os.getenv("AGENT_LOG_PATH") or DEFAULT_LOG_PATH),
        )
