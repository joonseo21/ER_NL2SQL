"""Grade recorded agent observations against reviewed answer SQL on a fixed snapshot.

Commands:
- expect: run each answer SQL once as agent_ro on the snapshot and save the result tables.
- grade: compare one or more run.py result folders with the saved tables.
- run: split a question file into batches run.py accepts, run them, then grade them together.

The answer files (gold, saved tables) live outside the repository and are passed by path.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import time
from datetime import UTC, datetime
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation
from pathlib import Path
from typing import Any
from uuid import uuid4

import psycopg
from psycopg import errors

from er_agent.database.connection import _analytics_connection
from er_agent.models.serialization import json_value
from er_agent.settings import Settings

from . import run
from .report import write_json

EXPECTED_KIND = {"답변": "answer", "안내": "notice", "되묻기": "clarification"}
MODES = ("all_rows", "required", "subset")
DEFAULT_TOLERANCE = Decimal("0.011")
EXPECT_ATTEMPTS = 3


def file_digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def sql_digest(sql: str) -> str:
    return hashlib.sha256(sql.encode("utf-8")).hexdigest()


def load_gold(path: Path) -> dict[str, dict[str, Any]]:
    values = json.loads(path.read_text(encoding="utf-8"))
    cases = values.get("cases") if isinstance(values, dict) else None
    if not isinstance(cases, list) or not cases:
        raise ValueError("Gold file requires a nonempty cases list")
    gold: dict[str, dict[str, Any]] = {}
    for case in cases:
        case_id = case.get("id")
        if not isinstance(case_id, str) or not case_id or case_id in gold:
            raise ValueError("Gold case ids must be unique nonempty text")
        if case.get("expected_response") not in EXPECTED_KIND:
            raise ValueError(f"Unknown expected_response for {case_id}")
        scoring = case.get("scoring")
        if scoring is not None:
            validate_scoring(case_id, scoring)
        gold[case_id] = case
    return gold


def validate_scoring(case_id: str, scoring: dict[str, Any]) -> None:
    keys = scoring.get("key_columns")
    if not isinstance(keys, list) or not keys or not all(isinstance(key, str) for key in keys):
        raise ValueError(f"{case_id}: scoring.key_columns must list column names")
    if scoring.get("mode") not in MODES:
        raise ValueError(f"{case_id}: scoring.mode must be one of {MODES}")
    for name in ("head", "tail", "min_rows"):
        value = scoring.get(name, 0)
        if type(value) is not int or value < 0:
            raise ValueError(f"{case_id}: scoring.{name} must be a non-negative integer")
    rows = scoring.get("rows", [])
    if not isinstance(rows, list) or any(
        not isinstance(row, list) or len(row) != len(keys) for row in rows
    ):
        raise ValueError(f"{case_id}: scoring.rows must match key_columns")


# ---- expected tables -------------------------------------------------------------


def snapshot_database_url() -> str:
    url = os.getenv("AGENT_EVAL_DATABASE_URL", "")
    if not url:
        raise ValueError("AGENT_EVAL_DATABASE_URL is required; no production fallback")
    if url == Settings.from_env().database_url:
        raise ValueError("Snapshot and production URLs must differ")
    return url


def run_expected_sql(database_url: str, sql: str) -> dict[str, Any]:
    """Execute reviewed answer SQL as agent_ro; retry statement timeouts only."""
    for attempt in range(1, EXPECT_ATTEMPTS + 1):
        started = time.perf_counter()
        try:
            with _analytics_connection(database_url) as connection:
                cursor = connection.execute(sql)
                columns = [column.name for column in cursor.description or ()]
                rows = [json_value(row) for row in cursor.fetchall()]
        except errors.QueryCanceled:
            if attempt == EXPECT_ATTEMPTS:
                return {"error_category": "QueryCanceled", "attempts": attempt}
            continue
        except Exception as exception:
            # Never persist driver messages, which can contain connection details.
            return {"error_category": type(exception).__name__, "attempts": attempt}
        return {
            "columns": columns,
            "rows": rows,
            "elapsed_ms": int((time.perf_counter() - started) * 1000),
            "attempts": attempt,
        }
    raise AssertionError("unreachable")


def collect_expected(arguments: argparse.Namespace) -> int:
    gold = load_gold(arguments.gold)
    if not arguments.snapshot_id or not arguments.snapshot_id.strip():
        raise ValueError("A declared snapshot id is required")
    database_url = snapshot_database_url()
    with _analytics_connection(database_url) as connection:
        identity = connection.execute("SELECT current_database() AS database").fetchone()
    if identity != {"database": arguments.expected_database}:
        raise ValueError("Connected database does not match the explicitly expected name")
    cases = {
        case_id: {
            "sql_sha256": sql_digest(case["sql"]),
            **run_expected_sql(database_url, case["sql"]),
        }
        for case_id, case in gold.items()
        if case.get("sql")
    }
    write_json(
        arguments.out,
        {
            "created_at": datetime.now(UTC).isoformat(),
            "snapshot_id_declared": arguments.snapshot_id,
            "database": arguments.expected_database,
            "gold_sha256": file_digest(arguments.gold),
            "cases": cases,
        },
    )
    failed = sorted(case_id for case_id, item in cases.items() if "error_category" in item)
    print(json.dumps({"status": "failed" if failed else "saved", "failed": failed}))
    return 2 if failed else 0


# ---- value comparison ------------------------------------------------------------


def _number(value: Any) -> Decimal | None:
    if isinstance(value, bool) or value is None:
        return None
    try:
        number = Decimal(str(value).strip())
    except InvalidOperation:
        return None
    return number if number.is_finite() else None


def _boolean(value: Any) -> bool | None:
    if isinstance(value, bool):
        return value
    text = str(value).strip().lower()
    return {"true": True, "t": True, "false": False, "f": False}.get(text)


def values_match(expected: Any, actual: Any, tolerance: Decimal = DEFAULT_TOLERANCE) -> bool:
    """Numbers match within tolerance or when the answer key rounds to the agent's precision."""
    if expected is None or actual is None:
        return expected is None and actual is None
    if isinstance(expected, bool) or isinstance(actual, bool):
        return _boolean(expected) is not None and _boolean(expected) == _boolean(actual)
    left, right = _number(expected), _number(actual)
    if left is not None and right is not None:
        if abs(left - right) <= tolerance:
            return True
        places = max(-right.as_tuple().exponent, 0)
        coarser = places < max(-left.as_tuple().exponent, 0)
        quantum = Decimal(1).scaleb(-places)
        return coarser and left.quantize(quantum, rounding=ROUND_HALF_UP) == right
    return str(expected).strip().casefold() == str(actual).strip().casefold()


