from __future__ import annotations

import argparse
import json
from contextlib import contextmanager
from decimal import Decimal
from pathlib import Path
from typing import Any
from unittest.mock import MagicMock, patch

import pytest
from evaluations import score
from psycopg import errors

SQL = "SELECT 1"


def case(case_id: str = "one", **changes: Any) -> dict[str, Any]:
    value = {
        "id": case_id,
        "expected_response": "답변",
        "sql": SQL,
        "value_area": True,
        "scoring": {"key_columns": ["name", "rate"], "mode": "all_rows"},
    }
    value.update(changes)
    return value


def expected(rows: list[dict[str, Any]], columns: list[str] | None = None) -> dict[str, Any]:
    return {
        "sql_sha256": score.sql_digest(SQL),
        "columns": columns or list(rows[0]),
        "rows": rows,
    }


def answer(rows: list[dict[str, Any]]) -> dict[str, Any]:
    return {"kind": "answer", "columns": list(rows[0]) if rows else [], "rows": rows}


TWO = [{"name": "초월", "rate": "13.56"}, {"name": "블링크", "rate": "11.11"}]


@pytest.mark.parametrize(
    "left,right,result",
    [
        ("13.56", "13.56", True),
        ("13.56", 13.56, True),
        ("13.56", "13.6", True),
        ("13.56", "13.5", False),
        ("13.56", "13.58", False),
        ("41.60", "41.6", True),
        (4, "4.0", True),
        (10, "10.4", False),
        (True, "true", True),
        (True, "f", False),
        (None, None, True),
        (None, "0", False),
        ("셀린", " 셀린 ", True),
        ("XCR", "xcr", True),
        ("셀린", "셀리느", False),
    ],
)
def test_values_match(left: Any, right: Any, result: bool) -> None:
    assert score.values_match(left, right) is result


def test_row_match_ignores_column_names_order_and_extra_columns() -> None:
    assert score.row_matches(["초월", "13.56"], ["13.6", 686, "초월"], Decimal("0.011"))
    assert not score.row_matches(["초월", "13.56"], ["초월"], Decimal("0.011"))


def test_all_rows_requires_same_rows_in_any_order() -> None:
    scoring = case()["scoring"]
    response = answer([{"skill": "블링크", "win": "11.11"}, {"skill": "초월", "win": "13.56"}])
    assert score.compare_result(scoring, expected(TWO), response) == []
    response = answer([{"skill": "초월", "win": "13.56"}])
    assert score.compare_result(scoring, expected(TWO), response) == ["row_count"]


def test_ordered_head_must_keep_rank_order() -> None:
    scoring = {"key_columns": ["name", "rate"], "mode": "required", "head": 2, "ordered": True}
    in_order = answer(
        [{"n": "초월", "r": "13.56"}, {"n": "블링크", "r": "11.11"}, {"n": "x", "r": 1}]
    )
    assert score.compare_result(scoring, expected(TWO), in_order) == []
    gap = answer([{"n": "초월", "r": "13.56"}, {"n": "x", "r": 1}, {"n": "블링크", "r": "11.11"}])
    assert score.compare_result(scoring, expected(TWO), gap) == ["head_order"]
    reversed_rows = answer([{"n": "블링크", "r": "11.11"}, {"n": "초월", "r": "13.56"}])
    assert score.compare_result(scoring, expected(TWO), reversed_rows) == ["head_order"]
    short = answer([{"n": "초월", "r": "13.56"}])
    assert score.compare_result(scoring, expected(TWO), short) == ["head_order"]


def test_head_and_tail_and_literal_rows_are_required() -> None:
    rows = [{"name": name, "rate": rate} for name, rate in (("a", 9), ("b", 5), ("c", 1))]
    scoring = {"key_columns": ["name", "rate"], "mode": "required", "head": 1, "tail": 1}
    assert score.compare_result(scoring, expected(rows), answer([rows[0], rows[2]])) == []
    assert score.compare_result(scoring, expected(rows), answer([rows[0]])) == [
        "required_rows_missing"
    ]
    literal = {"key_columns": ["name", "rate"], "mode": "required", "rows": [["b", 5]]}
    assert score.compare_result(literal, expected(rows), answer([rows[1]])) == []


