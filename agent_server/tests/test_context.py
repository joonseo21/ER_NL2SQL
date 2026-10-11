from collections.abc import Callable
from datetime import datetime, timedelta, timezone

import pytest

from er_agent.context import (
    ContextSnapshot,
    QueryIntent,
    ResolvedContext,
    ScopeBucket,
    TierSelection,
    TimeRange,
    UnsupportedReason,
    prepare_context,
)
from er_agent.contracts import (
    NameCandidate,
    NameCategory,
    PatchFilter,
    PatchMode,
    PatchVersion,
    QueryConditions,
    QueryRequest,
    QueryResponse,
    ResponseKind,
)
from er_agent.names import NameCatalog, NameTerm

KST = timezone(timedelta(hours=9))
DAY = datetime(2026, 10, 8, 20, tzinfo=KST)


def exact(*versions: tuple[int, int]) -> PatchFilter:
    return PatchFilter(PatchMode.EXACT, tuple(PatchVersion(*version) for version in versions))


def bucket(
    patch: tuple[int, int],
    tier: str | None,
    division: int | None = None,
    appearances: int = 10,
    day: datetime = DAY,
) -> ScopeBucket:
    return ScopeBucket(
        PatchVersion(*patch),
        tier,
        division,
        appearances,
        day,
        day + timedelta(hours=1),
        day + timedelta(hours=2),
    )


@pytest.fixture
def snapshot() -> ContextSnapshot:
    return ContextSnapshot(
        41,
        NameCatalog(
            (
                NameCandidate(NameCategory.CHARACTER, 1, "합성 실험체 A"),
                NameCandidate(NameCategory.CHARACTER, 2, "합성 실험체 B"),
                NameCandidate(NameCategory.ITEM, 10, "합성 장비"),
            )
        ),
        (
            bucket((4, 0), "다이아몬드", 4, day=DAY - timedelta(days=8)),
            bucket((5, 0), "다이아몬드", 4),
            bucket((5, 0), "미스릴 이상", 0),
            bucket((5, 0), "플래티넘 미만", 0),
            bucket((5, 0), None),
        ),
        (
            TierSelection("다이아몬드", 4),
            TierSelection("다이아몬드", 3),
            TierSelection("미스릴 이상", 0),
            TierSelection("플래티넘", 4),
        ),
    )


def prepare(
    snapshot: ContextSnapshot,
    intent: QueryIntent | None = None,
    conditions: QueryConditions | None = None,
) -> ResolvedContext | QueryResponse:
    return prepare_context(
        QueryRequest("합성 질문", conditions or QueryConditions()),
        intent or QueryIntent(),
        snapshot,
        "trial-7-2",
        12,
    )


def test_default_latest_and_all_tiers_become_actual_patch(snapshot: ContextSnapshot) -> None:
    result = prepare(snapshot)
    assert isinstance(result, ResolvedContext)
    assert result.applied_conditions.season_id == 41
    assert result.applied_conditions.conditions == QueryConditions(patch=exact((5, 0)))
    assert result.evidence.data_as_of == DAY + timedelta(hours=2)
    assert result.evidence.sample_size is None  # Results determine samples later.


def test_explicit_question_overrides_selected_patch_tier_and_character(
    snapshot: ContextSnapshot,
) -> None:
    result = prepare(
        snapshot,
        QueryIntent(
            patch=exact((4, 0)),
            tier=TierSelection("다이아몬드", 4),
            character=NameTerm("합성 실험체 B"),
        ),
        QueryConditions(exact((5, 0)), "미스릴 이상", 1),
    )
    assert isinstance(result, ResolvedContext)
    assert result.applied_conditions.conditions == QueryConditions(
        exact((4, 0)), "다이아몬드", 2, 4
    )
    assert result.names[0].candidate.code == 2