def row_matches(keys: list[Any], cells: list[Any], tolerance: Decimal) -> bool:
    """Every key value must match a different cell; column names and order are ignored."""

    def assign(index: int, used: frozenset[int]) -> bool:
        if index == len(keys):
            return True
        return any(
            position not in used
            and values_match(keys[index], cell, tolerance)
            and assign(index + 1, used | {position})
            for position, cell in enumerate(cells)
        )

    return assign(0, frozenset())


def _match_distinct(
    wanted: list[list[Any]], rows: list[list[Any]], tolerance: Decimal, ordered: bool
) -> bool:
    if ordered:
        position = 0
        for keys in wanted:
            while position < len(rows) and not row_matches(keys, rows[position], tolerance):
                position += 1
            if position == len(rows):
                return False
            position += 1
        return True
    owner: dict[int, int] = {}

    def augment(index: int, seen: set[int]) -> bool:
        for position, cells in enumerate(rows):
            if position in seen or not row_matches(wanted[index], cells, tolerance):
                continue
            seen.add(position)
            if position not in owner or augment(owner[position], seen):
                owner[position] = index
                return True
        return False

    return all(augment(index, set()) for index in range(len(wanted)))


def compare_result(
    scoring: dict[str, Any], expected: dict[str, Any], response: dict[str, Any]
) -> list[str]:
    """Return failed checks; an empty list means the result matches the answer key."""
    keys = scoring["key_columns"]
    missing = [key for key in keys if key not in expected["columns"]]
    if missing:
        raise ValueError(f"Key columns are absent from the expected table: {missing}")
    tolerance = Decimal(str(scoring.get("tolerance", DEFAULT_TOLERANCE)))
    ordered = bool(scoring.get("ordered", False))
    expected_rows = [[row[key] for key in keys] for row in expected["rows"]]
    columns = response.get("columns") or []
    rows = [[row.get(column) for column in columns] for row in response.get("rows") or []]
    failures = []
    if len(rows) < max(scoring.get("min_rows", 0), 1):
        failures.append("too_few_rows")
    if scoring["mode"] == "all_rows":
        if len(rows) != len(expected_rows):
            failures.append("row_count")
        elif not _match_distinct(expected_rows, rows, tolerance, ordered):
            failures.append("rows_differ")
    head, tail = scoring.get("head", 0), scoring.get("tail", 0)
    leading = expected_rows[:head]
    required = [] if ordered else list(leading)
    if ordered and leading:
        # A ranked answer must start with the same rows in the same order.
        prefix = rows[: len(leading)]
        if len(prefix) < len(leading) or not all(
            row_matches(keys, cells, tolerance) for keys, cells in zip(leading, prefix, strict=True)
        ):
            failures.append("head_order")
    required += [row for row in expected_rows[len(expected_rows) - tail :] if row not in leading]
    required += scoring.get("rows", [])
    if required and not _match_distinct(required, rows, tolerance, ordered=False):
        failures.append("required_rows_missing")
    if scoring["mode"] == "subset" and any(
        not any(row_matches(keys_row, cells, tolerance) for keys_row in expected_rows)
        for cells in rows
    ):
        failures.append("unexpected_rows")
    return failures


