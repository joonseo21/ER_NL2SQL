import re
from unittest.mock import patch

import pytest

from er_agent.guard import guard_sql
from er_agent.prompt import METRICS_PATH, build_prompt


def test_prompt_uses_metric_document_as_its_single_policy_source() -> None:
    text = METRICS_PATH.read_text(encoding="utf-8")
    prompt = build_prompt("실험체 픽률을 알려줘", {})
    assert prompt.count(text) == 1
    assert "계산 대상 실험체의 조건은 분자에 적용하고 분모에서 제외" in prompt
    assert "승률에 적용하지 않는다" in prompt
    with patch("er_agent.prompt.METRICS_PATH") as source:
        source.read_text.return_value = "검토한 지표 정의"
        assert "검토한 지표 정의" in build_prompt("질문", {})


def test_missing_metric_document_stops_prompt_building() -> None:
    with patch("er_agent.prompt.METRICS_PATH") as source:
        source.read_text.side_effect = FileNotFoundError
        with pytest.raises(FileNotFoundError):
            build_prompt("픽률", {})


def test_documented_pick_rate_sql_passes_existing_guard() -> None:
    text = METRICS_PATH.read_text(encoding="utf-8")
    examples = re.findall(r"```sql\n(.*?)```", text, flags=re.DOTALL)
    assert len(examples) == 1
    guarded = guard_sql(examples[0])
    assert "LIMIT 200" in guarded.sql