def test_subset_rejects_rows_absent_from_answer_key() -> None:
    scoring = {"key_columns": ["name", "rate"], "mode": "subset", "min_rows": 1}
    assert score.compare_result(scoring, expected(TWO), answer([TWO[1]])) == []
    wrong = answer([{"name": "블링크", "rate": "20.00"}])
    assert score.compare_result(scoring, expected(TWO), wrong) == ["unexpected_rows"]
    assert score.compare_result(scoring, expected(TWO), answer([])) == ["too_few_rows"]


def test_single_row_answer_passes_when_only_the_first_rank_is_required() -> None:
    rows = [{"name": "a", "rate": 9}, {"name": "b", "rate": 5}]
    scoring = {"key_columns": ["name", "rate"], "mode": "required", "head": 1, "ordered": True}
    assert score.compare_result(scoring, expected(rows), answer([rows[0]])) == []
    assert score.compare_result(scoring, expected(rows), answer([rows[1], rows[0]])) == [
        "head_order"
    ]


MONSTERS = [
    {"monster_code": 1, "monster_name": "닭", "tier": "플래티넘", "kills": "8.99"},
    {"monster_code": 1, "monster_name": "닭", "tier": "미스릴 이상", "kills": "10.61"},
    {"monster_code": 2, "monster_name": "박쥐", "tier": "플래티넘", "kills": "1.50"},
    {"monster_code": 2, "monster_name": "박쥐", "tier": "미스릴 이상", "kills": "2.50"},
]
WIDE = {
    "mode": "grouped",
    "group_by": "monster_code",
    "identity_columns": ["monster_code", "monster_name"],
    "value_columns": ["kills"],
}
BY_TIER = {
    "mode": "grouped",
    "group_by": "tier",
    "value_columns": ["kills"],
    "row_filter": {"monster_code": [1, 2]},
}


def test_grouped_accepts_wide_table_by_code_or_name_with_any_headers() -> None:
    by_name = answer(
        [
            {"동물": "닭", "저티어": "8.99", "고티어(미스릴 이상)": "10.61"},
            {"동물": "박쥐", "저티어": "1.5", "고티어(미스릴 이상)": "2.5"},
        ]
    )
    assert score.compare_result(WIDE, expected(MONSTERS), by_name) == []
    by_code = answer([{"c": 1, "a": 10.61, "b": 8.99}, {"c": 2, "a": 2.5, "b": 1.5}])
    assert score.compare_result(WIDE, expected(MONSTERS), by_code) == []


def test_grouped_rejects_missing_group_wrong_value_and_long_table() -> None:
    wrong = answer(
        [{"동물": "닭", "저": "8.99", "고": "10.80"}, {"동물": "박쥐", "저": 1.5, "고": 2.5}]
    )
    assert score.compare_result(WIDE, expected(MONSTERS), wrong) == ["group_missing"]
    one = answer([{"동물": "닭", "저": "8.99", "고": "10.61"}])
    assert score.compare_result(WIDE, expected(MONSTERS), one) == ["group_missing"]
    long_table = answer(
        [
            {"동물": "닭", "tier": "플래티넘", "값": "8.99"},
            {"동물": "닭", "tier": "미스릴", "값": "10.61"},
        ]
    )
    assert score.compare_result(WIDE, expected(MONSTERS), long_table) == ["group_missing"]
    limited = {**WIDE, "group_limit": 1}
    assert score.compare_result(limited, expected(MONSTERS), one) == []


