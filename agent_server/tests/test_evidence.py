from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

import pytest

from er_agent.models.evidence import SampleColumn, SampleUnit
from er_agent.models.query import DataEvidence
from er_agent.models.sql import QueryTable
from er_agent.query.evidence import COMPARISON_SAMPLE, UNKNOWN_SAMPLE, result_evidence
from er_agent.sql.evidence import verified_sample

PARTICIPANTS = SampleColumn("samples", SampleUnit.PARTICIPANTS)


@pytest.mark.parametrize(
    "sql,unit,expected",
    [
        ("SELECT count(*) AS samples FROM participants", SampleUnit.PARTICIPANTS, True),
        ("SELECT count(1) AS samples FROM games", SampleUnit.GAMES, True),
        ("SELECT count(*) AS samples FROM games", SampleUnit.PARTICIPANTS, False),
        (
            "SELECT count(*) FILTER (WHERE game_rank=1) AS samples FROM participants",
            SampleUnit.PARTICIPANTS,
            True,
        ),
        (
            "SELECT count(DISTINCT p.participant_id) AS samples FROM participants p JOIN participant_equipment e ON e.participant_id=p.participant_id",
            SampleUnit.PARTICIPANTS,
            True,
        ),
        (
            "SELECT count(*) AS samples FROM participants p JOIN participant_equipment e ON e.participant_id=p.participant_id",
            SampleUnit.PARTICIPANTS,
            False,
        ),
        ("SELECT count(DISTINCT game_id) AS samples FROM participants", SampleUnit.GAMES, True),
        (
            "SELECT count(DISTINCT (game_id,team_number)) AS samples FROM participants",
            SampleUnit.TEAMS,
            True,
        ),
        (
            "SELECT count(DISTINCT team_number) AS samples FROM participants",
            SampleUnit.TEAMS,
            False,
        ),
        (
            "SELECT count(*) AS samples FROM participants p JOIN games g ON g.game_id=p.game_id",
            SampleUnit.PARTICIPANTS,
            True,
        ),
        (
            "SELECT count(*) AS samples FROM games g JOIN participants p ON g.game_id=p.game_id",
            SampleUnit.PARTICIPANTS,
            True,
        ),
        (
            "SELECT count(*) AS samples FROM games g LEFT JOIN participants p ON p.game_id=g.game_id",
            SampleUnit.PARTICIPANTS,
            False,
        ),
        (
            "SELECT count(*) AS samples FROM participants p CROSS JOIN games g",
            SampleUnit.PARTICIPANTS,
            False,
        ),
        ("SELECT 99 AS samples FROM participants", SampleUnit.PARTICIPANTS, False),
        ("SELECT count(*) / 2 AS samples FROM participants", SampleUnit.PARTICIPANTS, False),
        ("SELECT count(*) OVER () AS samples FROM participants", SampleUnit.PARTICIPANTS, False),
        (
            "WITH s AS (SELECT participant_id FROM participants) SELECT count(*) AS samples FROM s",
            SampleUnit.PARTICIPANTS,
            True,
        ),
        (
            "WITH s AS (SELECT p.participant_id FROM participants p JOIN games g ON p.game_id=g.game_id) SELECT count(*) AS samples FROM s",
            SampleUnit.PARTICIPANTS,
            True,
        ),
        (
            "WITH s AS (SELECT count(*) AS samples FROM participants) SELECT samples FROM s",
            SampleUnit.PARTICIPANTS,
            True,
        ),
        (
            "WITH s AS (SELECT DISTINCT character_num FROM participants) SELECT count(*) AS samples FROM s",
            SampleUnit.PARTICIPANTS,
            False,
        ),
    ],
)
def test_count_lineage_certifies_unit_conservatively(
    sql: str, unit: SampleUnit, expected: bool
) -> None:
    assert verified_sample(sql, SampleColumn("samples", unit)) is expected


