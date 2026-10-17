import re
from unittest.mock import patch

import pytest

from er_agent.generation.prompts import METRICS_PATH, build_prompt
from er_agent.sql.guard import guard_sql


def test_prompt_uses_metric_document_as_its_single_policy_source() -> None:
    text = METRICS_PATH.read_text(encoding="utf-8")
    prompt = build_prompt("실험체 픽률을 알려줘", {})
    assert prompt.count(text) == 1
    assert "계산 대상 실험체의 조건은 분자에 적용하고 분모에서 제외" in prompt
    assert "승률에 적용하지 않는다" in prompt
    with patch("er_agent.generation.prompts.METRICS_PATH") as source:
        source.read_text.return_value = "검토한 지표 정의"
        assert "검토한 지표 정의" in build_prompt("질문", {})


def test_missing_metric_document_stops_prompt_building() -> None:
    with patch("er_agent.generation.prompts.METRICS_PATH") as source:
        source.read_text.side_effect = FileNotFoundError
        with pytest.raises(FileNotFoundError):
            build_prompt("픽률", {})


def test_documented_pick_rate_sql_passes_existing_guard() -> None:
    text = METRICS_PATH.read_text(encoding="utf-8")
    examples = re.findall(r"```sql\n(.*?)```", text, flags=re.DOTALL)
    assert len(examples) == 2  # pick rate, then patch comparison
    for example in examples:
        assert "LIMIT" in guard_sql(example).sql


def test_generation_prompt_tells_the_model_to_compare_patches_through_intent() -> None:
    from er_agent.generation.prompts import SQL_SCOPE_RULES, build_generation_prompt
    from er_agent.models.context import ContextSnapshot
    from er_agent.models.names import NameCatalog
    from er_agent.models.query import QueryConditions, QueryRequest

    snapshot = ContextSnapshot(41, NameCatalog(()), (), ())
    prompt = build_generation_prompt(QueryRequest("질문", QueryConditions()), snapshot)
    assert "set\nintent.patch to mode exact with BOTH versions" in prompt
    assert "지난 패치보다" in prompt
    # The SQL rules are shared with the repair prompt.
    assert "ARRAY_AGG(value ORDER BY version_major, version_minor)" in SQL_SCOPE_RULES
    assert SQL_SCOPE_RULES in prompt
