"""Conservative COUNT lineage checks, not a proof of question/metric correctness."""

from __future__ import annotations

from dataclasses import dataclass

from sqlglot import exp, parse_one
from sqlglot.optimizer.scope import Scope, build_scope

from ..models.evidence import SampleColumn, SampleUnit
from .guard import guard_sql


@dataclass(frozen=True)
class _ColumnOrigin:
    table: str
    column: str
    source_path: tuple[tuple[int, str], ...]


def _projection(scope: Scope, column: str) -> exp.Expression | None:
    matches = [item for item in scope.expression.selects if item.alias_or_name == column]
    if len(matches) != 1:
        return None
    item = matches[0]
    return item.this if isinstance(item, exp.Alias) else item


def _lineage(scope: Scope, column: exp.Column) -> _ColumnOrigin | None:
    path = ((id(scope), column.table),)
    source = scope.sources.get(column.table)
    if isinstance(source, exp.Table):
        return _ColumnOrigin(source.name, column.name, path)
    if isinstance(source, Scope):
        projection = _projection(source, column.name)
        if isinstance(projection, exp.Column):
            origin = _lineage(source, projection)
            if origin is not None:
                return _ColumnOrigin(origin.table, origin.column, path + origin.source_path)
    return None


def _origin(scope: Scope, column: exp.Column) -> tuple[str, str] | None:
    origin = _lineage(scope, column)
    return (origin.table, origin.column) if origin is not None else None


def _appearance_source(scope: Scope) -> SampleUnit | None:
    # Start at a fact source and admit only joins to known unique keys. Child
    # joins, self joins, and grouped/deduplicated CTE rows need DISTINCT counts.
    first = scope.expression.args.get("from_")
    if first is None:
        return None
    alias = first.this.alias_or_name
    source = scope.sources.get(alias)
    if isinstance(source, exp.Table):
        unit = {"participants": SampleUnit.PARTICIPANTS, "games": SampleUnit.GAMES}.get(source.name)
    elif (
        isinstance(source, Scope)
        and not any(source.expression.args.get(key) for key in ("group", "distinct"))
        and not any(
            item.find(exp.AggFunc, exp.Unnest) is not None for item in source.expression.selects
        )
    ):
        unit = _appearance_source(source)
    else:
        return None
    if unit is None:
        return None
    preceding = {alias}
    unique_keys = {
        "games": "game_id",
        "characters": "character_code",
        "weapon_types": "code",
        "items": "code",
        "trait_names": "code",
        "tactical_skills": "code",
        "skill_groups": "code",
        "areas": "code",
        "weather_types": "code",
    }
    for join in scope.expression.args.get("joins") or []:
        target = join.this
        condition = join.args.get("on")
        # games -> participants is a one-to-many INNER join whose resulting
        # rows are participant appearances, as in the reverse join order.
        if (
            isinstance(target, exp.Table)
            and target.name == "participants"
            and unit == SampleUnit.GAMES
            and not join.args.get("side")
            and join.args.get("kind") in (None, "", "INNER")
            and isinstance(condition, exp.EQ)
            and all(isinstance(item, exp.Column) for item in (condition.this, condition.expression))
            and {_origin(scope, item) for item in (condition.this, condition.expression)}
            == {("games", "game_id"), ("participants", "game_id")}
        ):
            unit = SampleUnit.PARTICIPANTS
            preceding.add(target.alias_or_name)
            continue
        if (
            not isinstance(target, exp.Table)
            or target.name not in unique_keys
            or join.args.get("side") not in (None, "", "LEFT")
            or join.args.get("kind") not in (None, "", "INNER")
            or not isinstance(condition, exp.EQ)
            or not all(
                isinstance(item, exp.Column) for item in (condition.this, condition.expression)
            )
        ):
            return None
        pairs = ((condition.this, condition.expression), (condition.expression, condition.this))
        if not any(
            key.table == target.alias_or_name
            and key.name == unique_keys[target.name]
            and value.table in preceding
            for key, value in pairs
        ):
            return None
        preceding.add(target.alias_or_name)
    return unit


def _restricts_target(
    scope: Scope, predicate: exp.Expression | None, source_path: tuple[tuple[int, str], ...]
) -> bool:
    if isinstance(predicate, (exp.Where, exp.Paren)):
        return _restricts_target(scope, predicate.this, source_path)
    if isinstance(predicate, exp.And):
        return _restricts_target(scope, predicate.this, source_path) or _restricts_target(
            scope, predicate.expression, source_path
        )
    if isinstance(predicate, exp.Or):
        return _restricts_target(scope, predicate.this, source_path) and _restricts_target(
            scope, predicate.expression, source_path
        )
    if isinstance(predicate, exp.EQ):
        for column, binding in (
            (predicate.this, predicate.expression),
            (predicate.expression, predicate.this),
        ):
            origin = _lineage(scope, column) if isinstance(column, exp.Column) else None
            if (
                origin is not None
                and (origin.table, origin.column) == ("participants", "character_num")
                and origin.source_path == source_path
                and isinstance(binding, exp.Placeholder)
                and binding.name == "character_code"
            ):
                return True
    return False