def test_grouped_by_tier_accepts_one_row_per_tier_with_animals_as_columns() -> None:
    rows = answer(
        [
            {"tier_group": "저티어 (플래티넘)", "chicken": "8.99", "bat": "1.50", "wolf": 7.67},
            {"tier_group": "고티어(미스릴 이상)", "chicken": "10.61", "bat": "2.5", "wolf": 8.58},
        ]
    )
    assert score.compare_result(BY_TIER, expected(MONSTERS), rows) == []
    wrong = answer([{"t": "저", "c": "8.99", "b": "1.50"}, {"t": "고", "c": "10.80", "b": "2.50"}])
    assert score.compare_result(BY_TIER, expected(MONSTERS), wrong) == ["group_missing"]
    animal_rows = answer([{"동물": "닭", "저": "8.99", "고": "10.61"}])
    assert score.compare_result(BY_TIER, expected(MONSTERS), animal_rows) == ["group_missing"]


def test_grouped_column_absent_from_answer_key_is_a_gold_error() -> None:
    with pytest.raises(ValueError, match="Grouped columns"):
        score.compare_result({**WIDE, "value_columns": ["absent"]}, expected(MONSTERS), answer(TWO))


CERES = [
    {"samples": 100, "win_rate": "12.26", "total_samples": 400, "total_win_rate": "11.50"},
    {"samples": 300, "win_rate": "11.21", "total_samples": 400, "total_win_rate": "11.50"},
]
CERES_SCORING = {
    "alternatives": [
        {"key_columns": ["samples", "win_rate"], "mode": "all_rows"},
        {
            "all_of": [
                {"key_columns": ["samples", "win_rate"], "mode": "required", "head": 1},
                {"key_columns": ["total_samples", "total_win_rate"], "mode": "required", "head": 1},
            ]
        },
    ]
}


def test_alternatives_accept_either_reading_of_the_baseline() -> None:
    without = answer([{"g": "있음", "n": 100, "w": "12.26"}, {"g": "없음", "n": 300, "w": "11.21"}])
    assert score.compare_result(CERES_SCORING, expected(CERES), without) == []
    # Compared against the whole character in one side-by-side row.
    overall = answer([{"vs": 100, "vs_w": "12.26", "all": 400, "all_w": "11.50"}])
    assert score.compare_result(CERES_SCORING, expected(CERES), overall) == []
    # Or in two rows.
    two_rows = answer([{"g": "있음", "n": 100, "w": "12.26"}, {"g": "전체", "n": 400, "w": "11.5"}])
    assert score.compare_result(CERES_SCORING, expected(CERES), two_rows) == []


def test_alternatives_reject_a_wrong_baseline_and_report_the_first_nearest_failure() -> None:
    wrong = answer([{"vs": 100, "vs_w": "12.26", "all": 400, "all_w": "13.00"}])
    # Both readings fail once; the first alternative's reason is reported.
    assert score.compare_result(CERES_SCORING, expected(CERES), wrong) == ["row_count"]
    assert score.compare_result(CERES_SCORING["alternatives"][1], expected(CERES), wrong) == [
        "required_rows_missing"
    ]
    other = answer([{"vs": 90, "vs_w": "12.26"}])
    assert score.compare_result(CERES_SCORING, expected(CERES), other)


def test_missing_key_column_is_a_gold_error() -> None:
    scoring = {"key_columns": ["absent"], "mode": "all_rows"}
    with pytest.raises(ValueError, match="Key columns"):
        score.compare_result(scoring, expected(TWO), answer(TWO))


def test_grade_answer_marks_disclosure_for_human_review() -> None:
    gold_case = case(required_disclosure="기준 안내")
    graded = score.grade_observation(gold_case, expected(TWO), {"response": answer(TWO)})
    assert graded["verdict"] == "correct"
    assert graded["review"] == ["disclosure"]


