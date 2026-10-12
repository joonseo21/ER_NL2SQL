"""Latency probe with a trivial prompt. Sends no database-derived data."""

import time

from er_agent.generation.gemini import GeminiTransport, create_client
from er_agent.settings import Settings

settings = Settings.from_env()
settings.require_gemini()
transport = GeminiTransport(create_client(settings.gemini_api_key), settings.gemini_model)
print("model:", settings.gemini_model)

for label, prompt, schema in (
    ("plain", "Reply with the single word: ok", None),
    (
        "json-schema",
        "Return a JSON object whose field answer is the word ok.",
        {
            "type": "object",
            "properties": {"answer": {"type": "string"}},
            "required": ["answer"],
        },
    ),
):
    started = time.perf_counter()
    try:
        reply = transport.complete(prompt, schema)
        elapsed = int((time.perf_counter() - started) * 1000)
        print(label, "OK", elapsed, "ms", "tokens:", reply.tokens)
    except Exception as exception:
        elapsed = int((time.perf_counter() - started) * 1000)
        print(
            label, "FAILED", elapsed, "ms", type(exception).__name__, getattr(exception, "args", "")
        )
