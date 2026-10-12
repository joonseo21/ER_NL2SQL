"""Which thinking controls does this SDK/model accept? Trivial prompts only."""

import time

from google import genai
from google.genai import types

from er_agent.settings import Settings

settings = Settings.from_env()
settings.require_gemini()
print("ThinkingConfig fields:", sorted(types.ThinkingConfig.model_fields))
client = genai.Client(
    api_key=settings.gemini_api_key,
    http_options=types.HttpOptions(
        timeout=60_000, retry_options=types.HttpRetryOptions(attempts=1)
    ),
)
prompt = (
    "A table has rows (a=1,b=2),(a=1,b=3),(a=2,b=5). "
    "Write one PostgreSQL SELECT returning a and sum(b) per a ordered by a. SQL only."
)
variants = {
    "default": None,
    "level=low": types.ThinkingConfig(thinking_level="low"),
    "level=minimal": types.ThinkingConfig(thinking_level="minimal"),
    "budget=0": types.ThinkingConfig(thinking_budget=0),
    "budget=512": types.ThinkingConfig(thinking_budget=512),
}
for label, thinking in variants.items():
    config = types.GenerateContentConfig(thinking_config=thinking) if thinking else None
    started = time.perf_counter()
    try:
        response = client.models.generate_content(
            model=settings.gemini_model, contents=prompt, config=config
        )
        usage = response.usage_metadata
        print(
            f"{label:14s} OK {int((time.perf_counter() - started) * 1000):6d} ms "
            f"thoughts={getattr(usage, 'thoughts_token_count', None)} "
            f"out={getattr(usage, 'candidates_token_count', None)}"
        )
    except Exception as exception:
        message = str(exception).replace("\n", " ")[:160]
        print(f"{label:14s} FAILED {type(exception).__name__}: {message}")
