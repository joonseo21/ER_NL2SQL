import json
from datetime import UTC, datetime
from decimal import Decimal

import pytest

from er_agent.models.names import NameCandidate, NameCategory, NameClarification
from er_agent.models.query import (
    AppliedConditions,
    DataEvidence,
    PatchFilter,
    PatchMode,
    PatchVersion,
    QueryConditions,
    QueryRequest,
    QueryResponse,
    ResponseKind,
)


def applied() -> AppliedConditions:
    return AppliedConditions(
        41, QueryConditions(patch=PatchFilter(PatchMode.EXACT, (PatchVersion(5, 0),)))
    )


def answer(**overrides) -> QueryResponse:
    values = dict(
        kind=ResponseKind.ANSWER,
        question="실험체 픽률",
        log_id="trial-1",
        elapsed_ms=10,
        applied_conditions=applied(),
        sql="SELECT count(*) AS samples FROM participants",
        columns=("samples",),
        rows=({"samples": 10},),
    )
    return QueryResponse(**(values | overrides))


def test_request_defaults_are_distinct_from_applied_scope() -> None:
    request = QueryRequest("픽률을 알려줘")
    assert request.conditions.patch.mode == PatchMode.LATEST
    assert request.conditions.tier is None and request.conditions.character_code is None
    with pytest.raises(ValueError, match="Resolve latest"):
        AppliedConditions(41, request.conditions)
    assert applied().conditions.patch.versions == (PatchVersion(5, 0),)


@pytest.mark.parametrize(
    "factory",
    [
        lambda: QueryRequest(" "),
        lambda: QueryRequest("질문", conditions={}),
        lambda: PatchVersion(True, 0),
        lambda: PatchVersion(5, -1),
        lambda: PatchFilter(PatchMode.EXACT),
        lambda: PatchFilter(PatchMode.LATEST, (PatchVersion(5, 0),)),
        lambda: PatchFilter(PatchMode.ALL, (PatchVersion(5, 0),)),
        lambda: PatchFilter(PatchMode.EXACT, (PatchVersion(5, 0), PatchVersion(5, 0))),
        lambda: QueryConditions(character_code=0),
        lambda: QueryConditions(character_code=True),
        lambda: QueryConditions(tier=""),
        lambda: AppliedConditions(0, QueryConditions(patch=PatchFilter(PatchMode.ALL))),
    ],
)
def test_invalid_request_scopes_are_rejected(factory) -> None:
    with pytest.raises(ValueError):
        factory()


def test_exact_patch_comparison_preserves_major_and_minor() -> None:
    conditions = QueryConditions(
        patch=PatchFilter(PatchMode.EXACT, (PatchVersion(4, 0), PatchVersion(5, 1)))
    )
    assert AppliedConditions(41, conditions).conditions == conditions


def test_answer_without_summary_retains_sql_table_and_evidence() -> None:
    response = answer(
        summary=None,
        evidence=DataEvidence(10, "participant_appearances", datetime(2026, 10, 11, tzinfo=UTC)),
        warnings=("표본 30건 미만",),
    )
    payload = response.to_dict()
    assert payload["kind"] == "answer"
    assert payload["summary"] is None
    assert payload["rows"] == [{"samples": 10}]
    assert payload["sql"] and payload["warnings"]
    assert payload["evidence"]["sample_size"] == 10
    assert payload["evidence"]["data_as_of"] == "2026-10-11T00:00:00+00:00"
    json.dumps(payload, allow_nan=False)


def test_empty_result_is_an_answer_with_zero_sample_evidence() -> None:
    response = answer(rows=(), evidence=DataEvidence(0, "participant_appearances"))
    assert response.to_dict()["rows"] == []
    assert response.to_dict()["evidence"]["sample_size"] == 0


