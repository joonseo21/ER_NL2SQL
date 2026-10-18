from __future__ import annotations

from typing import Any

import pytest

from er_agent.database import context_repository
from er_agent.database.name_choice import NameRow, one_code_per_name
from er_agent.models.names import NameCategory, NameClarification, NameTerm, ResolvedName
from er_agent.query.name_resolver import NameResolver


def codes(rows: list[NameRow]) -> list[int]:
    return [row.code for row in one_code_per_name(rows)]


def test_a_name_used_in_matches_wins_even_when_its_code_is_higher() -> None:
    # 철갑탄: the unused code is lower, but the code seen in matches must be kept.
    rows = [NameRow(7010101, "철갑탄", False, "None"), NameRow(7310401, "철갑탄", True, "Sub1")]
    assert codes(rows) == [7310401]


def test_a_used_code_beats_a_lower_unused_code() -> None:
    rows = [NameRow(131501, "데스애더퀸-MT", False), NameRow(601501, "데스애더퀸-MT", True)]
    assert codes(rows) == [601501]


def test_cobalt_traits_lose_to_every_other_trait_even_when_used() -> None:
    rows = [
        NameRow(7922702, "가시 덤불", True, "Cobalt"),
        NameRow(7210101, "가시 덤불", None, "Sub1"),
    ]
    assert codes(rows) == [7210101]


def test_core_trait_beats_other_kinds_when_usage_does_not_decide() -> None:
    rows = [NameRow(100, "합성", None, "Sub1"), NameRow(200, "합성", None, "Core")]
    assert codes(rows) == [200]


@pytest.mark.parametrize("used", [(False, False), (None, None), (None, False)])
def test_the_lowest_code_is_the_deterministic_fallback(
    used: tuple[bool | None, bool | None],
) -> None:
    rows = [NameRow(500160, "초월", used[0]), NameRow(70, "초월", used[1])]
    assert codes(rows) == [70]
    assert codes(list(reversed(rows))) == [70]


def test_names_compare_after_boundary_whitespace_is_removed_and_output_is_ordered() -> None:
    rows = [NameRow(3, "셋"), NameRow(1, "하나"), NameRow(2, "하나 "), NameRow(4, " 하나")]
    assert codes(rows) == [1, 3]


class FakeConnection:
    """Answers the name loader's queries from in-memory tables."""

    def __init__(self, tables: dict[str, list[dict[str, Any]]], v10: bool) -> None:
        self.tables = tables
        self.v10 = v10
        self.queries: list[str] = []
        self.rows: list[dict[str, Any]] = []

    def execute(self, query: str, params: tuple[Any, ...]) -> FakeConnection:
        self.queries.append(query)
        if "information_schema.columns" in query:
            extra = ["seen_in_matches", "usage_season_id"] if self.v10 else []
            self.rows = [
                {"table_name": table, "column_name": column}
                for table in context_repository._EXTRA_NAME_COLUMNS
                for column in [*extra, *(["trait_type"] if table == "trait_names" else [])]
            ]
            return self
        table = query.split(" FROM ")[1].split(" ")[0]
        wanted = [
            column.split(" AS ")[-1]
            for column in query[len("SELECT ") :].split(" FROM ")[0].split(", ")
        ]
        self.rows = [
            {column: row.get(column) for column in wanted}
            for row in sorted(self.tables.get(table, []), key=lambda row: row["code"])
            if row["code"] > params[0]
        ]
        return self

    def fetchall(self) -> list[dict[str, Any]]:
        return self.rows


@pytest.fixture
def tables() -> dict[str, list[dict[str, Any]]]:
    return {
        "characters": [{"code": 1, "name_ko": "셀린"}],
        "tactical_skills": [
            {"code": 70, "name_ko": "초월", "seen_in_matches": True, "usage_season_id": 41},
            {"code": 500160, "name_ko": "초월", "seen_in_matches": False, "usage_season_id": 41},
            {"code": 90, "name_ko": "무효화", "seen_in_matches": False, "usage_season_id": 40},
            {"code": 500180, "name_ko": "무효화", "seen_in_matches": True, "usage_season_id": 40},
        ],
        "trait_names": [
            {
                "code": 7010101,
                "name_ko": "철갑탄",
                "seen_in_matches": False,
                "usage_season_id": 41,
                "trait_type": "None",
            },
            {
                "code": 7310401,
                "name_ko": "철갑탄",
                "seen_in_matches": True,
                "usage_season_id": 41,
                "trait_type": "Sub1",
            },
        ],
        "items": [
            {"code": 10, "name_ko": "합성 장비", "seen_in_matches": None, "usage_season_id": 41}
        ],
    }


def load(tables: dict[str, list[dict[str, Any]]], v10: bool) -> tuple[Any, FakeConnection]:
    connection = FakeConnection(tables, v10)
    return context_repository._load_names(connection, 41), connection  # type: ignore[arg-type]


def test_same_name_codes_collapse_so_the_resolver_no_longer_asks(
    tables: dict[str, list[dict[str, Any]]],
) -> None:
    catalog, _ = load(tables, v10=True)
    resolver = NameResolver(catalog)
    skill = resolver.resolve(NameTerm("초월", NameCategory.TACTICAL))
    trait = resolver.resolve(NameTerm("철갑탄", NameCategory.TRAIT))
    assert isinstance(skill, ResolvedName) and skill.candidate.code == 70
    assert isinstance(trait, ResolvedName) and trait.candidate.code == 7310401


def test_usage_from_another_season_is_unknown_and_falls_back_to_the_lowest_code(
    tables: dict[str, list[dict[str, Any]]],
) -> None:
    # 무효화 was recorded for season 40 only, so season 41 must not trust it: lowest code wins.
    catalog, _ = load(tables, v10=True)
    resolved = NameResolver(catalog).resolve(NameTerm("무효화", NameCategory.TACTICAL))
    assert isinstance(resolved, ResolvedName) and resolved.candidate.code == 90


def test_an_older_database_without_v10_columns_still_loads_and_keeps_the_lowest_code(
    tables: dict[str, list[dict[str, Any]]],
) -> None:
    catalog, connection = load(tables, v10=False)
    assert not any("seen_in_matches" in query for query in connection.queries[1:])
    resolved = NameResolver(catalog).resolve(NameTerm("초월", NameCategory.TACTICAL))
    assert isinstance(resolved, ResolvedName) and resolved.candidate.code == 70
    characters = [e.name for e in catalog.entries if e.category == NameCategory.CHARACTER]
    assert characters == ["셀린"]


def test_characters_and_weapons_keep_asking_when_a_name_is_shared() -> None:
    tables = {"characters": [{"code": 1, "name_ko": "동명"}, {"code": 2, "name_ko": "동명"}]}
    catalog, _ = load(tables, v10=True)
    result = NameResolver(catalog).resolve(NameTerm("동명", NameCategory.CHARACTER))
    assert isinstance(result, NameClarification)
    assert {candidate.code for candidate in result.candidates} == {1, 2}


def test_pagination_reads_every_code(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(context_repository, "MAX_ROWS", 2)
    tables = {"characters": [{"code": code, "name_ko": f"이름{code}"} for code in range(1, 6)]}
    catalog, _ = load(tables, v10=True)
    assert [entry.code for entry in catalog.entries] == [1, 2, 3, 4, 5]
