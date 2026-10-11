from __future__ import annotations

from typing import Any

from sqlglot import exp
from sqlglot.optimizer.scope import traverse_scope

from ..models.context import ResolvedContext
from ..models.names import NameCategory
from .guard import SqlGuardError

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


def validate_bindings(statement: exp.Select, context: ResolvedContext) -> dict[str, int]:
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
