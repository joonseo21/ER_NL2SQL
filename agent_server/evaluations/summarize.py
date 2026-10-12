"""Summarize observation directories by question. Counts and timings, not correctness."""

from __future__ import annotations

import argparse
import hashlib
import json
import statistics
from collections import Counter, defaultdict
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

EVALUATION_ROOT = Path(__file__).resolve().parent


def digest(value: Any) -> str:
    text = json.dumps(value, sort_keys=True, ensure_ascii=False, default=str)
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:8]


def _value(value: Any) -> str:
    """Compare numbers by value at two decimals so formatting alone does not differ."""
    if value is None or isinstance(value, bool):
        return str(value)
    try:
        return f"{round(float(value), 2):.2f}"
    except (TypeError, ValueError):
        return str(value)


def _table(rows: list[dict[str, Any]]) -> list[frozenset[str]]:
    return [frozenset(_value(value) for value in row.values()) for row in rows]


def _contained(small: list[frozenset[str]], large: list[frozenset[str]]) -> bool:
    if len(small) != len(large):
        return False
    remaining = list(large)
    for row in small:
        match = next((index for index, other in enumerate(remaining) if row <= other), None)
        if match is None:
            return False
        remaining.pop(match)
    return True


def result_classes(tables: list[list[dict[str, Any]]]) -> int:
    """Count result classes. Column names, order, formatting and extra columns are ignored.

    Two tables are the same class when one's rows are all contained in the other's rows. A
    column that exists in only one table (for example a code column) still separates them.
    """
    normalized = [_table(rows) for rows in tables]
    parent = list(range(len(normalized)))

    def find(index: int) -> int:
        while parent[index] != index:
            parent[index] = parent[parent[index]]
            index = parent[index]
        return index

    for left in range(len(normalized)):
        for right in range(left + 1, len(normalized)):
            if _contained(normalized[left], normalized[right]) or _contained(
                normalized[right], normalized[left]
            ):
                parent[find(left)] = find(right)
    return len({find(index) for index in range(len(normalized))})


def percentile(values: list[int | float], fraction: float) -> float:
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(fraction * len(ordered)))] if ordered else 0.0


def median(values: list[int | float]) -> str:
    return f"{statistics.median(values):.0f}" if values else "-"


def kind_of(item: dict[str, Any]) -> str:
    response = item.get("response")
    if response:
        return str(response.get("kind"))
    return f"error:{item.get('error_category')}"


def load(directory: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    path = directory / "observations.json"
    observations = json.loads(path.read_text(encoding="utf-8")) if path.exists() else []
    return manifest, observations


def summarize(directory: Path) -> str:
    manifest, observations = load(directory)
    lines = [
        f"## {directory.name}",
        "",
        f"- gemini: {manifest.get('gemini', 'not recorded (older run)')}",
        f"- model: {manifest.get('model')} / repeat: {manifest.get('repeat', 1)} / "
        f"snapshot: {manifest.get('snapshot_id_declared')} / "
        f"observations: {len(observations)}",
        "",
        "| case | runs | kinds | repaired | summary missing | result classes | sql variants | "
        "wall s median | llm call s max | prompt tok median | thoughts tok median |",
        "|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    all_calls = [ms for item in observations for ms in item.get("llm_attempt_ms") or []]
    groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for item in observations:
        groups[item["id"]].append(item)
    for case_id, items in groups.items():
        kinds = Counter(kind_of(item) for item in items)
        answers = [item for item in items if kind_of(item) == "answer"]
        classes = result_classes([item["response"].get("rows") or [] for item in answers])
        sql_variants = len({digest(item["response"].get("sql")) for item in answers})
        walls = [item["wall_ms"] / 1000 for item in items if item.get("wall_ms") is not None]
        calls = [ms for item in items for ms in item.get("llm_attempt_ms") or []]
        prompt = [
            item["tokens"]["prompt_token_count"]
            for item in items
            if item.get("tokens", {}).get("prompt_token_count") is not None
        ]
        thoughts = [
            item["tokens"]["thoughts_token_count"]
            for item in items
            if item.get("tokens", {}).get("thoughts_token_count") is not None
        ]
        lines.append(
            f"| {case_id} | {len(items)} | "
            f"{', '.join(f'{name}:{count}' for name, count in sorted(kinds.items()))} | "
            f"{sum(1 for item in items if item.get('sql_repaired'))} | "
            f"{sum(1 for item in answers if not item['response'].get('summary'))} | "
            f"{classes if answers else '-'} | {sql_variants if answers else '-'} | "
            f"{median(walls)} | {max(calls) / 1000 if calls else 0:.1f} | "
            f"{median(prompt)} | {median(thoughts)} |"
        )
    if all_calls:
        seconds = [ms / 1000 for ms in all_calls]
        lines += [
            "",
            f"LLM calls: n={len(seconds)} p50={percentile(seconds, 0.5):.1f}s "
            f"p95={percentile(seconds, 0.95):.1f}s max={max(seconds):.1f}s",
        ]
    return "\n".join(lines)


def _status(manifest: dict[str, Any], observations: int) -> str:
    if manifest.get("preflight_error_category"):
        return f"preflight_failed:{manifest['preflight_error_category']}"
    if manifest.get("preflight_only"):
        return "preflight_only"
    expected = len(manifest.get("cases", [])) * manifest.get("repeat", 1)
    return "complete" if observations == expected else f"incomplete {observations}/{expected}"


def list_runs() -> str:
    """One line per result directory: when, what, with which settings, how it ended."""
    lines = [
        "| id | started (KST) | target | gemini (timeout/thinking) | repeat x cases | status | "
        "purpose |",
        "|---|---|---|---|---|---|---|",
    ]
    entries = []
    for directory in (EVALUATION_ROOT / "results").iterdir():
        if not directory.is_dir():
            continue
        note_path = directory / "NOTE.txt"
        purpose = ""
        if note_path.exists():
            purpose = note_path.read_text(encoding="utf-8").splitlines()[0]
        if (directory / "manifest.json").exists():
            manifest, observations = load(directory)
            started = manifest.get("started_at", "")
            gemini = manifest.get("gemini")
            settings = f"{gemini['timeout_seconds']}s/{gemini['thinking_level']}" if gemini else "-"
            row = (
                directory.name[:8],
                started,
                manifest.get("target", "-"),
                settings,
                f"{manifest.get('repeat', 1)} x {len(manifest.get('cases', []))}",
                _status(manifest, len(observations)),
                manifest.get("label") or purpose,
            )
        else:
            row = (directory.name[:8], "", "-", "-", "-", "no manifest", purpose or directory.name)
        entries.append(row)
    for row in sorted(entries, key=lambda item: item[1]):
        started = row[1]
        if started:
            kst = datetime.fromisoformat(started) + timedelta(hours=9)
            started = kst.strftime("%m-%d %H:%M")
        lines.append("| " + " | ".join((row[0], started, *row[2:])) + " |")
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "directories",
        nargs="*",
        type=Path,
        help="Result directories (default: the newest one under evaluations/results)",
    )
    parser.add_argument("--list", action="store_true", help="Index of every result directory")
    arguments = parser.parse_args()
    if arguments.list:
        print(list_runs())
        return
    directories = arguments.directories
    if not directories:
        results = EVALUATION_ROOT / "results"
        newest = max(
            (path for path in results.iterdir() if (path / "manifest.json").exists()),
            key=lambda path: path.stat().st_mtime,
        )
        directories = [newest]
    print("\n\n".join(summarize(directory) for directory in directories))


if __name__ == "__main__":
    main()