@pytest.mark.parametrize("column,expected", [("picks", True), ("denominator", False)])
def test_target_pick_count_cannot_be_replaced_by_global_denominator(
    column: str, expected: bool
) -> None:
    sql = """SELECT count(*) FILTER (WHERE character_num=%(character_code)s) AS picks,
                    count(*) AS denominator FROM participants"""
    assert (
        verified_sample(sql, SampleColumn(column, SampleUnit.PARTICIPANTS), require_target=True)
        is expected
    )


@pytest.mark.parametrize("operator,expected", [("AND", True), ("OR", False)])
def test_partial_target_predicate_does_not_certify_global_samples(
    operator: str, expected: bool
) -> None:
    sql = f"SELECT count(*) AS samples FROM participants WHERE character_num=%(character_code)s {operator} game_rank=1"
    assert verified_sample(sql, PARTICIPANTS, require_target=True) is expected


def test_group_samples_are_not_combined_or_replaced_with_row_count() -> None:
    day = datetime(2026, 10, 8, tzinfo=UTC)
    table = QueryTable(
        ("character_num", "samples"),
        ({"character_num": 1, "samples": 29}, {"character_num": 2, "samples": 30}),
    )
    evidence, warnings = result_evidence(
        table,
        "SELECT character_num, count(*) AS samples FROM participants GROUP BY character_num",
        PARTICIPANTS,
        DataEvidence(data_as_of=day),
    )
    assert evidence.sample_size is None and evidence.sample_unit is None
    assert evidence.data_as_of == day
    assert [group.sample_size for group in evidence.groups] == [29, 30]
    assert evidence.groups[0].warnings and not evidence.groups[1].warnings
    assert len(warnings) == 1 and "행 0" in warnings[0]


@pytest.mark.parametrize("value", [None, True, -1, 1.5, "4"])
def test_missing_or_invalid_sample_never_uses_number_of_rows(value: Any) -> None:
    table = QueryTable(("samples",), ({"samples": value},))
    evidence, warnings = result_evidence(
        table, "SELECT count(*) AS samples FROM participants", PARTICIPANTS, DataEvidence()
    )
    assert evidence.sample_size is None
    assert evidence.groups[0].sample_size is None
    assert UNKNOWN_SAMPLE in warnings[0]


def test_zero_sample_is_not_a_zero_rate() -> None:
    table = QueryTable(("samples", "rate"), ({"samples": 0, "rate": None},))
    evidence, warnings = result_evidence(
        table,
        "SELECT count(*) AS samples, NULL AS rate FROM participants",
        PARTICIPANTS,
        DataEvidence(),
    )
    assert evidence.sample_size == 0
    assert "표본이 없어" in warnings[0] and table.rows[0]["rate"] is None


def test_game_count_has_game_unit_without_participant_threshold() -> None:
    evidence, warnings = result_evidence(
        QueryTable(("samples",), ({"samples": 2},)),
        "SELECT count(*) AS samples FROM games",
        SampleColumn("samples", SampleUnit.GAMES),
        DataEvidence(),
    )
    assert evidence.sample_size == 2 and evidence.sample_unit == "games"
    assert not warnings


def test_empty_table_preserves_columns_without_inventing_zero_samples() -> None:
    table = QueryTable(("samples",), ())
    evidence, warnings = result_evidence(
        table, "SELECT count(*) AS samples FROM participants LIMIT 0", PARTICIPANTS, DataEvidence()
    )
    assert table.columns == ("samples",)
    assert evidence.sample_size is None and not evidence.groups and warnings


@pytest.mark.parametrize("filtered_alias", ["p", "q"])
@pytest.mark.parametrize("in_filter", [False, True])
def test_target_filter_must_restrict_the_counted_participant_source(
    filtered_alias: str,
    in_filter: bool,
) -> None:
    predicate = f"{filtered_alias}.character_num=%(character_code)s"
    count_filter = f" FILTER (WHERE {predicate})" if in_filter else ""
    where = "" if in_filter else f" WHERE {predicate}"
    sql = f"SELECT count(DISTINCT p.participant_id){count_filter} AS samples FROM participants p JOIN participants q ON p.game_id=q.game_id{where}"
    assert verified_sample(sql, PARTICIPANTS, require_target=True) is (filtered_alias == "p")