def test_explicit_all_clears_selected_tier_character_and_patch(snapshot: ContextSnapshot) -> None:
    result = prepare(
        snapshot,
        QueryIntent(patch=PatchFilter(PatchMode.ALL), tier=TierSelection(), all_characters=True),
        QueryConditions(exact((5, 0)), "미스릴 이상", 1, 0),
    )
    assert isinstance(result, ResolvedContext)
    assert result.applied_conditions.conditions == QueryConditions(patch=PatchFilter(PatchMode.ALL))


def test_question_override_can_replace_an_invalid_selection(snapshot: ContextSnapshot) -> None:
    result = prepare(
        snapshot,
        QueryIntent(character=NameTerm("합성 실험체 A")),
        QueryConditions(character_code=999),
    )
    assert isinstance(result, ResolvedContext)
    assert result.applied_conditions.conditions.character_code == 1


def test_mentioned_opponent_name_is_not_implicitly_a_character_filter(
    snapshot: ContextSnapshot,
) -> None:
    result = prepare(
        snapshot, QueryIntent(names=(NameTerm("합성 실험체 B"),)), QueryConditions(character_code=1)
    )
    assert isinstance(result, ResolvedContext)
    assert result.applied_conditions.conditions.character_code == 1
    assert result.names[0].candidate.code == 2


def test_unknown_name_returns_candidates_without_sql_or_applied_scope(
    snapshot: ContextSnapshot,
) -> None:
    result = prepare(snapshot, QueryIntent(names=(NameTerm("합성 장비이", NameCategory.ITEM),)))
    assert isinstance(result, QueryResponse) and result.kind == ResponseKind.CLARIFICATION
    assert result.sql is None and result.rows == () and result.applied_conditions is None
    assert result.clarifications[0].candidates[0].code == 10
    assert result.log_id == "trial-7-2" and result.elapsed_ms == 12


def test_duplicate_name_references_do_not_duplicate_candidates(snapshot: ContextSnapshot) -> None:
    term = NameTerm("합성 실험체 A", NameCategory.CHARACTER)
    result = prepare(snapshot, QueryIntent(names=(term, term), character=term))
    assert isinstance(result, ResolvedContext) and len(result.names) == 1


@pytest.mark.parametrize("reason", list(UnsupportedReason))
def test_unsupported_intent_returns_reason_and_examples_without_sql(
    snapshot: ContextSnapshot,
    reason: UnsupportedReason,
) -> None:
    result = prepare(snapshot, QueryIntent(unsupported_reason=reason))
    assert isinstance(result, QueryResponse) and result.kind == ResponseKind.NOTICE
    assert result.message and result.examples and result.sql is None and result.rows == ()


@pytest.mark.parametrize(
    "conditions",
    [
        QueryConditions(patch=exact((3, 0))),
        QueryConditions(patch=exact((4, 0), (6, 0))),
        QueryConditions(tier="이터니티"),
        QueryConditions(tier="다이아몬드", tier_division=9),
        QueryConditions(tier="플래티넘"),
        QueryConditions(tier="다이아몬드", tier_division=3),
        QueryConditions(character_code=999),
    ],
)
def test_unavailable_scope_is_not_replaced_with_a_neighbor(
    snapshot: ContextSnapshot,
    conditions: QueryConditions,
) -> None:
    result = prepare(snapshot, conditions=conditions)
    assert isinstance(result, QueryResponse) and result.kind == ResponseKind.NOTICE
    assert result.sql is None and result.applied_conditions is None


def test_latest_is_resolved_before_tier_so_it_cannot_fall_back_to_old_patch(
    snapshot: ContextSnapshot,
) -> None:
    updated = ContextSnapshot(
        41, snapshot.catalog, snapshot.scopes + (bucket((5, 1), "미스릴 이상", 0),), snapshot.tiers
    )
    result = prepare(updated, conditions=QueryConditions(tier="다이아몬드"))
    assert isinstance(result, QueryResponse) and result.kind == ResponseKind.NOTICE