def test_grade_wrong_kind_error_and_stale_key() -> None:
    notice = {"response": {"kind": "notice", "message": "안내"}}
    assert score.grade_observation(case(), expected(TWO), notice)["verdict"] == "wrong_kind"
    failure = {"response": None, "error_category": "TimeoutError"}
    graded = score.grade_observation(case(), expected(TWO), failure)
    assert (graded["verdict"], graded["reasons"]) == ("error", ["TimeoutError"])
    stale = {**expected(TWO), "sql_sha256": "other"}
    graded = score.grade_observation(case(), stale, {"response": answer(TWO)})
    assert (graded["verdict"], graded["reasons"]) == ("unscored", ["stale_answer_key"])


def test_grade_clarification_checks_expected_candidates() -> None:
    gold_case = case(expected_response="되묻기", sql=None, scoring=None)
    gold_case["expected_candidates"] = ["셀린"]
    response = {
        "kind": "clarification",
        "message": "확인",
        "clarifications": [{"term": "셀리느", "candidates": [{"name": "셀린", "code": 43}]}],
    }
    assert score.grade_observation(gold_case, None, {"response": response})["verdict"] == "correct"
    response["clarifications"][0]["candidates"] = []
    graded = score.grade_observation(gold_case, None, {"response": response})
    assert graded["verdict"] == "wrong_candidates"


def test_gold_accepts_composite_and_grouped_scoring(tmp_path: Path) -> None:
    scoring = {"alternatives": [CERES_SCORING, WIDE], "note": "either reading"}
    path = tmp_path / "gold.json"
    path.write_text(json.dumps({"cases": [case(scoring=scoring)]}), encoding="utf-8")
    assert score.load_gold(path)["one"]["scoring"] == scoring


@pytest.mark.parametrize(
    "bad",
    [
        {"cases": []},
        {"cases": [case(), case()]},
        {"cases": [case(expected_response="예측")]},
        {"cases": [case(scoring={"key_columns": [], "mode": "all_rows"})]},
        {"cases": [case(scoring={"key_columns": ["a"], "mode": "other"})]},
        {"cases": [case(scoring={"key_columns": ["a"], "mode": "required", "rows": [[1, 2]]})]},
        {"cases": [case(scoring={"alternatives": []})]},
        {"cases": [case(scoring={"alternatives": [{"key_columns": [], "mode": "all_rows"}]})]},
        {"cases": [case(scoring={"all_of": [], "mode": "all_rows"})]},
        {"cases": [case(scoring={"mode": "grouped", "group_by": "a"})]},
        {"cases": [case(scoring={"mode": "grouped", "value_columns": ["a"]})]},
        {
            "cases": [
                case(
                    scoring={
                        "mode": "grouped",
                        "group_by": "a",
                        "value_columns": ["a"],
                        "row_filter": {"a": []},
                    }
                )
            ]
        },
    ],
)
def test_gold_validation(tmp_path: Path, bad: dict[str, Any]) -> None:
    path = tmp_path / "gold.json"
    path.write_text(json.dumps(bad, ensure_ascii=False), encoding="utf-8")
    with pytest.raises(ValueError):
        score.load_gold(path)


def test_split_questions_writes_run_sized_batches(tmp_path: Path) -> None:
    gold = {f"q{index}": case(f"q{index}") for index in range(20)}
    questions = tmp_path / "questions.json"
    questions.write_text(
        json.dumps([{"id": case_id, "question": "질문"} for case_id in gold]), encoding="utf-8"
    )
    paths = score.split_questions(questions, gold, tmp_path / "batches")
    assert [len(json.loads(path.read_text(encoding="utf-8"))) for path in paths] == [10, 10]
    unknown = tmp_path / "unknown.json"
    unknown.write_text(json.dumps([{"id": "x", "question": "질문"}]), encoding="utf-8")
    with pytest.raises(ValueError, match="not in the gold"):
        score.split_questions(unknown, gold, tmp_path / "other")


def write_gold(tmp_path: Path, cases: list[dict[str, Any]]) -> Path:
    path = tmp_path / "gold.json"
    path.write_text(json.dumps({"cases": cases}, ensure_ascii=False), encoding="utf-8")
    return path


