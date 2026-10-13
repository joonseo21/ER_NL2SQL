"""Export the runtime OpenAPI without loading secrets or contacting external services."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from ..paths import REPOSITORY_ROOT
from ..settings import Settings
from .app import create_app
from .config import HttpSettings
from .errors import ERRORS


def build_openapi() -> dict[str, Any]:
    app = create_app(Settings("schema-only", "schema-only", "schema-only"), HttpSettings("0" * 64))
    schema = app.openapi()
    schema["info"]["description"] = (
        "실제 FastAPI 서버 구성에서 생성한 내부 API 명세. "
        "Spring은 AGENT_INTERNAL_TOKEN을 X-Agent-Token 헤더에 붙인다. "
        "사용자 인증·초대 코드·사용량은 Spring 책임이다. "
        "호스트 포트와 문서 경로를 공개하지 않는다. "
        "서버는 단일 프로세스에서 동시 질문 하나만 처리한다."
    )
    operation = schema["paths"]["/v1/query"]["post"]
    operation["requestBody"]["content"]["application/json"]["examples"] = {
        "defaults": {
            "summary": "최신 패치·전체 티어",
            "value": {"question": "수집된 경기는 몇 판이야?"},
        }
    }
    samples = REPOSITORY_ROOT / "docs" / "contracts" / "samples"
    if samples.is_dir():
        operation["responses"]["200"]["content"]["application/json"]["examples"] = {
            path.stem: {
                "summary": path.stem,
                "value": json.loads(path.read_text(encoding="utf-8"))
                | {"chart": None, "conversation_context": None},
            }
            for path in sorted(samples.glob("*.json"))
        }
    for status, (code, message) in ERRORS.items():
        operation["responses"][str(status)]["content"]["application/json"]["example"] = {
            "detail": {"code": code, "message": message, "issues": []}
        }
    schema["paths"]["/health"]["get"]["responses"]["200"]["content"]["application/json"][
        "example"
    ] = {"status": "ok"}
    return schema


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    args.output.write_text(
        json.dumps(build_openapi(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


if __name__ == "__main__":
    main()
