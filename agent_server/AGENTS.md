# agent 파트 (NL2SQL 에이전트)

공통 규칙은 [CLAUDE.md](../CLAUDE.md)다. 갱신일: 2026-10-28. 현재 구현·검증 범위와 남은 일은 [agent 파트 문서](../docs/parts/agent.md)의 첫 요약에서 확인한다.

## 담당

- `agent_server/`: Python CLI와 **구현된 내부 FastAPI**, models/query/sql/database/generation/recording/http, settings, evaluations, schema_context.md, metrics.md, 테스트와 의존성 설정.
- 공용 `compose.yaml`의 agent 서비스와 `.env.example`의 에이전트 변수. 다른 서비스·변수는 담당 밖이다.
- 공통 규칙상 `tools/` Python 검증 스크립트도 관련 범위다. 이번 실패 안내 작업의 코드 범위는 `agent_server/`이며 tools는 검사 대상에 포함하지만 수정하지 않는다.

## 읽을 문서

- 공통 결정·발견사항·미결정: [spec](../docs/spec.md).
- 현재 상태·요구사항·인계: [agent](../docs/parts/agent.md). 완료한 작업의 검증·미실행·운영 적용 여부를 구분해 갱신한다.
- 주인인 계약: [agent-http](../docs/contracts/agent-http.md). 읽기 전용인 계약: [database](../docs/contracts/database.md).
- 실행·접속·배포·검사 명령: 필요한 절의 [operations](../docs/operations.md). 평가 도구 사용법: [evaluations 안내](evaluations/README.md).
- 다른 파트의 docs/parts는 읽지 않는다. archive는 기본 읽기 대상이 아니며, 실제 측정 등 근거가 꼭 필요할 때 해당 절만 읽는다.

## 담당 밖

- `backend/`, `frontend/`, `db/migrations/` 코드는 기본 읽기/수정 대상이 아니다. DB 범위는 database 계약과 `sql/schema.py`로 대조한다.
- 스키마·권한 변경이 필요하면 구현하지 않고 담당자에게 변경 요청을 전달한다. DB 계약 수정이 금지된 작업에서는 보고의 기획 전달 사항에 적는다.
- 원인 확인에 담당 밖 코드가 꼭 필요하면 파일·이유를 먼저 설명하고 승인된 파일만 읽는다.
- docs는 Git 제외다. 편집 대상마다 시작 시각·해시를 기록하고 쓰기 직전 다시 읽어 동시 변경을 확인한다. 기존 미커밋 변경은 덮어쓰지 않는다.

## 계약을 바꿀 때

- 요청·응답 형식(`models/query.py`, `http/models.py`)을 바꾸면 같은 작업에서 agent-http와 실제 생성 OpenAPI를 맞춘다.
- 필드 삭제·이름/의미 변경은 api-server에 영향이 있으므로 먼저 사용자에게 알린다. 선택 필드 추가는 기존 규칙대로 진행한다.
- 문서 정리에서는 코드에 맞춰 설명만 고치며 형식·정책을 새로 구현하거나 미결정을 확정하지 않는다.

## 검증

`agent_server`에서 PowerShell로 실행한다. 자동 수정 없는 확인 명령이다.

```powershell
uv run ruff check . ../tools
uv run ruff format --check . ../tools
uv run pytest -m "not postgres" -q
```

기존 환경만 사용할 때 `uv run --offline --no-sync ...`로 의존성 갱신을 피할 수 있다. offline은 uv 다운로드 설정이며 테스트/모델 호출의 네트워크 차단 옵션이 아니다. 위 pytest는 모의 호출을 쓰며 PostgreSQL 표시 테스트를 제외한다. DB 통합 실행 조건은 operations를 따르고, 실제 모델/운영 접속은 승인된 작업에서만 한다. 문서만 고치는 작업에 `--fix`나 자동 format을 실행하지 않는다.

## 시스템 실패 안내와 진단 기록

이용자용 한국어 실패 안내 개선 결정(2026-10-28, spec D-23 ②)에 따라 모델 호출·조회 실행 실패의 message는 상황과 할 행동을 한국어로 쓴다. 내부 용어/모델 이름/원인 문자열을 이용자에게 표시하지 않고 기존 JSONL 진단 필드에 유지한다. 문구 선택은 query/notice_messages.py, 규칙은 [agent-http](../docs/contracts/agent-http.md#오류와-재호출)를 따른다. 질문 횟수 설명은 화면 책임이며 에이전트 문구에 넣지 않는다. 가드 거부·지원 불가·이름 되묻기·요약 실패 경고는 이번 변경 범위 밖이다. 응답 형식/HTTP 상태와 분류·재시도·수정 정책을 문구 변경 때문에 바꾸지 않는다.