def test_missing_tier_in_one_comparison_patch_is_not_silently_dropped(
    snapshot: ContextSnapshot,
) -> None:
    result = prepare(snapshot, conditions=QueryConditions(exact((4, 0), (5, 0)), "미스릴 이상"))
    assert isinstance(result, QueryResponse) and result.kind == ResponseKind.NOTICE


@pytest.mark.parametrize("tier", ["플래티넘 미만", "미확인"])
def test_below_platinum_and_unknown_mmr_are_queryable_when_present(
    snapshot: ContextSnapshot,
    tier: str,
) -> None:
    result = prepare(snapshot, conditions=QueryConditions(tier=tier))
    assert isinstance(result, ResolvedContext) and result.applied_conditions.conditions.tier == tier


def test_minor_patch_is_not_discarded_in_comparison(snapshot: ContextSnapshot) -> None:
    updated = ContextSnapshot(
        41, snapshot.catalog, snapshot.scopes + (bucket((5, 1), "미스릴 이상", 0),), snapshot.tiers
    )
    result = prepare(updated, conditions=QueryConditions(exact((5, 0), (5, 1))))
    assert isinstance(result, ResolvedContext)
    assert result.applied_conditions.conditions.patch == exact((5, 0), (5, 1))


def test_empty_database_returns_notice(snapshot: ContextSnapshot) -> None:
    result = prepare(ContextSnapshot(41, snapshot.catalog, (), snapshot.tiers))
    assert isinstance(result, QueryResponse) and result.kind == ResponseKind.NOTICE


def test_korean_time_range_is_preserved_without_claiming_continuous_coverage(
    snapshot: ContextSnapshot,
) -> None:
    period = TimeRange(DAY - timedelta(hours=20), DAY + timedelta(hours=4))
    result = prepare(snapshot, QueryIntent(period=period))
    assert isinstance(result, ResolvedContext) and result.period == period
    assert result.warnings


def test_period_end_is_exclusive_and_missing_period_returns_notice(
    snapshot: ContextSnapshot,
) -> None:
    result = prepare(snapshot, QueryIntent(period=TimeRange(DAY - timedelta(days=1), DAY)))
    assert isinstance(result, QueryResponse) and result.kind == ResponseKind.NOTICE


def test_period_cannot_discard_one_side_of_patch_comparison(snapshot: ContextSnapshot) -> None:
    result = prepare(
        snapshot,
        QueryIntent(period=TimeRange(DAY, DAY + timedelta(days=1))),
        QueryConditions(exact((4, 0), (5, 0))),
    )
    assert isinstance(result, QueryResponse) and result.kind == ResponseKind.NOTICE


def test_all_patches_can_be_narrowed_by_period_without_changing_the_requested_period(
    snapshot: ContextSnapshot,
) -> None:
    period = TimeRange(DAY, DAY + timedelta(days=1))
    result = prepare(
        snapshot, QueryIntent(period=period), QueryConditions(patch=PatchFilter(PatchMode.ALL))
    )
    assert isinstance(result, ResolvedContext) and result.period == period
    assert result.applied_conditions.conditions.patch.mode == PatchMode.ALL


@pytest.mark.parametrize(
    "factory",
    [
        lambda: TimeRange(DAY, DAY),
        lambda: TimeRange(DAY.replace(tzinfo=None), DAY),
        lambda: TierSelection(division=1),
        lambda: QueryConditions(tier="다이아몬드", tier_division=True),
        lambda: QueryIntent(names=("이름",)),
        lambda: QueryIntent(patch={}),
        lambda: QueryIntent(character=NameTerm("합성 장비", NameCategory.ITEM)),
        lambda: QueryIntent(character=NameTerm("이름"), all_characters=True),
        lambda: QueryIntent(all_characters=1),
    ],
)
def test_invalid_extracted_conditions_fail_closed(factory: Callable[[], object]) -> None:
    with pytest.raises(ValueError):
        factory()