# ---- grading ---------------------------------------------------------------------


def _milliseconds(values: Any) -> list[int]:
    return [value for value in values or [] if type(value) is int]


def observation_timing(observation: dict[str, Any]) -> dict[str, Any]:
    """Gemini attempts and SQL stages as recorded by run.py; SQL includes guard and connection."""
    llm = _milliseconds(observation.get("llm_attempt_ms"))
    sql = _milliseconds(observation.get("sql_stage_ms"))
    return {
        "wall_ms": observation.get("wall_ms"),
        "llm_attempt_ms": llm,
        "llm_total_ms": sum(llm) if llm else None,
        "llm_max_ms": max(llm) if llm else None,
        "sql_stage_ms": sql,
        "sql_total_ms": sum(sql) if sql else None,
        "sql_max_ms": max(sql) if sql else None,
        "sql_timeouts": observation.get("sql_timeouts"),
        "sql_repaired": observation.get("sql_repaired"),
        "logical_calls": observation.get("logical_calls"),
        "physical_calls": observation.get("physical_calls"),
    }


def grade_observation(
    case: dict[str, Any], expected: dict[str, Any] | None, observation: dict[str, Any]
) -> dict[str, Any]:
    expected_kind = EXPECTED_KIND[case["expected_response"]]
    response = observation.get("response")
    graded: dict[str, Any] = {
        "id": case["id"],
        "repetition": observation.get("repetition"),
        "expected_kind": expected_kind,
        "kind": response.get("kind") if response else None,
        "timing": observation_timing(observation),
        "reasons": [],
        "review": [],
    }
    if response is None:
        graded["verdict"] = "error"
        graded["reasons"].append(observation.get("error_category") or "no_response")
        return graded
    if graded["kind"] != expected_kind:
        graded["verdict"] = "wrong_kind"
    elif expected_kind == "answer":
        scoring = case.get("scoring")
        if scoring is None or expected is None or "error_category" in expected:
            graded["verdict"] = "unscored"
            graded["reasons"].append("no_answer_key")
        elif expected.get("sql_sha256") != sql_digest(case.get("sql") or ""):
            # The saved table came from a different answer SQL; rerun expect.
            graded["verdict"] = "unscored"
            graded["reasons"].append("stale_answer_key")
        else:
            graded["reasons"] = compare_result(scoring, expected, response)
            graded["verdict"] = "wrong_result" if graded["reasons"] else "correct"
    elif expected_kind == "clarification" and case.get("expected_candidates"):
        names = {
            candidate.get("name")
            for item in response.get("clarifications") or []
            for candidate in item.get("candidates") or []
        }
        absent = [name for name in case["expected_candidates"] if name not in names]
        graded["verdict"] = "wrong_candidates" if absent else "correct"
        graded["reasons"] = [f"missing_candidate:{name}" for name in absent]
    else:
        graded["verdict"] = "correct"
    # Meaning of messages, summaries and disclosures needs a human reader.
    if graded["kind"] == "answer" and case.get("required_disclosure"):
        graded["review"].append("disclosure")
    if graded["kind"] in ("notice", "clarification"):
        graded["review"].append("message")
    return graded