@pytest.mark.parametrize("filtered_column", ["own_character", "other_character"])
def test_cte_projection_preserves_distinct_participant_origins(filtered_column: str) -> None:
    sql = f"""WITH joined AS (
        SELECT p.participant_id AS id, p.character_num AS own_character,
               q.character_num AS other_character
        FROM participants p JOIN participants q ON p.game_id=q.game_id)
        SELECT count(DISTINCT s.id) AS samples FROM joined s
        WHERE s.{filtered_column}=%(character_code)s"""
    assert verified_sample(sql, PARTICIPANTS, require_target=True) is (
        filtered_column == "own_character"
    )


@pytest.mark.parametrize("filtered_alias", ["p", "q"])
def test_repeated_cte_occurrences_are_distinct_sources(filtered_alias: str) -> None:
    sql = f"""WITH source AS (SELECT participant_id,character_num,game_id FROM participants)
        SELECT count(DISTINCT p.participant_id) AS samples
        FROM source p JOIN source q ON p.game_id=q.game_id
        WHERE {filtered_alias}.character_num=%(character_code)s"""
    assert verified_sample(sql, PARTICIPANTS, require_target=True) is (filtered_alias == "p")


@pytest.mark.parametrize(
    "column,expression,source,unit",
    [
        ("samples", "count(*)", "participants", "participant_appearances"),
        ("sample_size", "count(*)", "games", "games"),
        ("sample_count", "count(DISTINCT game_id)", "participants", "games"),
        ("picks", "count(DISTINCT participant_id)", "participants", "participant_appearances"),
        ("participant_appearances", "count(*)", "participants", "participant_appearances"),
        ("game_count", "count(*)", "games", "games"),
        ("team_count", "count(DISTINCT (game_id, team_number))", "participants", "teams"),
    ],
)
@pytest.mark.parametrize("declaration", [None, SampleColumn("missing", SampleUnit.GAMES)])
def test_sample_alias_fallback_verifies_count_and_unit(
    column: str, expression: str, source: str, unit: str, declaration: SampleColumn | None
) -> None:
    evidence, warnings = result_evidence(
        QueryTable((column,), ({column: 1392},)),
        f"SELECT {expression} AS {column} FROM {source}",
        declaration,
        DataEvidence(),
    )
    assert evidence.sample_size == 1392 and evidence.sample_unit == unit
    assert evidence.groups[0].sample_size == 1392
    assert not warnings


@pytest.mark.parametrize(
    "sql",
    [
        "SELECT 1392 AS samples FROM participants",
        "SELECT count(DISTINCT user_id) AS samples FROM participants",
        "SELECT count(*) AS samples FROM participants p JOIN participant_traits t ON p.participant_id=t.participant_id",
        "SELECT count(*) AS samples FROM participants p RIGHT JOIN characters c ON c.character_code=p.character_num",
        "SELECT count(*) AS samples FROM participants p JOIN characters c ON c.name_ko=p.tier",
        "SELECT count(*) AS samples, count(DISTINCT game_id) AS game_count FROM participants",
        "SELECT count(*) AS samples FROM participants",
    ],
)
def test_unverified_or_ambiguous_samples_have_one_response_warning(sql: str) -> None:
    # The last query has a known unit but invalid returned sample values.
    columns = ("samples", "game_count") if "game_count" in sql else ("samples",)
    table = QueryTable(columns, tuple(dict.fromkeys(columns) for _ in range(5)))
    evidence, warnings = result_evidence(table, sql, None, DataEvidence())
    assert warnings == (UNKNOWN_SAMPLE,)
    assert [group.row_index for group in evidence.groups] == list(range(5))
    assert all(group.sample_size is None and not group.warnings for group in evidence.groups)


