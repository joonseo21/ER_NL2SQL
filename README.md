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

- `backend/pipeline/`: Java 21, Spring Boot 3.5.16 단일 워커. GAME 큐 처리→users의 티어별 후보 선택→경기 목록 페이지 조회를 반복한다. 경기·참가자·users·자식 데이터는 JPA로 저장하고 큐 등의 특수 연산은 SQL로 처리한다. 수집 흐름·사용자 선택·재시도·메타데이터 갱신은 각각 분리되어 있다.
- `agent_server/`: Python CLI, psycopg 3.x, sqlglot, python-dotenv.
- `frontend/`: 미구현. 사용자용 Spring·Python HTTP API도 없다.
- `compose.yaml`, `infra/`: PostgreSQL·collector Docker 실행과 EC2 설치.
- `db/migrations/`: V1부터의 스키마·데이터 변경과 런타임 권한. Flyway로 적용 이력을 관리한다.
- `tools/`: 5단계 운영 복사본 준비·분석 계정 검증. 실제 API 비교는 Gradle `stage5Verification` 태스크이며 기본 테스트에서 제외된다. 실행은 [operations.md](docs/operations.md)의 5단계 절을 따른다.
- `agent_server/schema_context.md`: LLM 실행 입력으로 사용하는 DB 설명.

EC2에 collector·DB를 운영하며 로컬 Python은 SSH 터널로 접근한다. 기존 배포 기록은 시즌 41, 상위 랭커 50명, 0.5 RPS, 사이클 종료 후 6시간 대기다. 로컬 3단계 구현은 users 기반 연속 수집, 기본 0.5 RPS, 재방문 4시간·사람당 30페이지·유휴 5분·메타 갱신 24시간을 사용한다. 운영 적용은 3·4단계 검토 후 5단계에서 진행하며 현재 배포 설정은 새로 조회하지 않았다.

## 빠른 시작

Java 21, Docker, Python 3.12 이상, uv가 필요하다. 기존 `.env`가 없다면 `.env.example`을 복사하고 실제 환경값을 채운다. 비밀값은 Git에 넣지 않는다.

저장소 루트에서:

```powershell
docker compose up -d postgres
docker compose ps
docker compose --profile migrate build migrate
docker compose --profile migrate run --rm migrate info
```

새 빈 DB는 `docker compose --profile migrate run --rm migrate`로 초기화한다. 기존 볼륨은 현재 버전을 확인하고 [DB 변경 절차](docs/operations.md#db-변경과-배포)에 따라 명시적으로 baseline을 등록한다. 자동 baseline은 사용하지 않는다. PostgreSQL initdb는 계정만 생성하며 테이블은 Flyway가 만든다.

수집기 테스트와 로컬 수집 실행:

새 경기 저장 코드는 V3·V4 적용 DB가 필요하며 시작 시 JPA가 스키마를 검증한다. 수집기 시작 시 마이그레이션은 실행하지 않는다. 기존 볼륨의 마이그레이션과 별도 PostgreSQL 통합 테스트는 [operations.md](docs/operations.md)를 따른다.

```powershell
cd backend/pipeline
.\gradlew.bat test
.\gradlew.bat bootRun --args="--er.collection.enabled=true --er.collection.continuous=false"
```

`continuous=false`는 GAME과 조회 가능한 사용자가 모두 없을 때 종료한다. 빈 DB는 랭킹 목록 전체로 첫 시드를 만들므로 위 실행은 소량 검증용 제한이 아니다. 소량 실제 API 검증은 5단계의 격리된 DB와 검증 도구를 사용한다. 예전 `ER_TOP_RANKER_LIMIT`과 `ER_COLLECTION_INTERVAL`은 새 수집기에서 사용하지 않는다.

에이전트 테스트와 조회 (`agent_server/`에서):

```powershell
uv sync
uv run pytest
uv run er-agent manual game-count
uv run er-agent ask "수집된 경기는 몇 판이야?"
```

실제 수집은 ER API 호출과 DB 쓰기를 수행하고 ask는 Gemini quota를 소비한다. 상세 설정과 배포는 [operations.md](docs/operations.md)를 따른다.
