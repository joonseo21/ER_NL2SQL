"""Authenticate before FastAPI reads or validates the query body."""

from __future__ import annotations

from hmac import compare_digest

from starlette.types import ASGIApp, Receive, Scope, Send

from .errors import error_response


class InternalTokenMiddleware:
    def __init__(self, app: ASGIApp, token: str) -> None:
        self.app = app
        self.token = token.encode("ascii")

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] == "http" and scope["path"].rstrip("/") == "/v1/query":
            values = [v for k, v in scope["headers"] if k.lower() == b"x-agent-token"]
            if len(values) != 1 or not compare_digest(values[0], self.token):
                await error_response(401)(scope, receive, send)
                return
        await self.app(scope, receive, send)
