# agent 파트 (NL2SQL 에이전트)

공통 규칙은 [CLAUDE.md](../CLAUDE.md)다. 이 파일은 이 파트의 범위만 정한다.

## 담당

- `agent_server/`: Python 에이전트(CLI, 이후 FastAPI), schema_context.md, metrics.md
- `tools/`의 Python 검증 스크립트

## 읽을 문서

- 공통 결정·발견사항·미결정: [docs/spec.md](../docs/spec.md)
- 파트 현황과 요구사항: [docs/parts/agent.md](../docs/parts/agent.md). 작업을 마치면 이 문서의 현재 상태와 개발 전달 기록을 갱신한다.
- 다른 파트의 문서(docs/parts의 나머지)는 읽지 않는다.
- 주인인 계약: [docs/contracts/agent-http.md](../docs/contracts/agent-http.md)
- 읽는 계약: [docs/contracts/database.md](../docs/contracts/database.md)

## 담당 밖

- `backend/`, `frontend/`, `db/migrations/`의 코드는 읽지 않는다. 조회할 수 있는 테이블과 컬럼은 database 계약과 `src/er_agent/sql/schema.py`로 안다.
- 스키마나 권한을 바꿔야 하면 직접 고치지 않고 database 계약의 "변경 요청"에 적고 사용자에게 알린다.
- 담당 밖 코드를 봐야 원인을 알 수 있으면 사용자에게 어떤 파일이 왜 필요한지 말하고 승인받은 파일만 본다.

## 계약을 바꿀 때

- 요청·응답 형식(`models/query.py`와 그 안에서 쓰는 모델)을 바꾸면 같은 작업에서 agent-http 계약을 고친다.
- 필드 삭제·이름 변경·의미 변경은 api-server가 쓰고 있을 수 있으므로 먼저 사용자에게 알린다. 선택 필드 추가는 바로 해도 된다.

## 검증

```powershell
uv run ruff check . ../tools --fix
uv run ruff format . ../tools
uv run ruff check . ../tools
uv run pytest -m "not postgres" -q
```

DB 통합 테스트의 실행 조건은 operations를 따른다. `uv`는 Windows 셸에서만 실행한다.
