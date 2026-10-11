from __future__ import annotations

import json
from collections.abc import Callable, Iterator
from datetime import UTC, datetime
from typing import Any
from unittest.mock import MagicMock, patch

import pytest

from er_agent.models.context import ContextSnapshot, ScopeBucket, TierSelection
from er_agent.models.names import NameCandidate, NameCatalog, NameCategory
from er_agent.models.query import PatchVersion


@pytest.fixture
def query_snapshot() -> ContextSnapshot:
    day = datetime(2026, 10, 8, tzinfo=UTC)
    return ContextSnapshot(
        41,
        NameCatalog(
            (
                NameCandidate(NameCategory.CHARACTER, 1, "합성 실험체 A"),
                NameCandidate(NameCategory.CHARACTER, 2, "합성 실험체 B"),
                NameCandidate(NameCategory.ITEM, 100001, "합성 장비"),
                NameCandidate(NameCategory.WEAPON, 16, "합성 무기"),
                NameCandidate(NameCategory.TRAIT, 123, "합성 특성"),
                NameCandidate(NameCategory.TACTICAL, 1, "합성 전술"),
            )
        ),
        tuple(
            ScopeBucket(PatchVersion(major, 0), tier, division, 10, day, day, day)
            for major, tier, division in (
                (5, "다이아몬드", 4),
                (5, None, None),
                (4, "다이아몬드", 4),
            )
        ),
        (TierSelection("다이아몬드", 4),),
    )


@pytest.fixture
def generation_text() -> Callable[..., str]:
    def encode(
        sql: str | None = "SELECT count(*) AS samples FROM participants", **overrides: Any
    ) -> str:
        intent = dict(
            names=[],
            patch=None,
            tier=None,
            character=None,
            all_characters=False,
            period=None,
            unsupported_reason=None,
        )
        intent.update(overrides)
        return json.dumps({"intent": intent, "sql": sql}, ensure_ascii=False)

    return encode


@pytest.fixture
def fake_client() -> Iterator[MagicMock]:
    client = MagicMock()
    client.__enter__.return_value = client
    with patch("er_agent.bootstrap.create_client", return_value=client):
        yield client