def load_observations(directories: list[Path]) -> list[tuple[Path, dict[str, Any]]]:
    items = []
    for directory in directories:
        path = directory / "observations.json"
        if not path.exists():
            raise ValueError(f"No observations.json in {directory.name}")
        items += [(directory, item) for item in json.loads(path.read_text(encoding="utf-8"))]
    return items


def _rate(numerator: int, denominator: int) -> float | None:
    return round(100.0 * numerator / denominator, 2) if denominator else None


def _percentile(values: list[int], fraction: float) -> int | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round(fraction * (len(ordered) - 1))))]


def timing_summary(items: list[dict[str, Any]]) -> dict[str, Any]:
    def column(name: str) -> list[int]:
        return [item["timing"][name] for item in items if type(item["timing"][name]) is int]

    summary: dict[str, Any] = {}
    for name in ("wall_ms", "llm_total_ms", "sql_total_ms", "sql_max_ms"):
        values = column(name)
        summary[name] = {
            "median": _percentile(values, 0.5),
            "p90": _percentile(values, 0.9),
            "max": max(values) if values else None,
        }
    timeouts = [item["timing"]["sql_timeouts"] for item in items]
    summary["sql_timeouts"] = (
        sum(timeouts) if timeouts and all(type(value) is int for value in timeouts) else None
    )
    return summary


def summarize_grades(gold: dict[str, dict[str, Any]], grades: list[dict[str, Any]]) -> dict:
    scored = [item for item in grades if item["verdict"] != "unscored"]

    def accuracy(items: list[dict[str, Any]]) -> dict[str, Any]:
        gradable = [item for item in items if item["verdict"] != "unscored"]
        correct = sum(item["verdict"] == "correct" for item in gradable)
        kind_ok = sum(item["kind"] == item["expected_kind"] for item in items)
        return {
            "observations": len(items),
            "gradable": len(gradable),
            "correct": correct,
            "accuracy": _rate(correct, len(gradable)),
            "kind_agreement": _rate(kind_ok, len(items)),
            "timing": timing_summary(items),
        }

    by_case = {}
    for case_id in gold:
        items = [item for item in grades if item["id"] == case_id]
        by_case[case_id] = {
            **accuracy(items),
            "verdicts": [item["verdict"] for item in items],
        }
    groups = {
        f"expected_{kind}": accuracy([item for item in grades if item["expected_kind"] == kind])
        for kind in EXPECTED_KIND.values()
    }
    groups["value_area"] = accuracy([item for item in grades if gold[item["id"]].get("value_area")])
    return {
        "overall": accuracy(grades),
        "groups": groups,
        "by_case": by_case,
        "unobserved": [case_id for case_id, value in by_case.items() if not value["observations"]],
        "needs_review": sum(bool(item["review"]) for item in scored),
    }


