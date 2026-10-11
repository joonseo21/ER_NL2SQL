from __future__ import annotations

from dataclasses import replace

from ..models.evidence import GroupEvidence, SampleColumn, SampleUnit
from ..models.query import DataEvidence
from ..models.sql import QueryTable
from ..sql.evidence import verified_sample

UNKNOWN_SAMPLE = "표본 수와 집계 단위를 확인하지 못했습니다."


def result_evidence(
    table: QueryTable,
    sql: str,
    sample: SampleColumn | None,
    evidence: DataEvidence,
    require_target: bool = False,
) -> tuple[DataEvidence, tuple[str, ...]]:
    known_column = (
        sample is not None
        and sample.column in table.columns
        and verified_sample(sql, sample, require_target)
    )
    groups = []
    warnings = []
    for index, row in enumerate(table.rows):
        value = row[sample.column] if known_column and sample is not None else None
        size = value if type(value) is int and value >= 0 else None
        unit = sample.unit if size is not None and sample is not None else None
        group_warnings = ()
        if size is None:
            group_warnings = (UNKNOWN_SAMPLE,)
        elif size == 0:
            group_warnings = ("표본이 없어 지표를 해석할 수 없습니다.",)
        elif unit == SampleUnit.PARTICIPANTS and size < 30:
            group_warnings = ("참가자 출현이 30건 미만인 작은 표본입니다.",)
        groups.append(GroupEvidence(index, size, unit, group_warnings))
        warnings.extend(f"결과 행 {index}: {warning}" for warning in group_warnings)
    if not table.rows:
        warnings.append("조회 결과가 없습니다. 반환 행 수로 표본 수를 추정하지 않습니다.")
    single = groups[0] if len(groups) == 1 else None
    return replace(
        evidence,
        sample_size=single.sample_size if single else None,
        sample_unit=single.sample_unit.value if single and single.sample_unit else None,
        groups=tuple(groups),
    ), tuple(warnings)
