# ER Analytics

이터널 리턴 경기 데이터를 수집하고 한국어 질문을 SQL로 변환해 PostgreSQL 실행 결과를 제공하는 비영리 학술 프로젝트다. 중간보고서는 제출됐으며 MVP-1 범위는 확정됐다. 세부 결정과 초기 개발을 진행 중이다.

## 문서 안내

| 문서 | 역할 |
|---|---|
| [CLAUDE.md](CLAUDE.md) | 개발 규칙과 검증·문서 갱신 책임 |
| [docs/spec.md](docs/spec.md) | 현재 구현, 발견사항, MVP-1 요구사항과 기획·개발 전달 |
| [docs/operations.md](docs/operations.md) | 환경 설정, 실행, 배포, 백업, 마이그레이션 |
| [docs/api_findings.md](docs/api_findings.md) | API 실측 사실과 미확인 사항 |

기획 에이전트는 위 자료로 판단하고 개발 에이전트는 코드·실행 결과를 확인해 자료를 갱신한다. [docs/archive](docs/archive)의 과거 기획과 결과는 현재 요구사항으로 사용하지 않는다.

## 구성

```text
ER Open API → Spring collector → PostgreSQL
                                  ↑
                         Python CLI ↔ Gemini
```

- `backend/pipeline/`: Java 21, Spring Boot 3.5.16, JdbcTemplate 기반 단일 워커. collection/erapi/config 패키지로 구성한다.
- `agent_server/`: Python CLI, psycopg 3.x, sqlglot, python-dotenv.
- `frontend/`: 미구현. 사용자용 Spring·Python HTTP API도 없다.
- `compose.yaml`, `infra/`: PostgreSQL·collector Docker 실행과 EC2 설치.
- `docs/schema_v1.sql`, `db/migrations/`: 최초 스키마와 후속 변경.
- `agent_server/schema_context.md`: LLM 실행 입력으로 사용하는 DB 설명.

EC2에 collector·DB를 운영하며 로컬 Python은 SSH 터널로 접근한다. 사용자가 확인한 운영 조건은 시즌 41, 상위 랭커 50명, 0.5 RPS, 사이클 종료 후 6시간 대기다. 현재 배포 설정은 새로 조회하지 않았다. 코드 기본 RPS는 1.0이므로 운영 `.env`에 `ER_REQUESTS_PER_SECOND=0.5`를 명시한다.

## 빠른 시작

Java 21, Docker, Python 3.12 이상, uv가 필요하다. 기존 `.env`가 없다면 `.env.example`을 복사하고 실제 환경값을 채운다. 비밀값은 Git에 넣지 않는다.

저장소 루트에서:

```powershell
docker compose up -d postgres
docker compose ps
```

수집기 테스트와 로컬 단일 사이클:

```powershell
cd backend/pipeline
.\gradlew.bat test
.\gradlew.bat bootRun --args="--er.collection.enabled=true --er.collection.continuous=false"
```

에이전트 테스트와 조회 (`agent_server/`에서):

```powershell
uv sync
uv run pytest
uv run er-agent manual game-count
uv run er-agent ask "수집된 경기는 몇 판이야?"
```

실제 수집은 ER API 호출과 DB 쓰기를 수행하고 ask는 Gemini quota를 소비한다. 상세 설정과 배포는 [operations.md](docs/operations.md)를 따른다.