def test_run_requires_opt_in_before_running_batches(tmp_path: Path) -> None:
    namespace = argparse.Namespace(allow_live_gemini=False)
    with patch.object(score.run, "execute_batch") as execute, pytest.raises(ValueError):
        score.run_and_grade(namespace)
    execute.assert_not_called()


def test_run_grades_all_batches_together(tmp_path: Path) -> None:
    cases = [case(f"q{index}") for index in range(12)]
    gold = write_gold(tmp_path, cases)
    saved = tmp_path / "expected.json"
    saved.write_text(
        json.dumps({"cases": {item["id"]: expected(TWO) for item in cases}}, ensure_ascii=False),
        encoding="utf-8",
    )
    questions = tmp_path / "questions.json"
    questions.write_text(
        json.dumps([{"id": item["id"], "question": "질문"} for item in cases]), encoding="utf-8"
    )
    folders: list[Path] = []

    def fake_batch(arguments: argparse.Namespace) -> tuple[int, Path]:
        folder = tmp_path / f"result-{len(folders)}"
        folder.mkdir()
        batch = json.loads(Path(arguments.cases).read_text(encoding="utf-8"))
        observations = [
            {"id": item["id"], "repetition": 1, "response": answer(TWO)} for item in batch
        ]
        (folder / "observations.json").write_text(json.dumps(observations), encoding="utf-8")
        folders.append(folder)
        return 0, folder

    namespace = argparse.Namespace(
        allow_live_gemini=True,
        gold=gold,
        expected=saved,
        questions=questions,
        label=None,
        continue_on_failure=False,
    )
    with (
        patch.object(score.run, "EVALUATION_ROOT", tmp_path),
        patch.object(score.run, "execute_batch", side_effect=fake_batch),
    ):
        assert score.run_and_grade(namespace) == 0
    assert len(folders) == 2
    output = next((tmp_path / "results").glob("score-*"))
    summary = json.loads((output / "score.json").read_text(encoding="utf-8"))["summary"]
    assert summary["overall"]["correct"] == 12
    assert summary["overall"]["accuracy"] == 100.0


def test_timing_is_recorded_per_observation_and_summarized() -> None:
    observation = {
        "response": answer(TWO),
        "wall_ms": 9000,
        "llm_attempt_ms": [3000, 2500],
        "sql_stage_ms": [20100, 900],
        "sql_timeouts": 1,
        "sql_repaired": True,
    }
    graded = score.grade_observation(case(), expected(TWO), observation)
    timing = graded["timing"]
    assert (timing["llm_total_ms"], timing["sql_total_ms"], timing["sql_max_ms"]) == (
        5500,
        21000,
        20100,
    )
    other = score.grade_observation(case(), expected(TWO), {"response": answer(TWO), "wall_ms": 1})
    summary = score.timing_summary([graded, other])
    assert summary["wall_ms"]["max"] == 9000
    assert summary["llm_total_ms"]["median"] == 5500
    # Old result folders have no timeout count; the total stays unknown rather than zero.
    assert summary["sql_timeouts"] is None
    assert score.timing_summary([graded])["sql_timeouts"] == 1


def test_expected_sql_retries_statement_timeout_only() -> None:
    cursor = MagicMock()
    cursor.description = [MagicMock()]
    cursor.description[0].name = "value"
    cursor.fetchall.return_value = [{"value": Decimal("1.50")}]
    calls = {"count": 0}

    @contextmanager
    def connection(_url: str):
        calls["count"] += 1
        handle = MagicMock()
        if calls["count"] == 1:
            handle.execute.side_effect = errors.QueryCanceled()
        else:
            handle.execute.return_value = cursor
        yield handle

    with patch.object(score, "_analytics_connection", connection):
        result = score.run_expected_sql("url", SQL)
    assert result["rows"] == [{"value": "1.50"}]
    assert result["attempts"] == 2