def grade_directories(gold_path: Path, expected_path: Path, directories: list[Path]) -> Path:
    gold = load_gold(gold_path)
    expected = json.loads(expected_path.read_text(encoding="utf-8"))
    observations = load_observations(directories)
    unknown = sorted({item["id"] for _, item in observations} - set(gold))
    if unknown:
        raise ValueError(f"Observations are not in the gold file: {unknown}")
    grades = []
    for directory, observation in observations:
        case = gold[observation["id"]]
        graded = grade_observation(case, expected["cases"].get(case["id"]), observation)
        grades.append({"result_dir": directory.name, **graded})
    summary = summarize_grades(gold, grades)
    output = run.EVALUATION_ROOT / "results" / f"score-{uuid4()}"
    output.mkdir(parents=True)
    write_json(
        output / "score.json",
        {
            "graded_at": datetime.now(UTC).isoformat(),
            "gold_sha256": file_digest(gold_path),
            "expected_sha256": file_digest(expected_path),
            "expected_snapshot_id": expected.get("snapshot_id_declared"),
            "result_dirs": [directory.name for directory in directories],
            "summary": summary,
            "grades": grades,
        },
    )
    print(render(summary))
    print(json.dumps({"status": "graded", "directory": str(output)}))
    return output


def render(summary: dict[str, Any]) -> str:
    def line(name: str, value: dict[str, Any]) -> str:
        return (
            f"| {name} | {value['observations']} | {value['gradable']} | {value['correct']} "
            f"| {value['accuracy']} | {value['kind_agreement']} |"
        )

    def seconds(value: int | None) -> str:
        return "-" if value is None else f"{value / 1000:.1f}"

    def timing(value: dict[str, Any]) -> str:
        times = value["timing"]
        return (
            f"{seconds(times['wall_ms']['median'])} | {seconds(times['wall_ms']['max'])} "
            f"| {seconds(times['llm_total_ms']['median'])} "
            f"| {seconds(times['sql_total_ms']['median'])} | {seconds(times['sql_max_ms']['max'])} "
            f"| {'-' if times['sql_timeouts'] is None else times['sql_timeouts']}"
        )

    lines = [
        "| 구분 | 관찰 | 채점 가능 | 정답 | 정답률(%) | 유형 일치(%) |",
        "|---|---:|---:|---:|---:|---:|",
        line("전체", summary["overall"]),
        *(line(name, value) for name, value in summary["groups"].items()),
        "",
        "시간(초): 전체·Gemini 호출 합계·SQL 단계 합계는 관찰 한 건 기준. "
        "SQL 단계는 검사·접속 포함",
        "",
        "| 문항 | 정답/채점 가능 | 판정 | 전체 중앙값 | 전체 최대 | Gemini 중앙값 "
        "| SQL 중앙값 | SQL 한 번 최대 | SQL 시간 초과 |",
        "|---|---:|---|---:|---:|---:|---:|---:|---:|",
        *(
            f"| {case_id} | {value['correct']}/{value['gradable']} "
            f"| {', '.join(value['verdicts'])} | {timing(value)} |"
            for case_id, value in summary["by_case"].items()
        ),
        f"| 전체 | | | {timing(summary['overall'])} |",
    ]
    if summary["unobserved"]:
        lines += ["", "관찰 없음: " + ", ".join(summary["unobserved"])]
    lines += ["", f"사람 확인이 필요한 관찰: {summary['needs_review']}"]
    return "\n".join(lines)


# ---- batch runner ----------------------------------------------------------------


