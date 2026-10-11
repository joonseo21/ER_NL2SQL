"""Enforce the responsibility boundaries alongside the normal regression suite."""

from __future__ import annotations

import ast
from importlib.util import resolve_name
from pathlib import Path

import pytest

SOURCE_ROOT = Path(__file__).resolve().parents[1] / "src" / "er_agent"
PURE_STDLIB = {"__future__", "dataclasses", "datetime", "decimal", "enum", "math", "typing"}
LAYER_IMPORTS = {
    "models": {"er_agent.models"},
    "sql": {"er_agent.models", "er_agent.sql", "sqlglot", "re"},
    "query": {
        "er_agent.models",
        "er_agent.sql",
        "er_agent.query",
        "collections",
        "contextlib",
        "difflib",
        "time",
        "unicodedata",
    },
    "database": {
        "er_agent.models",
        "er_agent.sql",
        "er_agent.database",
        "psycopg",
        "sqlglot",
        "collections",
        "contextlib",
        "uuid",
    },
    "generation": {
        "er_agent.models",
        "er_agent.sql.bindings",
        "er_agent.query.ports",
        "er_agent.generation",
        "er_agent.paths",
        "json",
        "time",
    },
    "recording": {"er_agent.recording", "er_agent.paths", "json", "pathlib", "time", "uuid"},
}


def imported_modules(source: str, module: str) -> list[tuple[int, str]]:
    imports: list[tuple[int, str]] = []
    for node in ast.walk(ast.parse(source)):
        if isinstance(node, ast.Import):
            imports.extend((node.lineno, name.name) for name in node.names)
        elif isinstance(node, ast.ImportFrom):
            name = "." * node.level + (node.module or "")
            if node.level:
                name = resolve_name(name, module.rpartition(".")[0])
            imports.append((node.lineno, name))
    return imports


def violations(source: str, module: str) -> list[tuple[int, str]]:
    layer = module.split(".")[1]
    allowed = PURE_STDLIB | LAYER_IMPORTS[layer]
    if module == "er_agent.generation.gemini":
        allowed = allowed | {"google.genai", "google"}
    return [
        (line, name)
        for line, name in imported_modules(source, module)
        if not any(name == prefix or name.startswith(prefix + ".") for prefix in allowed)
    ]


def test_source_dependencies_respect_layers() -> None:
    failures: list[str] = []
    for layer in LAYER_IMPORTS:
        for path in sorted((SOURCE_ROOT / layer).glob("*.py")):
            module = f"er_agent.{layer}.{path.stem}"
            failures.extend(
                f"{path.relative_to(SOURCE_ROOT)}:{line}: {name}"
                for line, name in violations(path.read_text(encoding="utf-8"), module)
            )
    assert not failures, "Forbidden layer dependencies:\n" + "\n".join(failures)


@pytest.mark.parametrize(
    "module,source",
    [
        ("er_agent.models.query", "from ..database.query_executor import execute_select"),
        ("er_agent.models.names", "from pathlib import Path"),
        ("er_agent.query.service", "import psycopg.errors"),
        ("er_agent.query.execution", "from google.genai import errors"),
        ("er_agent.query.service", "from ..generation.agent import SqlGenerationAgent"),
        ("er_agent.sql.scope", "from ..recording.jsonl import JsonlSink"),
        ("er_agent.generation.retry", "from google import genai"),
        ("er_agent.generation.agent", "from ..database.connection import _analytics_connection"),
        ("er_agent.recording.trial", "from ..query.service import QueryService"),
    ],
)
def test_forbidden_imports_are_detected(module: str, source: str) -> None:
    assert violations(source, module)


@pytest.mark.parametrize(
    "module,source",
    [
        ("er_agent.models.context", "from .names import NameCatalog"),
        ("er_agent.query.service", "from .ports import QueryExecutor"),
        ("er_agent.sql.scope", "from sqlglot.optimizer.scope import traverse_scope"),
        ("er_agent.generation.gemini", "from google import genai"),
        ("er_agent.generation.retry", "from .ports import ModelTransport"),
        ("er_agent.recording.trial", "from .ports import RecordSink"),
    ],
)
def test_allowed_imports_remain_possible(module: str, source: str) -> None:
    assert not violations(source, module)