def test_rate_only_result_does_not_invent_samples() -> None:
    evidence, warnings = result_evidence(
        QueryTable(("pick_rate_change",), ({"pick_rate_change": 1.5},) * 5),
        "SELECT 1.5 AS pick_rate_change FROM participants",
        None,
        DataEvidence(),
    )
    assert all(group.sample_size is None for group in evidence.groups)
    assert warnings == (UNKNOWN_SAMPLE,)


@pytest.mark.parametrize("join", ["JOIN", "LEFT JOIN"])
def test_name_lookup_join_does_not_hide_participant_sample(join: str) -> None:
    sql = f"""SELECT c.name_ko, count(*) AS samples FROM participants p
        JOIN games g ON g.game_id=p.game_id
        {join} characters c ON c.character_code=p.character_num GROUP BY c.name_ko"""
    assert verified_sample(sql, PARTICIPANTS)


COMPARISON_SQL = """WITH per_patch AS (
    SELECT p.character_num, p.version_major, p.version_minor, count(*) AS picks,
           sum(count(*)) OVER (PARTITION BY p.version_major,p.version_minor) AS total
    FROM participants p {target}
    GROUP BY p.character_num, p.version_major, p.version_minor
), compared AS (
    SELECT character_num,
           array_agg(picks ORDER BY version_major,version_minor) AS counts,
           array_agg(100.0*picks/NULLIF(total,0) ORDER BY version_major,version_minor) AS rates
    FROM per_patch GROUP BY character_num HAVING count(*)=2
)
SELECT c.name_ko AS character_name, counts[1] AS picks_before, counts[2] AS picks_after,
       rates[1] AS pick_rate_before, rates[2] AS pick_rate_after,
       rates[2]-rates[1] AS pick_rate_change
FROM compared b LEFT JOIN characters c ON c.character_code=b.character_num"""


@pytest.mark.parametrize("require_target", [False, True])
def test_comparison_count_lineage_through_ordered_arrays(require_target: bool) -> None:
    sql = COMPARISON_SQL.format(
        target="WHERE p.character_num=%(character_code)s" if require_target else ""
    )
    assert verified_sample(
        sql, SampleColumn("picks_before", SampleUnit.PARTICIPANTS), require_target
    )
    assert verified_sample(
        sql, SampleColumn("picks_after", SampleUnit.PARTICIPANTS), require_target
    )
    assert not verified_sample(sql, SampleColumn("pick_rate_after", SampleUnit.PARTICIPANTS))
    if require_target:
        assert not verified_sample(
            COMPARISON_SQL.format(target=""),
            SampleColumn("picks_after", SampleUnit.PARTICIPANTS),
            True,
        )


@pytest.mark.parametrize("before", [0, 29, 30])
def test_comparison_evidence_uses_after_count_and_checks_before(before: int) -> None:
    row = dict(
        character_name="케네스",
        picks_before=before,
        picks_after=1392,
        pick_rate_before=1.6,
        pick_rate_after=2.4,
        pick_rate_change=0.8,
    )
    table = QueryTable(tuple(row), (row,))
    evidence, warnings = result_evidence(
        table, COMPARISON_SQL.format(target=""), None, DataEvidence()
    )
    assert evidence.sample_size == 1392 and evidence.sample_unit == "participant_appearances"
    assert warnings[0] == COMPARISON_SAMPLE
    assert UNKNOWN_SAMPLE not in warnings
    assert len(warnings) == (1 if before == 30 else 2)
    assert before == 30 or "변경 전" in warnings[1]


def test_fallback_cannot_use_target_pick_rate_denominator() -> None:
    evidence, warnings = result_evidence(
        QueryTable(("samples",), ({"samples": 1392},)),
        "SELECT count(*) AS samples FROM participants",
        None,
        DataEvidence(),
        require_target=True,
    )
    assert evidence.sample_size is None and warnings == (UNKNOWN_SAMPLE,)