def _row_source(scope: Scope) -> tuple[tuple[int, str], ...] | None:
    unit = _appearance_source(scope)
    if unit is None:
        return None
    table = "participants" if unit == SampleUnit.PARTICIPANTS else "games"
    for alias, source in scope.sources.items():
        if isinstance(source, exp.Table) and source.name == table:
            return ((id(scope), alias),)
    alias = scope.expression.args["from_"].this.alias_or_name
    source = scope.sources[alias]
    if isinstance(source, Scope):
        path = _row_source(source)
        return ((id(scope), alias),) + path if path is not None else None
    return None


def _count_source(scope: Scope, target: exp.Expression) -> tuple[tuple[int, str], ...] | None:
    if isinstance(target, exp.Distinct) and len(target.expressions) == 1:
        value = target.expressions[0]
        if isinstance(value, exp.Tuple) and value.expressions:
            value = value.expressions[0]
        origin = _lineage(scope, value) if isinstance(value, exp.Column) else None
        return origin.source_path if origin is not None else None
    return _row_source(scope)


def _count_unit(
    scope: Scope, expression: exp.Expression, require_target: bool
) -> SampleUnit | None:
    if isinstance(expression, exp.Column):
        source = scope.sources.get(expression.table)
        if isinstance(source, Scope):
            projection = _projection(source, expression.name)
            return (
                _count_unit(source, projection, require_target) if projection is not None else None
            )
    if isinstance(expression, exp.Paren):
        return _count_unit(scope, expression.this, require_target)
    if isinstance(expression, exp.Bracket):
        # sqlglot normalizes PostgreSQL array subscripts to zero-based literals.
        if len(expression.expressions) != 1:
            return None
        index = expression.expressions[0]
        if not isinstance(index, exp.Literal) or not index.is_int or int(index.this) < 0:
            return None
        return _array_count_unit(scope, expression.this, require_target)
    predicate = None
    if isinstance(expression, exp.Filter):
        predicate = expression.expression
        expression = expression.this
    if not isinstance(expression, exp.Count):
        return None
    if require_target:
        source_path = _count_source(scope, expression.this)
        if source_path is None or not (
            _restricts_target(scope, scope.expression.args.get("where"), source_path)
            or _restricts_target(scope, predicate, source_path)
        ):
            return None
    return _unit_for_count(scope, expression.this)


def _array_count_unit(
    scope: Scope, expression: exp.Expression, require_target: bool
) -> SampleUnit | None:
    if isinstance(expression, exp.Paren):
        return _array_count_unit(scope, expression.this, require_target)
    if isinstance(expression, exp.Column):
        source = scope.sources.get(expression.table)
        if isinstance(source, Scope):
            projection = _projection(source, expression.name)
            if projection is not None:
                return _array_count_unit(source, projection, require_target)
    if isinstance(expression, exp.ArrayAgg):
        value = expression.this
        if isinstance(value, exp.Order):
            value = value.this
        return _count_unit(scope, value, require_target)
    return None


def _unit_for_count(scope: Scope, target: exp.Expression) -> SampleUnit | None:
    if target is None:
        return None
    if isinstance(target, exp.Distinct):
        values = target.expressions
        if len(values) == 1 and isinstance(values[0], exp.Column):
            return {
                ("participants", "participant_id"): SampleUnit.PARTICIPANTS,
                ("participants", "game_id"): SampleUnit.GAMES,
                ("games", "game_id"): SampleUnit.GAMES,
            }.get(_origin(scope, values[0]))
        if len(values) == 1 and isinstance(values[0], exp.Tuple):
            columns = values[0].expressions
            if (
                len(columns) == 2
                and all(isinstance(column, exp.Column) for column in columns)
                and columns[0].table == columns[1].table
                and _lineage(scope, columns[0]) is not None
                and _lineage(scope, columns[1]) is not None
                and _lineage(scope, columns[0]).source_path
                == _lineage(scope, columns[1]).source_path
                and {_origin(scope, column) for column in columns}
                == {("participants", "game_id"), ("participants", "team_number")}
            ):
                return SampleUnit.TEAMS
        return None
    if isinstance(target, exp.Star) or (
        isinstance(target, exp.Literal) and target.is_int and target.this == "1"
    ):
        return _appearance_source(scope)
    return None


def verified_sample(sql: str, sample: SampleColumn | None, require_target: bool = False) -> bool:
    if sample is None:
        return False
    return sample_columns(sql, (sample.column,), require_target).get(sample.column) == sample


def sample_columns(
    sql: str, columns: tuple[str, ...], require_target: bool = False
) -> dict[str, SampleColumn]:
    """Infer units from COUNT lineage, never from an alias or result magnitude."""
    statement = parse_one(guard_sql(sql).sql, dialect="postgres")
    scope = build_scope(statement)
    if scope is None:
        return {}
    result = {}
    for column in columns:
        projection = _projection(scope, column)
        unit = _count_unit(scope, projection, require_target) if projection is not None else None
        if unit is not None:
            result[column] = SampleColumn(column, unit)
    return result
