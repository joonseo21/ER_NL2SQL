"""Stable, safe HTTP errors; never echo raw inputs or upstream exceptions."""

from __future__ import annotations

from typing import Any

from fastapi.responses import JSONResponse

ERRORS = {
    401: ("unauthorized_internal_call", "내부 호출 인증을 확인할 수 없습니다."),
    422: ("invalid_request", "요청 형식을 확인해 주세요."),
    500: ("internal_error", "에이전트가 요청을 처리하지 못했습니다."),
    503: ("service_unavailable", "에이전트를 잠시 사용할 수 없습니다."),
}


def error_response(status: int, issues: list[dict[str, Any]] | None = None) -> JSONResponse:
    code, message = ERRORS[status]
    return JSONResponse(
        status_code=status,
        content={"detail": {"code": code, "message": message, "issues": issues or []}},
        headers={"WWW-Authenticate": 'ApiKey realm="agent"'} if status == 401 else None,
    )


class AgentHttpError(Exception):
    def __init__(self, status: int) -> None:
        self.status = status
