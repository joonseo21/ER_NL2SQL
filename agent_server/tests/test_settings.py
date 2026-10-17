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


def test_defaults_use_low_thinking_and_thirty_second_attempts() -> None:
    settings = from_env()
    assert settings.gemini_timeout_seconds == 30
    assert settings.gemini_thinking_level == "low"


def test_model_default_thinking_must_be_requested_explicitly() -> None:
    assert from_env(GEMINI_THINKING_LEVEL=" Default ").gemini_thinking_level == ""


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


def test_openai_selection_needs_no_gemini_credentials() -> None:
    settings = from_env(
        LLM_PROVIDER="openai",
        OPENAI_MODEL="test-gpt",
        OPENAI_API_KEY="secret-key",
        OPENAI_TIMEOUT_SECONDS="90",
        OPENAI_REASONING_EFFORT=" Low ",
    )
    settings.require_llm()
    assert settings.llm.provider == "openai" and settings.llm.model == "test-gpt"
    assert settings.llm.timeout_seconds == 90 and settings.llm.reasoning_level == "low"
    assert "secret-key" not in repr(settings) + repr(settings.llm)


def test_openai_does_not_inherit_gemini_thinking_or_credentials() -> None:
    settings = from_env(LLM_PROVIDER="openai", GEMINI_API_KEY="secret-key", GEMINI_MODEL="gemini")
    assert settings.llm.reasoning_level == ""
    with pytest.raises(ValueError, match="OPENAI_API_KEY, OPENAI_MODEL"):
        settings.require_llm()


@pytest.mark.parametrize("value", ["", "default"])
def test_openai_reasoning_omits_model_default(value: str) -> None:
    assert from_env(OPENAI_REASONING_EFFORT=value).openai_reasoning_effort == ""


@pytest.mark.parametrize(
    "name,value",
    [
        ("LLM_PROVIDER", "unknown"),
        ("OPENAI_TIMEOUT_SECONDS", "bad"),
        ("OPENAI_TIMEOUT_SECONDS", "0"),
        ("OPENAI_TIMEOUT_SECONDS", "601"),
        ("OPENAI_REASONING_EFFORT", "defaultlow"),
    ],
)
def test_invalid_provider_options_fail_loudly(name: str, value: str) -> None:
    with pytest.raises(ValueError, match=name):
        from_env(**{name: value})
