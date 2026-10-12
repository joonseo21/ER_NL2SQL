from __future__ import annotations

from unittest.mock import patch

import pytest

from er_agent.settings import Settings


def from_env(**values: str) -> Settings:
    with (
        patch.dict("os.environ", values, clear=True),
        patch("er_agent.settings.load_dotenv"),
    ):
        return Settings.from_env()


def test_defaults_keep_current_gemini_behaviour() -> None:
    settings = from_env()
    assert settings.gemini_timeout_seconds == 30
    assert settings.gemini_thinking_level == ""


def test_gemini_options_are_read_from_environment() -> None:
    settings = from_env(GEMINI_TIMEOUT_SECONDS="90", GEMINI_THINKING_LEVEL=" Low ")
    assert settings.gemini_timeout_seconds == 90
    assert settings.gemini_thinking_level == "low"


@pytest.mark.parametrize(
    "name,value",
    [
        ("GEMINI_TIMEOUT_SECONDS", "abc"),
        ("GEMINI_TIMEOUT_SECONDS", "-5"),
        ("GEMINI_TIMEOUT_SECONDS", "0"),
        ("GEMINI_TIMEOUT_SECONDS", "601"),
        ("GEMINI_THINKING_LEVEL", "minimal"),
    ],
)
def test_invalid_gemini_options_fail_loudly(name: str, value: str) -> None:
    with pytest.raises(ValueError, match=name):
        from_env(**{name: value})
