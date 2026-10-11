from __future__ import annotations

import pytest

from er_agent.context import (
    ContextSnapshot,
    QueryIntent,
    ResolvedContext,
    TierSelection,
    prepare_context,
)
from er_agent.contracts import NameCategory, QueryConditions, QueryRequest
from er_agent.guard import SqlGuardError
from er_agent.names import NameTerm
from er_agent.sql_scope import name_parameters, scope_sql


def context(
    snapshot: ContextSnapshot, intent: QueryIntent | None = None, **conditions: object
) -> ResolvedContext:
    resolved = prepare_context(
        QueryRequest("질문", QueryConditions(**conditions)),
        intent or QueryIntent(),
        snapshot,
        "test",
    )
    assert isinstance(resolved, ResolvedContext)
    return resolved


def test_common_scope_is_inserted_on_physical_sources_only(query_snapshot: ContextSnapshot) -> None:
    sql = "WITH games AS (SELECT game_id FROM public.games) SELECT count(*) AS games FROM games"
    scoped = scope_sql(sql, context(query_snapshot))
    assert '"__er_source"."season_id"' in scoped.sql
    assert scoped.parameters == {"__er_season": 41, "__er_major_0": 5, "__er_minor_0": 0}
    assert scoped.sql.count("%(__er_season)s") == 1
    assert "LIMIT 200" in scoped.sql


@pytest.mark.parametrize(
    "table",
    [
        "participants",
        "participant_equipment",
        "participant_traits",
        "participant_mastery",
        "participant_matchups",
        "participant_deaths",
    ],
)
def test_tier_and_game_scope_cannot_be_bypassed_by_child_table(
    query_snapshot: ContextSnapshot, table: str
) -> None:
    scoped = scope_sql(
        f"SELECT count(*) AS samples FROM {table}",
        context(query_snapshot, QueryIntent(tier=TierSelection("다이아몬드", 4))),
    )
    assert scoped.parameters["__er_tier"] == "다이아몬드"
    assert scoped.parameters["__er_division"] == 4
    assert "%(__er_season)s" in scoped.sql
    assert "%(__er_tier)s" in scoped.sql
    assert "EXISTS" in scoped.sql


def test_target_binding_does_not_prefilter_pick_denominator(
    query_snapshot: ContextSnapshot,
) -> None:
    resolved = context(
        query_snapshot, QueryIntent(character=NameTerm("합성 실험체 A", NameCategory.CHARACTER))
    )
    assert name_parameters(resolved) == {"character_code": 1}
    scoped = scope_sql(
        "SELECT count(*) FILTER (WHERE character_num = %(character_code)s) AS picks, count(*) AS denominator FROM participants",
        resolved,
    )
    assert scoped.sql.count("%(character_code)s") == 1
    assert scoped.parameters["character_code"] == 1


@pytest.mark.parametrize(
    "sql",
    [
        "SELECT count(*) FROM participants WHERE season_id = 41",
        "SELECT count(*) FROM participants WHERE tier = '다이아몬드'",
        "SELECT count(*) FROM participants WHERE character_num = 2",
        "SELECT count(*) FROM participants WHERE character_num = %(name_character_2)s",
        "SELECT %(character_code)s AS target FROM participants",
        "SELECT count(*) FROM participants WHERE best_weapon = %(character_code)s",
        "SELECT count(*) FROM participants",
        "SELECT count(*) FROM participants WHERE character_num = %s",
        "SELECT count(*) FROM characters WHERE character_code = %(character_code)s",
    ],
)
def test_mismatched_scope_or_name_filters_are_rejected(
    query_snapshot: ContextSnapshot, sql: str
) -> None:
    with pytest.raises(SqlGuardError):
        scope_sql(sql, context(query_snapshot, character_code=1))


@pytest.mark.parametrize(
    "category,column,code,name",
    [
        (NameCategory.ITEM, "item_code", 100001, "합성 장비"),
        (NameCategory.TRAIT, "trait_code", 123, "합성 특성"),
        (NameCategory.WEAPON, "best_weapon", 16, "합성 무기"),
        (NameCategory.TACTICAL, "tactical_skill_group", 1, "합성 전술"),
    ],
)
def test_official_name_bindings_are_bound_by_category(
    query_snapshot: ContextSnapshot, category: NameCategory, column: str, code: int, name: str
) -> None:
    table = {
        NameCategory.ITEM: "participant_equipment",
        NameCategory.TRAIT: "participant_traits",
    }.get(category, "participants")
    key = f"name_{category.value}_{code}"
    resolved = context(query_snapshot, QueryIntent(names=(NameTerm(name, category),)))
    scoped = scope_sql(f"SELECT count(*) FROM {table} WHERE {column} = %({key})s", resolved)
    assert scoped.parameters[key] == code


def test_unknown_tier_is_null_not_a_string_filter(query_snapshot: ContextSnapshot) -> None:
    resolved = context(query_snapshot, QueryIntent(tier=TierSelection("미확인")))
    assert (
        '"__er_source"."tier" IS NULL'
        in scope_sql("SELECT count(*) FROM participants", resolved).sql
    )


def test_all_patch_mode_omits_patch_predicate(query_snapshot: ContextSnapshot) -> None:
    from er_agent.contracts import PatchFilter, PatchMode

    resolved = context(query_snapshot, QueryIntent(patch=PatchFilter(PatchMode.ALL)))
    scoped = scope_sql("SELECT count(*) FROM participants", resolved)
    assert scoped.parameters == {"__er_season": 41}


def test_item_metadata_code_accepts_its_own_name_binding(query_snapshot: ContextSnapshot) -> None:
    resolved = context(
        query_snapshot, QueryIntent(names=(NameTerm("합성 장비", NameCategory.ITEM),))
    )
    scoped = scope_sql(
        "SELECT count(*) FROM participant_equipment e JOIN items i ON i.code=e.item_code WHERE i.code=%(name_item_100001)s",
        resolved,
    )
    assert scoped.parameters["name_item_100001"] == 100001
