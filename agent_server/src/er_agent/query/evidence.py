from __future__ import annotations

from dataclasses import replace

from ..models.evidence import GroupEvidence, SampleColumn, SampleUnit
from ..models.query import DataEvidence
from ..models.sql import QueryTable
from ..sql.evidence import sample_columns

UNKNOWN_SAMPLE = "표본 수와 집계 단위를 확인하지 못했습니다."
COMPARISON_SAMPLE = (
    "전후 비교의 행별 표본 근거는 변경 후 표본입니다. 변경 전 표본은 표에서 함께 확인해 주세요."
)
SAMPLE_NAMES = (
    "samples",
    "sample_size",
    "sample_count",
    "picks",
    "participant_appearances",
    "game_count",
    "team_count",
)
COMPARISON_PAIRS = (("picks_before", "picks_after"), ("samples_before", "samples_after"))


def _sample_column(
    verified: dict[str, SampleColumn], sample: SampleColumn | None
) -> SampleColumn | None:
    if sample is not None and verified.get(sample.column) == sample:
        return sample
    # Multiple count columns may describe different populations. Do not choose
    # one arbitrarily, including when a model declaration failed verification.
    candidates = [verified[name] for name in SAMPLE_NAMES if name in verified]
    if len(candidates) == 1:
        return candidates[0]
    if not candidates:
        pairs = [
            verified[after]
            for before, after in COMPARISON_PAIRS
            if before in verified
            and after in verified
            and verified[before].unit == verified[after].unit
        ]
        if len(pairs) == 1:
            return pairs[0]
    return None


def result_evidence(
    table: QueryTable,
    sql: str,
    sample: SampleColumn | None,
    evidence: DataEvidence,
    require_target: bool = False,
) -> tuple[DataEvidence, tuple[str, ...]]:
    names = set(SAMPLE_NAMES) | {name for pair in COMPARISON_PAIRS for name in pair}
    if sample is not None:
        names.add(sample.column)
    verified = sample_columns(
        sql, tuple(name for name in table.columns if name in names), require_target
    )
    sample = _sample_column(verified, sample)
    groups = []
    warnings = []
    before_column = next(
        (
            before
            for before, after in COMPARISON_PAIRS
            if sample is not None and sample.column == after
        ),
        None,
    )
    if before_column is not None:
        warnings.append(COMPARISON_SAMPLE)
    for index, row in enumerate(table.rows):
        value = row[sample.column] if sample is not None else None
        size = value if type(value) is int and value >= 0 else None
        unit = sample.unit if size is not None and sample is not None else None
        group_warnings = ()
        if size is None:
            if UNKNOWN_SAMPLE not in warnings:
                warnings.append(UNKNOWN_SAMPLE)
        elif size == 0:
            group_warnings = ("표본이 없어 지표를 해석할 수 없습니다.",)
        elif unit == SampleUnit.PARTICIPANTS and size < 30:
            group_warnings = ("참가자 출현이 30건 미만인 작은 표본입니다.",)
        groups.append(GroupEvidence(index, size, unit, group_warnings))
        warnings.extend(f"결과 행 {index}: {warning}" for warning in group_warnings)
        if before_column is not None:
            before_sample = verified.get(before_column)
            before_size = row.get(before_column)
            if before_sample is None or type(before_size) is not int or before_size < 0:
                if UNKNOWN_SAMPLE not in warnings:
                    warnings.append(UNKNOWN_SAMPLE)
            elif before_size == 0:
                warnings.append(f"결과 행 {index}: 변경 전 표본이 없어 지표를 해석할 수 없습니다.")
            elif before_sample.unit == SampleUnit.PARTICIPANTS and before_size < 30:
                warnings.append(
                    f"결과 행 {index}: 변경 전 참가자 출현이 30건 미만인 작은 표본입니다."
                )
    if not table.rows:
        warnings.append("조회 결과가 없습니다. 반환 행 수로 표본 수를 추정하지 않습니다.")
    single = groups[0] if len(groups) == 1 else None
    return replace(
        evidence,
        sample_size=single.sample_size if single else None,
        sample_unit=single.sample_unit.value if single and single.sample_unit else None,
        groups=tuple(groups),
    ), tuple(warnings)
