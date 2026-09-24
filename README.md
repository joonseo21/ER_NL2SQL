# ER Analytics MVP-0

이터널 리턴 상위 랭커의 스쿼드 경기 데이터를 수집하고, Gemini가 만든 PostgreSQL을 읽기 전용 계정으로 실행하는 walking skeleton이다.

현재 범위는 다음 한 바퀴뿐이다.

```text
ER Open API → Spring Boot 수집기 → PostgreSQL → Python SQL guard → Gemini NL2SQL → 결과 행
```

프론트엔드, Redis, FastAPI/LangGraph, 답변 문장 생성과 배포는 MVP-0 범위 밖이다. 상세 기준은 [MVP-0 개발 기획서](./MVP-0%20개발%20기획서.md), 작업 규칙은 [CLAUDE.md](./CLAUDE.md)를 따른다.

## 구성

- `backend/pipeline`: Spring Boot 수집기
  - DB 영속 큐, GAME 우선 단일 워커
  - 설정 가능한 요청 속도(기본 1 RPS)
  - 403/429/5xx 최대 5회 지수 백오프
  - 경기/참가자 upsert와 원본 `jsonb` 보관
  - API 상태코드와 지연시간 기록
- `agent_server`: Python NL2SQL 실행기
  - Gemini 모델명 환경변수화
  - 스키마 설명과 마스킹된 샘플 행 프롬프트
  - 단일 `SELECT`, 테이블 화이트리스트, `LIMIT 200` 가드
  - `agent_ro` 읽기 전용 연결과 5초 timeout
  - JSONL 실행 로그
- `docs/schema_v1.sql`: 단일 스키마 계약
- `docs/results`: 중간보고서에 옮길 실제 실행 근거

## 로컬 실행 준비

Java 21, Docker Desktop, Python 3.12 이상과 `uv`가 필요하다. Docker PostgreSQL은 호스트의 기존 PostgreSQL과 충돌하지 않도록 `localhost:5433`을 사용한다.

```powershell
Copy-Item .env.example .env
```

`.env`에서 DB 비밀번호, `ER_API_KEY`, `ER_SEASON_ID`, `GEMINI_API_KEY`, `GEMINI_MODEL`을 채운다. 키와 비밀번호는 커밋하지 않는다.

```powershell
docker-compose up -d

cd backend/pipeline
.\gradlew.bat test
$env:ER_COLLECTION_ENABLED = 'true'
.\gradlew.bat bootRun

cd ..\..\agent_server
uv sync
uv run pytest
uv run er-agent manual game-count
uv run er-agent ask "수집된 경기는 몇 판이야?"
```

위 명령처럼 각 프로젝트 폴더에서 실행하면 Spring과 Python 모두 루트 `.env`를 읽는다. 운영체제 환경변수가 있으면 그 값을 우선 사용한다.

## MVP-0 완료 전 남은 실제 검증

- 실제 ER API 응답으로 시즌, 랭커 수, 페이지네이션, 참가자/사망/MMR 필드 확인
- 랭커 50명 수집 후 처리량과 403/429 수치 기록
- `agent_ro`의 SELECT 성공 및 INSERT 거부 확인
- Gemini 테스트 질문 5개 이상 실행 및 실패 분류

확인 전 수치는 추정해서 기록하지 않는다.
