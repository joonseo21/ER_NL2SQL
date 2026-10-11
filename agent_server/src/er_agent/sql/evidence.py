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
    # Only a flat source or a participant -> game many-to-one join is certified.
    # A subquery/CTE with DISTINCT/GROUP/aggregation could change the unit.
    tables = [source for source in scope.sources.values() if isinstance(source, exp.Table)]
    if len(tables) != len(scope.sources):
        if len(scope.sources) == 1:
            source = next(iter(scope.sources.values()))
            if (
                isinstance(source, Scope)
                and not any(source.expression.args.get(key) for key in ("group", "distinct"))
                and not any(
                    item.find(exp.AggFunc, exp.Unnest) is not None
                    for item in source.expression.selects
                )
            ):
                return _appearance_source(source)
        return None
    if len(tables) == 1:
        return {"participants": SampleUnit.PARTICIPANTS, "games": SampleUnit.GAMES}.get(
            tables[0].name
        )
    if sorted(table.name for table in tables) != ["games", "participants"]:
        return None
    joins = scope.expression.args.get("joins") or []
    if len(joins) != 1 or joins[0].args.get("side") not in (None, "", "LEFT"):
        return None
    condition = joins[0].args.get("on")
    if isinstance(condition, exp.EQ) and all(
        isinstance(item, exp.Column) for item in (condition.this, condition.expression)
    ):
        origins = {_origin(scope, condition.this), _origin(scope, condition.expression)}
        if origins == {("games", "game_id"), ("participants", "game_id")}:
            # LEFT JOIN from games could add empty game rows, so reject that case.
            first = scope.expression.args["from_"].this
            if (
                not joins[0].args.get("side")
                or scope.sources[first.alias_or_name].name == "participants"
            ):
                return SampleUnit.PARTICIPANTS
    return None


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
        if isinstance(source, Scope) and len(scope.sources) == 1:
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
    statement = parse_one(guard_sql(sql).sql, dialect="postgres")
    scope = build_scope(statement)
    if scope is None:
        return False
    projection = _projection(scope, sample.column)
    return projection is not None and _count_unit(scope, projection, require_target) == sample.unit
