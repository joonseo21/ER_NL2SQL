"""Bind verified names and constrain every physical fact source to local scope.

The model owns aggregate arithmetic, target-character numerator/denominator and
JOIN semantics. This module enforces common scope, not overall answer accuracy.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from sqlglot import exp, parse_one
from sqlglot.optimizer.scope import traverse_scope

from .context import ResolvedContext
from .contracts import NameCategory, PatchMode
from .guard import SqlGuardError, guard_sql
from .query_schema import TABLE_COLUMNS

FACT_TABLES = frozenset(
    {"games", "participants"}
    | {table for table in TABLE_COLUMNS if table.startswith("participant_")}
)
NAME_COLUMNS = {
    NameCategory.CHARACTER: {
        "character_num",
        "character_code",
        "opponent_character_num",
        "killer_character_num",
    },
    NameCategory.WEAPON: {"best_weapon", "mastery_code"},
    NameCategory.ITEM: {"item_code"},
    NameCategory.TRAIT: {"trait_code", "trait_first_core"},
    NameCategory.TACTICAL: {"tactical_skill_group"},
}
NAME_TABLES = {
    "characters": NameCategory.CHARACTER,
    "weapon_types": NameCategory.WEAPON,
    "items": NameCategory.ITEM,
    "trait_names": NameCategory.TRAIT,
    "tactical_skills": NameCategory.TACTICAL,
}
COMMON_COLUMNS = frozenset(
    {
        "season_id",
        "matching_mode",
        "matching_team_mode",
        "version_major",
        "version_minor",
        "tier",
        "tier_division",
        "start_dtm",
    }
)


@dataclass(frozen=True)
class ScopedSql:
    sql: str
    parameters: dict[str, Any]


def name_parameters(context: ResolvedContext) -> dict[str, int]:
    character = context.applied_conditions.conditions.character_code
    result = {
        f"name_{name.candidate.category.value}_{name.candidate.code}": name.candidate.code
        for name in context.names
        if not (
            name.candidate.category == NameCategory.CHARACTER and name.candidate.code == character
        )
    }
    if character is not None:
        result["character_code"] = character
    return result


def _name_category(column: exp.Column, sources: dict[str, Any]) -> NameCategory | None:
    for category, names in NAME_COLUMNS.items():
        if column.name in names:
            return category
    source = sources.get(column.table)
    if column.name == "code" and isinstance(source, exp.Table):
        return NAME_TABLES.get(source.name)
    return None


def _validate_bindings(statement: exp.Select, context: ResolvedContext) -> dict[str, int]:
    parameters = name_parameters(context)
    used = {node.name for node in statement.find_all(exp.Placeholder)}
    if used != set(parameters):
        raise SqlGuardError("Use every verified name/target binding and no other placeholders")
    predicates = (
        exp.EQ,
        exp.NEQ,
        exp.In,
        exp.GT,
        exp.GTE,
        exp.LT,
        exp.LTE,
        exp.Between,
        exp.Like,
        exp.ILike,
        exp.Is,
    )
    verified: set[str] = set()
    for scope in traverse_scope(statement):
        for node in scope.walk():
            if not isinstance(node, predicates):
                continue
            columns = list(node.find_all(exp.Column))
            placeholders = list(node.find_all(exp.Placeholder))
            literals = list(node.find_all(exp.Literal))
            if (literals or placeholders) and any(c.name in COMMON_COLUMNS for c in columns):
                raise SqlGuardError("Common scope filters are supplied by the server")
            categories = {_name_category(column, scope.sources) for column in columns} - {None}
            if literals and (categories or any(c.name == "name_ko" for c in columns)):
                raise SqlGuardError("Name/code filters must use verified bindings, not literals")
            for placeholder in placeholders:
                category = (
                    NameCategory.CHARACTER
                    if placeholder.name == "character_code"
                    else NameCategory(placeholder.name.split("_")[1])
                )
                if category not in categories:
                    raise SqlGuardError("Name binding must compare the matching category code")
                verified.add(placeholder.name)
    if verified != used:
        raise SqlGuardError("Name bindings must be used in code predicates")
    return parameters


def _game_filter(context: ResolvedContext, parameters: dict[str, Any], alias: str) -> str:
    parameters["__er_season"] = context.applied_conditions.season_id
    clauses = [
        f"{alias}.season_id = %(__er_season)s",
        f"{alias}.matching_mode = 3",
        f"{alias}.matching_team_mode = 3",
    ]
    patch = context.applied_conditions.conditions.patch
    if patch.mode == PatchMode.EXACT:
        versions = []
        for index, version in enumerate(patch.versions):
            parameters[f"__er_major_{index}"] = version.major
            parameters[f"__er_minor_{index}"] = version.minor
            versions.append(
                f"({alias}.version_major = %(__er_major_{index})s "
                f"AND {alias}.version_minor = %(__er_minor_{index})s)"
            )
        clauses.append("(" + " OR ".join(versions) + ")")
    if context.period:
        parameters["__er_start"] = context.period.start
        parameters["__er_end"] = context.period.end
        clauses.extend(
            [f"{alias}.start_dtm >= %(__er_start)s", f"{alias}.start_dtm < %(__er_end)s"]
        )
    return " AND ".join(clauses)


def _participant_filter(context: ResolvedContext, parameters: dict[str, Any], alias: str) -> str:
    game_filter = _game_filter(context, parameters, "__er_game")
    clauses = [
        f"EXISTS (SELECT 1 FROM games __er_game WHERE "
        f"__er_game.game_id = {alias}.game_id AND {game_filter})"
    ]
    conditions = context.applied_conditions.conditions
    if conditions.tier == "미확인":
        clauses.append(f"{alias}.tier IS NULL")
    elif conditions.tier is not None:
        parameters["__er_tier"] = conditions.tier
        clauses.append(f"{alias}.tier = %(__er_tier)s")
    if conditions.tier_division is not None:
        parameters["__er_division"] = conditions.tier_division
        clauses.append(f"{alias}.tier_division = %(__er_division)s")
    # Never prefilter character here: pick-rate denominator includes all characters.
    return " AND ".join(clauses)


def _filtered_source(
    table: str, context: ResolvedContext, parameters: dict[str, Any]
) -> exp.Select:
    alias = "__er_source"
    if table == "games":
        where = _game_filter(context, parameters, alias)
        # A tier-qualified game count is the games containing that participant tier.
        conditions = context.applied_conditions.conditions
        if conditions.tier is not None:
            participant_filter = _participant_filter(context, parameters, "__er_participant")
            where += (
                f" AND EXISTS (SELECT 1 FROM participants __er_participant WHERE "
                f"__er_participant.game_id = {alias}.game_id AND {participant_filter})"
            )
    elif table == "participants":
        where = _participant_filter(context, parameters, alias)
    else:
        participant_filter = _participant_filter(context, parameters, "__er_participant")
        where = (
            f"EXISTS (SELECT 1 FROM participants __er_participant WHERE "
            f"__er_participant.participant_id = {alias}.participant_id "
            f"AND {participant_filter})"
        )
    # Identifiers come only from the existing allowlist; values use psycopg bindings.
    columns = ", ".join(f"{alias}.{column}" for column in TABLE_COLUMNS[table])
    return parse_one(f"SELECT {columns} FROM {table} {alias} WHERE {where}", dialect="postgres")


def scope_sql(model_sql: str, context: ResolvedContext) -> ScopedSql:
    statement = parse_one(guard_sql(model_sql).sql, dialect="postgres")
    parameters: dict[str, Any] = _validate_bindings(statement, context)
    # Resolve physical sources before inserting wrappers; CTE aliases are untouched.
    sources = [
        source
        for scope in traverse_scope(statement)
        for _, source in scope.selected_sources.values()
        if isinstance(source, exp.Table)
    ]
    if not any(source.name in FACT_TABLES for source in sources):
        raise SqlGuardError("Analytics SQL requires a scoped game/participant fact source")
    for source in sources:
        if source.name in FACT_TABLES:
            source.replace(
                exp.Subquery(
                    this=_filtered_source(source.name, context, parameters),
                    alias=source.args["alias"].copy(),
                )
            )
    # The wrapped SQL must pass the same allowlist/SELECT/row-limit guard again.
    return ScopedSql(guard_sql(statement.sql(dialect="postgres")).sql, parameters)
