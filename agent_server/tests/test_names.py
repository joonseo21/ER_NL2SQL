import unicodedata
from collections.abc import Callable

import pytest

from er_agent.models.names import (
    NameCandidate,
    NameCatalog,
    NameCategory,
    NameClarification,
    NameTerm,
    ResolvedName,
)
from er_agent.query.name_resolver import NameResolver


@pytest.fixture
def catalog() -> NameCatalog:
    return NameCatalog(
        (
            NameCandidate(NameCategory.CHARACTER, 1, "합성 실험체"),
            NameCandidate(NameCategory.CHARACTER, 2, "다른 실험체"),
            NameCandidate(NameCategory.ITEM, 100, "합성 장비"),
            NameCandidate(NameCategory.ITEM, 101, "중복 이름"),
            NameCandidate(NameCategory.ITEM, 102, "중복 이름"),
            NameCandidate(NameCategory.TRAIT, 200, "합성 특성"),
            NameCandidate(NameCategory.WEAPON, 16, "양손검"),
            NameCandidate(NameCategory.TACTICAL, 300, "합성 특성"),
        )
    )


@pytest.mark.parametrize(
    "category,code,name",
    [
        (NameCategory.CHARACTER, 1, "합성 실험체"),
        (NameCategory.WEAPON, 16, "양손검"),
        (NameCategory.ITEM, 100, "합성 장비"),
        (NameCategory.TRAIT, 200, "합성 특성"),
        (NameCategory.TACTICAL, 300, "합성 특성"),
    ],
)
def test_official_names_resolve_in_all_five_categories(
    catalog: NameCatalog,
    category: NameCategory,
    code: int,
    name: str,
) -> None:
    result = NameResolver(catalog).resolve(NameTerm(name, category))
    assert isinstance(result, ResolvedName)
    assert result.candidate.code == code and result.candidate.category == category


def test_only_boundary_whitespace_and_unicode_form_are_normalized(catalog: NameCatalog) -> None:
    result = NameResolver(catalog).resolve(
        NameTerm(" " + unicodedata.normalize("NFD", "합성 실험체") + " ")
    )
    assert isinstance(result, ResolvedName) and result.candidate.code == 1
    assert isinstance(NameResolver(catalog).resolve(NameTerm("합성실험체")), NameClarification)


@pytest.mark.parametrize(
    "term,expected_codes",
    [
        (NameTerm("중복 이름", NameCategory.ITEM), {101, 102}),
        (NameTerm("합성 특성"), {200, 300}),
    ],
)
def test_exact_homonyms_require_clarification_with_all_candidates(
    catalog: NameCatalog,
    term: NameTerm,
    expected_codes: set[int],
) -> None:
    result = NameResolver(catalog).resolve(term)
    assert isinstance(result, NameClarification)
    assert {candidate.code for candidate in result.candidates} == expected_codes


def test_typo_candidates_are_never_resolved_or_taken_from_other_categories(
    catalog: NameCatalog,
) -> None:
    result = NameResolver(catalog).resolve(NameTerm("합성 장비이", NameCategory.ITEM))
    assert isinstance(result, NameClarification)
    assert result.candidates[0].code == 100
    assert all(candidate.category == NameCategory.ITEM for candidate in result.candidates)
    assert len(result.candidates) <= 5


def test_aliases_and_unknown_names_do_not_automatically_get_a_code(catalog: NameCatalog) -> None:
    assert isinstance(
        NameResolver(catalog).resolve(NameTerm("양검", NameCategory.WEAPON)), NameClarification
    )
    unknown = NameResolver(catalog).resolve(NameTerm("zzzzzzzz", NameCategory.ITEM))
    assert isinstance(unknown, NameClarification) and unknown.candidates == ()
    assert NameResolver(catalog).character(100) is None


@pytest.mark.parametrize(
    "factory",
    [
        lambda: NameTerm(" "),
        lambda: NameTerm("이름", "item"),
        lambda: NameCatalog(("이름",)),
        lambda: NameCatalog(
            (NameCandidate(NameCategory.ITEM, 1, "A"), NameCandidate(NameCategory.ITEM, 1, "B"))
        ),
    ],
)
def test_invalid_catalogs_and_terms_are_rejected(factory: Callable[[], object]) -> None:
    with pytest.raises(ValueError):
        factory()
