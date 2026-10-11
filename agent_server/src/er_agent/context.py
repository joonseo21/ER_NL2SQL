"""Validate extracted question meaning against a read-only database snapshot.

This is not a Korean-language parser. The generation adapter supplies QueryIntent;
this module verifies official names and scope before any generated SQL can execute.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from enum import StrEnum

from .contracts import (
    AppliedConditions,
    DataEvidence,
    NameCategory,
    NameClarification,
    PatchFilter,
    PatchMode,
    PatchVersion,
    QueryConditions,
    QueryRequest,
    QueryResponse,
    ResponseKind,
)
from .names import NameCatalog, NameTerm, ResolvedName


class UnsupportedReason(StrEnum):
    PERSONAL_HISTORY = "personal_history"
    PREDICTION = "prediction"
    UNRELATED = "unrelated"
    UNAVAILABLE_DATA = "unavailable_data"


REASON_MESSAGES = {
    UnsupportedReason.PERSONAL_HISTORY: "특정 이용자의 개인 전적은 제공하지 않습니다.",
    UnsupportedReason.PREDICTION: "승패 예측·조언·추천은 제공하지 않습니다.",
    UnsupportedReason.UNRELATED: "이터널 리턴 경기 데이터에 관한 질문만 지원합니다.",
    UnsupportedReason.UNAVAILABLE_DATA: "수집된 경기 데이터로 확인할 수 없는 내용입니다.",
}
EXAMPLES = ("최신 패치의 실험체별 승률과 표본 수를 알려줘", "티어별 평균 순위를 비교해줘")


@dataclass(frozen=True)
class TimeRange:
    start: datetime
    end: datetime

    def __post_init__(self) -> None:
        for value in (self.start, self.end):
            if not isinstance(value, datetime) or value.utcoffset() is None:
                raise ValueError("Period boundaries must be timezone-aware datetimes")
        if self.start >= self.end:
            raise ValueError("Period requires start < end (end is exclusive)")


@dataclass(frozen=True)
class TierSelection:
    tier: str | None = None
    division: int | None = None

    def __post_init__(self) -> None:
        QueryConditions(tier=self.tier, tier_division=self.division)


@dataclass(frozen=True)
class QueryIntent:
    names: tuple[NameTerm, ...] = ()
    patch: PatchFilter | None = None
    tier: TierSelection | None = None
    character: NameTerm | None = None
    all_characters: bool = False
    period: TimeRange | None = None
    unsupported_reason: UnsupportedReason | None = None

    def __post_init__(self) -> None:
        names = tuple(self.names)
        if any(not isinstance(term, NameTerm) for term in names):
            raise ValueError("Intent names must contain NameTerm values")
        object.__setattr__(self, "names", names)
        for value, expected in (
            (self.patch, PatchFilter),
            (self.tier, TierSelection),
            (self.character, NameTerm),
            (self.period, TimeRange),
            (self.unsupported_reason, UnsupportedReason),
        ):
            if value is not None and not isinstance(value, expected):
                raise ValueError("Invalid extracted intent type")
        if type(self.all_characters) is not bool or (self.character and self.all_characters):
            raise ValueError("Character override must be a name or all characters")
        if self.character and self.character.category not in {None, NameCategory.CHARACTER}:
            raise ValueError("Character override must refer to a character")


@dataclass(frozen=True)
class ScopeBucket:
    patch: PatchVersion
    tier: str | None
    division: int | None
    appearances: int
    first_match: datetime | None
    last_match: datetime | None
    data_as_of: datetime


@dataclass(frozen=True)
class ContextSnapshot:
    season_id: int
    catalog: NameCatalog
    scopes: tuple[ScopeBucket, ...]
    tiers: tuple[TierSelection, ...]


@dataclass(frozen=True)
class ResolvedContext:
    applied_conditions: AppliedConditions
    names: tuple[ResolvedName, ...]
    period: TimeRange | None
    evidence: DataEvidence
    warnings: tuple[str, ...] = ()


def _notice(request: QueryRequest, log_id: str, elapsed_ms: int, message: str) -> QueryResponse:
    return QueryResponse(
        ResponseKind.NOTICE,
        request.question,
        log_id,
        elapsed_ms,
        message=message,
        examples=EXAMPLES,
    )


def _resolve_names(
    intent: QueryIntent,
    catalog: NameCatalog,
) -> tuple[tuple[ResolvedName, ...], tuple[NameClarification, ...]]:
    terms = list(intent.names)
    if intent.character:
        terms.append(NameTerm(intent.character.text, NameCategory.CHARACTER))
    resolved: list[ResolvedName] = []
    unresolved: list[NameClarification] = []
    for term in dict.fromkeys(terms):
        result = catalog.resolve(term)
        if isinstance(result, ResolvedName):
            resolved.append(result)
        else:
            unresolved.append(result)
    return tuple(resolved), tuple(unresolved)


def _merge_conditions(
    request: QueryRequest,
    intent: QueryIntent,
    catalog: NameCatalog,
) -> QueryConditions:
    selected = request.conditions
    tier = intent.tier or TierSelection(selected.tier, selected.tier_division)
    character = selected.character_code
    if intent.all_characters:
        character = None
    elif intent.character:
        result = catalog.resolve(NameTerm(intent.character.text, NameCategory.CHARACTER))
        if not isinstance(result, ResolvedName):
            raise ValueError("Resolve character names before merging conditions")
        character = result.candidate.code
    return QueryConditions(intent.patch or selected.patch, tier.tier, character, tier.division)


def _eligible_scopes(
    conditions: QueryConditions,
    snapshot: ContextSnapshot,
) -> tuple[ScopeBucket, ...]:
    # Latest is resolved in the whole season/mode, before a tier/character filter.
    patches = {scope.patch for scope in snapshot.scopes}
    if conditions.patch.mode == PatchMode.LATEST:
        patches = {max(patches, key=lambda patch: (patch.major, patch.minor))} if patches else set()
    elif conditions.patch.mode == PatchMode.EXACT:
        patches = set(conditions.patch.versions)
    return tuple(
        scope
        for scope in snapshot.scopes
        if scope.patch in patches
        and (
            conditions.tier is None
            or scope.tier == conditions.tier
            or (conditions.tier == "미확인" and scope.tier is None)
        )
        and (conditions.tier_division is None or scope.division == conditions.tier_division)
        and scope.appearances > 0
    )


def _scope_error(
    conditions: QueryConditions,
    snapshot: ContextSnapshot,
    scopes: tuple[ScopeBucket, ...],
) -> str | None:
    available = {scope.patch for scope in snapshot.scopes}
    if conditions.patch.mode == PatchMode.EXACT and not set(conditions.patch.versions) <= available:
        return "요청한 패치 중 수집 데이터가 없는 패치가 있습니다. 다른 패치로 바꾸지 않습니다."
    tier = TierSelection(conditions.tier, conditions.tier_division)
    if tier.tier is not None:
        known = {item.tier for item in snapshot.tiers} | {"플래티넘 미만", "미확인"}
        if tier.tier not in known or (tier.division is not None and tier not in snapshot.tiers):
            return "요청한 티어 구분은 지원하지 않습니다. 경기 당시 MMR의 저장된 티어를 사용합니다."
    if (
        conditions.character_code is not None
        and snapshot.catalog.character(conditions.character_code) is None
    ):
        return "선택한 실험체 코드를 공식 이름 메타데이터에서 확인할 수 없습니다."
    if not scopes:
        return "요청한 패치·티어 범위에 수집된 참가자 표본이 없습니다."
    # Do not silently discard one side of a multi-patch comparison.
    if conditions.patch.mode == PatchMode.EXACT and set(conditions.patch.versions) != {
        scope.patch for scope in scopes
    }:
        return "비교하려는 패치 중 요청한 티어의 참가자 표본이 없는 패치가 있습니다."
    return None


def prepare_context(
    request: QueryRequest,
    intent: QueryIntent,
    snapshot: ContextSnapshot,
    log_id: str,
    elapsed_ms: int = 0,
) -> ResolvedContext | QueryResponse:
    """Validate supplied intent; early responses contain neither SQL nor result rows."""
    if intent.unsupported_reason is not None:
        return _notice(request, log_id, elapsed_ms, REASON_MESSAGES[intent.unsupported_reason])
    resolved, unresolved = _resolve_names(intent, snapshot.catalog)
    if unresolved:
        return QueryResponse(
            ResponseKind.CLARIFICATION,
            request.question,
            log_id,
            elapsed_ms,
            message="정식 명칭과 종류를 확인해 주세요. 후보는 자동으로 적용하지 않습니다.",
            clarifications=unresolved,
        )
    conditions = _merge_conditions(request, intent, snapshot.catalog)
    scopes = _eligible_scopes(conditions, snapshot)
    error = _scope_error(conditions, snapshot, scopes)
    if error:
        return _notice(request, log_id, elapsed_ms, error)
    warnings: list[str] = []
    if intent.period:
        relevant = tuple(
            scope
            for scope in scopes
            if scope.first_match is not None
            and scope.last_match is not None
            and intent.period.start <= scope.last_match
            and intent.period.end > scope.first_match
        )
        missing_comparison = conditions.patch.mode == PatchMode.EXACT and {
            scope.patch for scope in relevant
        } != {scope.patch for scope in scopes}
        if not relevant or missing_comparison:
            return _notice(
                request, log_id, elapsed_ms, "요청한 기간에 비교 대상의 수집 데이터가 없습니다."
            )
        scopes = relevant
        warnings.append("기간의 첫·마지막 경기 사이가 모두 수집되었다는 보장은 없습니다.")
    if conditions.patch.mode == PatchMode.LATEST:
        actual = tuple(sorted({scope.patch for scope in scopes}, key=lambda p: (p.major, p.minor)))
        conditions = QueryConditions(
            PatchFilter(PatchMode.EXACT, actual),
            conditions.tier,
            conditions.character_code,
            conditions.tier_division,
        )
    return ResolvedContext(
        AppliedConditions(snapshot.season_id, conditions),
        resolved,
        intent.period,
        # This is a freshness bound, not a result sample size or query timestamp.
        DataEvidence(data_as_of=max(scope.data_as_of for scope in scopes)),
        tuple(warnings),
    )
