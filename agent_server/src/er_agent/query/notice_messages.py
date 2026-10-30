"""User-facing wording for existing failures; retry and recording policies stay separate."""

from ..models.errors import LlmCallError

_TEMPORARY_MODEL_CODES = frozenset({408, 429, 500, 502, 503, 504})
_TEMPORARY_MODEL_CATEGORIES = frozenset(
    {"unavailable", "rate_limited", "timeout", "empty_response", "incomplete_response"}
)


def model_failure_message(error: LlmCallError) -> str:
    if error.code in _TEMPORARY_MODEL_CODES or (
        error.code is None and error.category in _TEMPORARY_MODEL_CATEGORIES
    ):
        return "답변을 만드는 모델이 지금 응답하지 못했습니다. 잠시 뒤 다시 질문해 주세요."
    return "답변을 만드는 중 문제가 생겼습니다. 같은 문제가 계속되면 운영자에게 알려 주세요."


def query_failure_message(error: str | None) -> str:
    # execute_attempt already records this safe classification. Do not reinterpret guard errors.
    if error == "database: SQLSTATE 57014":
        return (
            "조회에 시간이 너무 오래 걸렸습니다. "
            "패치, 티어, 실험체 조건을 좁히거나 질문 범위를 줄여 다시 질문해 주세요."
        )
    if error is not None and error.startswith("database: SQLSTATE "):
        return (
            "조회를 끝내지 못했습니다. "
            "질문을 조금 다르게 표현하거나 조건을 바꿔 다시 질문해 주세요."
        )
    return "SQL을 실행하지 못했습니다. 질문을 다시 제출해 주세요."
