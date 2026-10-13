"""Internal synchronous query execution behind an ASGI HTTP adapter."""

from __future__ import annotations

import logging
from collections.abc import Callable
from pathlib import Path
from threading import Lock
from typing import Any

import anyio
from fastapi import Depends, FastAPI, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.exception_handlers import http_exception_handler
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.security import APIKeyHeader
from starlette.exceptions import HTTPException

from ..bootstrap import run_query
from ..models.query import QueryRequest, QueryResponse
from ..settings import Settings
from .auth import InternalTokenMiddleware
from .config import HttpSettings
from .errors import AgentHttpError, error_response
from .models import ErrorReply, HealthReply, QueryBody, QueryReply

QueryRunner = Callable[[QueryRequest, Settings, Path], QueryResponse]
logger = logging.getLogger(__name__)


def create_app(
    settings: Settings | None = None,
    http_settings: HttpSettings | None = None,
    *,
    runner: QueryRunner = run_query,
) -> FastAPI:
    settings = settings if settings is not None else Settings.from_env()
    http_settings = http_settings if http_settings is not None else HttpSettings.from_env()
    settings.require_database()
    settings.require_gemini()
    gate = Lock()
    app = FastAPI(
        title="ER Analytics 내부 에이전트 API",
        version="1.0.0",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        servers=[{"url": "http://agent:8000", "description": "Compose 내부 서비스 주소"}],
        redirect_slashes=False,
    )
    app.add_middleware(InternalTokenMiddleware, token=http_settings.internal_token)
    # Middleware performs authentication. This dependency only documents the header in OpenAPI.
    internal_token_header = APIKeyHeader(
        name="X-Agent-Token",
        scheme_name="AgentInternalToken",
        description="Spring과 Python에 비공개로 주입한 AGENT_INTERNAL_TOKEN의 공유 비밀값",
    )

    @app.exception_handler(AgentHttpError)
    async def handle_agent_error(request: Request, error: AgentHttpError) -> JSONResponse:
        return error_response(error.status)

    @app.exception_handler(HTTPException)
    async def handle_http_error(request: Request, error: HTTPException) -> JSONResponse:
        if error.status_code == 400 and request.url.path == "/v1/query":
            # Includes invalid JSON character encoding, which FastAPI reports as a parse error.
            return error_response(422)
        if error.status_code == 401:
            return error_response(401)
        return await http_exception_handler(request, error)

    @app.exception_handler(RequestValidationError)
    async def handle_validation(request: Request, error: RequestValidationError) -> JSONResponse:
        known_fields = {
            "body",
            "question",
            "conditions",
            "patch",
            "mode",
            "versions",
            "major",
            "minor",
            "tier",
            "tier_division",
            "character_code",
        }
        issues = [
            {
                "path": [
                    part if isinstance(part, int) or part in known_fields else "unknown_field"
                    for part in item["loc"]
                ],
                "message": "필드 형식과 조건 조합을 확인해 주세요.",
            }
            for item in error.errors()
        ]
        return error_response(422, issues)

    def execute(body: QueryBody) -> QueryReply:
        try:
            response = runner(body.to_domain(), settings, http_settings.log_path)
            return QueryReply.model_validate(response.to_dict())
        except Exception as error:
            # Logging only the class avoids exposing DB URLs, inputs and upstream credentials.
            logger.error("Agent query failed: %s", type(error).__name__)
            raise AgentHttpError(500) from None
        finally:
            gate.release()

    @app.post(
        "/v1/query",
        response_model=QueryReply,
        operation_id="queryAgent",
        summary="질문하고 답변·안내·되묻기 받기",
        description=(
            "Spring 웹 API만 호출한다. 세 업무 응답은 모두 HTTP 200이다. "
            "동시 질문은 한 개만 처리하고 추가 요청은 대기 없이 503으로 거절한다. "
            "조건이 생략되면 최신 패치·전체 티어다. 자동 재호출은 하지 않는다."
        ),
        tags=["질문"],
        dependencies=[Depends(internal_token_header)],
        responses={
            200: {"description": "공통 질문 응답. 요약 실패 시에도 SQL과 표는 보존한다."},
            401: {
                "model": ErrorReply,
                "description": "내부 토큰 누락·불일치·중복. 본문 검증과 질의 실행 전에 거부한다.",
                "headers": {"WWW-Authenticate": {"schema": {"type": "string"}}},
            },
            422: {
                "model": ErrorReply,
                "description": "요청 형식 오류. 원문 입력을 반환하지 않는다.",
            },
            500: {
                "model": ErrorReply,
                "description": "에이전트 자체 장애. 원본 예외는 반환하지 않는다.",
            },
            503: {
                "model": ErrorReply,
                "description": "다른 질문을 처리 중이다. 대기열에 넣지 않는다.",
            },
        },
    )
    async def query(body: QueryBody) -> QueryReply:
        if not gate.acquire(blocking=False):
            raise AgentHttpError(503)
        # Keep admission occupied until the sync worker ends, including on client cancellation.
        with anyio.CancelScope(shield=True):
            return await run_in_threadpool(execute, body)

    @app.get(
        "/health",
        response_model=HealthReply,
        operation_id="getAgentHealth",
        summary="프로세스 생존 확인",
        description="내부 상태 확인용. 토큰과 외부 의존성 호출 없이 최소 상태만 반환한다.",
        tags=["상태"],
    )
    async def health() -> dict[str, Any]:
        return {"status": "ok"}

    return app