def test_notice_and_clarification_share_envelope_but_have_no_sql() -> None:
    notice = QueryResponse(
        ResponseKind.NOTICE,
        "승패를 예측해줘",
        "trial-2",
        2,
        message="예측은 지원하지 않습니다.",
        examples=("실험체 승률",),
    )
    clarification = QueryResponse(
        ResponseKind.CLARIFICATION,
        "가상 이름 픽률",
        "trial-3",
        3,
        message="정식 명칭을 선택해 주세요.",
        clarifications=(
            NameClarification(
                "가상 이름", (NameCandidate(NameCategory.CHARACTER, 1, "가상 실험체"),)
            ),
        ),
    )
    assert set(notice.to_dict()) == set(clarification.to_dict()) == set(answer().to_dict())
    assert notice.sql is None and clarification.sql is None
    assert clarification.to_dict()["clarifications"][0]["candidates"][0] == {
        "category": "character",
        "code": 1,
        "name": "가상 실험체",
    }


@pytest.mark.parametrize(
    "overrides",
    [
        {"sql": None},
        {"applied_conditions": None},
        {"elapsed_ms": True},
        {"columns": ("samples", "samples")},
        {"rows": ({"different": 1},)},
        {"rows": ({"samples": 1},) * 201},
        {"clarifications": (NameClarification("미확인"),)},
    ],
)
def test_invalid_answer_payloads_are_rejected(overrides) -> None:
    with pytest.raises(ValueError):
        answer(**overrides)


@pytest.mark.parametrize(
    "overrides",
    [
        {"message": None},
        {"sql": "SELECT count(*) FROM games"},
        {"columns": ("count",), "rows": ({"count": 1},)},
        {"summary": "가상 요약"},
        {"clarifications": (NameClarification("미확인"),)},
    ],
)
def test_notice_cannot_accidentally_return_sql_or_results(overrides) -> None:
    values = dict(
        kind=ResponseKind.NOTICE,
        question="지원 밖 질문",
        log_id="trial-4",
        elapsed_ms=0,
        message="지원 범위 밖입니다.",
    )
    with pytest.raises(ValueError):
        QueryResponse(**(values | overrides))


def test_clarification_requires_a_term_but_candidates_may_be_empty() -> None:
    values = dict(
        kind=ResponseKind.CLARIFICATION,
        question="가상 질문",
        log_id="trial-5",
        elapsed_ms=0,
        message="이름을 확인해 주세요.",
    )
    with pytest.raises(ValueError):
        QueryResponse(**values)
    response = QueryResponse(**values, clarifications=(NameClarification("미확인 이름"),))
    assert response.to_dict()["clarifications"][0]["candidates"] == []


def test_database_scalars_and_arrays_serialize_without_precision_loss() -> None:
    response = answer(
        columns=("rate", "day", "credits", "details"),
        rows=(
            {
                "rate": Decimal("12.345678901234567890"),
                "day": datetime(2026, 10, 11, tzinfo=UTC).date(),
                "credits": [1, None],
                "details": {"amount": Decimal("0.1")},
            },
        ),
    )
    row = response.to_dict()["rows"][0]
    assert row == {
        "rate": "12.345678901234567890",
        "day": "2026-10-11",
        "credits": [1, None],
        "details": {"amount": "0.1"},
    }
    json.dumps(response.to_dict(), allow_nan=False)


@pytest.mark.parametrize("value", [float("nan"), float("inf"), object()])
def test_non_json_results_are_not_silently_stringified(value) -> None:
    with pytest.raises(ValueError, match="Unsupported JSON value"):
        answer(rows=({"samples": value},)).to_dict()


@pytest.mark.parametrize(
    "factory",
    [
        lambda: DataEvidence(-1, "participant_appearances"),
        lambda: DataEvidence(10),
        lambda: DataEvidence(data_as_of=datetime(2026, 10, 11)),
        lambda: NameCandidate(NameCategory.ITEM, 0, "가상 아이템"),
        lambda: NameClarification(""),
    ],
)
def test_incomplete_or_invalid_evidence_and_candidates_are_rejected(factory) -> None:
    with pytest.raises(ValueError):
        factory()