def split_questions(path: Path, gold: dict[str, dict[str, Any]], folder: Path) -> list[Path]:
    """Write run.py-sized batch files; ids must exist in the gold file."""
    values = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(values, list) or not values:
        raise ValueError("Question file requires a nonempty list")
    unknown = [value.get("id") for value in values if value.get("id") not in gold]
    if unknown:
        raise ValueError(f"Questions are not in the gold file: {unknown}")
    folder.mkdir(parents=True)
    paths = []
    for start in range(0, len(values), run.MAX_CASES):
        batch = [
            {"id": value["id"], "question": value["question"]}
            for value in values[start : start + run.MAX_CASES]
        ]
        path = folder / f"batch-{len(paths) + 1}.json"
        write_json(path, batch)
        run.load_cases(path)
        paths.append(path)
    return paths


def run_and_grade(arguments: argparse.Namespace) -> int:
    if not arguments.allow_live_gemini:
        raise ValueError("Explicit --allow-live-gemini is required")
    gold = load_gold(arguments.gold)
    json.loads(arguments.expected.read_text(encoding="utf-8"))
    batches = split_questions(
        arguments.questions, gold, run.EVALUATION_ROOT / "results" / f"batches-{uuid4()}"
    )
    codes, directories = [], []
    for index, path in enumerate(batches, start=1):
        batch_arguments = argparse.Namespace(**vars(arguments))
        batch_arguments.cases = path
        batch_arguments.label = f"{arguments.label or 'score'} ({index}/{len(batches)})"
        code, directory = run.execute_batch(batch_arguments)
        codes.append(code)
        # A failed preflight leaves a folder without observations; nothing to grade there.
        if (directory / "observations.json").exists():
            directories.append(directory)
        if code and not arguments.continue_on_failure:
            break
    if directories:
        grade_directories(arguments.gold, arguments.expected, directories)
    return 2 if any(codes) or len(codes) != len(batches) else 0


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    expect = commands.add_parser("expect", help="Save answer SQL results from the snapshot")
    expect.add_argument("--gold", type=Path, required=True)
    expect.add_argument("--out", type=Path, required=True)
    expect.add_argument("--expected-database", required=True)
    expect.add_argument("--snapshot-id", required=True)

    grade = commands.add_parser("grade", help="Grade existing run.py result folders")
    grade.add_argument("--gold", type=Path, required=True)
    grade.add_argument("--expected", type=Path, required=True)
    grade.add_argument("results", type=Path, nargs="+")

    batch = commands.add_parser("run", help="Run a larger question file in batches, then grade")
    batch.add_argument("--gold", type=Path, required=True)
    batch.add_argument("--expected", type=Path, required=True)
    batch.add_argument("--questions", type=Path, required=True)
    batch.add_argument("--target", choices=("production", "snapshot"), required=True)
    batch.add_argument("--expected-database", required=True)
    batch.add_argument("--snapshot-id")
    batch.add_argument("--allow-live-gemini", action="store_true")
    batch.add_argument("--repeat", type=int, default=1)
    batch.add_argument("--continue-on-failure", action="store_true")
    batch.add_argument("--label")
    batch.add_argument("--timeout-seconds", type=int)
    batch.add_argument("--thinking-level", choices=("low", "high"))

    arguments = parser.parse_args()
    try:
        if arguments.command == "expect":
            code = collect_expected(arguments)
        elif arguments.command == "grade":
            grade_directories(arguments.gold, arguments.expected, arguments.results)
            code = 0
        else:
            code = run_and_grade(arguments)
    except ValueError as exception:
        # These messages are written in this module and carry no connection details.
        print(json.dumps({"status": "failed", "error": str(exception)}, ensure_ascii=False))
        code = 2
    except (OSError, psycopg.Error) as exception:
        # Driver and file messages can contain connection strings; keep only the category.
        print(json.dumps({"status": "failed", "error_category": type(exception).__name__}))
        code = 2
    raise SystemExit(code)


if __name__ == "__main__":
    main()
