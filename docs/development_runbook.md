# MVP-0 로컬 실행 및 화면 캡처 가이드

현재 구성에는 HTTP 웹 서버가 없다. PostgreSQL만 계속 실행되고, Spring Boot는 수집 큐가 빌 때까지 실행되는 배치 프로그램이며, Python 에이전트는 질문 하나를 처리하는 CLI다.

## 1. 최초 1회 설정

Docker Desktop을 먼저 실행한다. 루트에서 `.env.example`을 `.env`로 복사하고 다음 값을 채운다.

- `POSTGRES_PASSWORD`, `COLLECTOR_PASSWORD`, `AGENT_RO_PASSWORD`
- `AGENT_DATABASE_URL`의 비밀번호를 `AGENT_RO_PASSWORD`와 일치시킨다.
- `ER_API_KEY`, `ER_SEASON_ID`
- `GEMINI_API_KEY`, `GEMINI_MODEL` (`2026-09-24` 기준 예: `gemini-3.8-flash`; 발급 계정에서 사용 가능한 모델을 선택)

로컬 데모용 DB 비밀번호에는 URL 인코딩 문제가 없는 영문·숫자 조합을 권장한다. `.env`는 Git에 추가하지 않는다.

DB를 처음 초기화한 뒤 비밀번호만 바꾸면 기존 볼륨에는 반영되지 않는다. 데이터가 있는 상태에서 `docker-compose down -v`를 실행하면 볼륨이 삭제되므로 임의로 실행하지 않는다.

## 2. PostgreSQL 실행

루트 `C:\er-analytics\ER_NL2SQL`의 PowerShell에서 실행한다.

```powershell
docker-compose up -d
docker-compose ps
docker-compose logs postgres
```

`docker-compose ps`에서 `postgres`가 `healthy`이면 준비 완료다.

읽기 전용 계정 확인:

```powershell
docker-compose exec postgres psql -U agent_ro -d er_analytics -c "SELECT count(*) FROM games;"
docker-compose exec postgres psql -U agent_ro -d er_analytics -c "INSERT INTO characters(character_code) VALUES (999999);"
```

첫 명령은 성공하고 두 번째 명령은 권한 오류가 나야 정상이다.

## 3. IntelliJ에서 Spring 수집기 실행

1. IntelliJ에서 `C:\er-analytics\ER_NL2SQL\backend\pipeline` 폴더를 Gradle 프로젝트로 연다.
2. Project SDK와 Gradle JVM을 Java 21로 선택한다.
3. `PipelineApplication`을 실행하는 Application 구성을 만든다.
4. Main class: `io.eranalytics.pipeline.PipelineApplication`
5. Working directory: `C:\er-analytics\ER_NL2SQL\backend\pipeline`
6. Program arguments: `--er.collection.enabled=true`
7. 실행한다. 루트 `.env`는 `application.yml`에서 자동으로 읽는다.

IntelliJ Run Configuration의 `Environment variables`에 예전 `COLLECTOR_PASSWORD`가 남아 있으면 `.env`보다 우선 적용된다. 별도로 입력할 필요가 없으므로 기존 값은 지우거나 현재 `.env`와 일치시킨다. 프로젝트 루트와 `backend/pipeline` 어느 쪽을 Working directory로 사용해도 `.env`를 찾도록 구성되어 있지만, 위 경로를 권장한다.

터미널에서 동일하게 실행하려면:

```powershell
cd C:\er-analytics\ER_NL2SQL\backend\pipeline
.\gradlew.bat test
.\gradlew.bat bootRun --args="--er.collection.enabled=true"
```

수집기는 캐릭터 메타데이터와 상위 랭커를 받은 뒤 USER/GAME 큐를 처리한다. 큐가 비면 `Collection queue drained`가 출력된다.

## 3-1. Docker에서 Spring 수집기 실행

이미지 빌드와 상시 실행:

```powershell
cd C:\er-analytics\ER_NL2SQL
docker-compose --profile collector build collector
docker-compose --profile collector up -d collector
docker-compose logs -f collector
```

컨테이너에서는 기본적으로 6시간마다 수집 사이클을 반복한다. 간격은 루트 `.env`의
`ER_COLLECTION_INTERVAL`로 바꿀 수 있다. 예: `ER_COLLECTION_INTERVAL=12h`.

API 호출을 최소화한 단일 통합 확인은 다음과 같다.

```powershell
docker-compose --profile collector run --rm `
  -e ER_TOP_RANKER_LIMIT=1 `
  -e ER_COLLECTION_CONTINUOUS=false collector
```

상시 수집기만 중지하려면 `docker-compose stop collector`를 사용한다. PostgreSQL 데이터를
보존해야 하므로 `docker-compose down -v`는 실행하지 않는다.

## 4. IntelliJ Database 창에서 결과 확인

IntelliJ Ultimate를 사용한다면 PostgreSQL Data Source를 추가한다.

- Host: `localhost`
- Port: `5433` (호스트에 설치된 PostgreSQL의 5432 포트와 충돌하지 않도록 Docker는 5433 사용)
- Database: `er_analytics`
- User: `agent_ro`
- Password: `.env`의 `AGENT_RO_PASSWORD`

보고서 캡처에 쓸 수 있는 쿼리:

```sql
SELECT count(*) AS games FROM games;

SELECT count(*) AS participants FROM participants;

SELECT status, job_type, count(*)
FROM collect_queue
GROUP BY status, job_type
ORDER BY job_type, status;

SELECT status_code, count(*) AS calls, round(avg(latency_ms), 1) AS avg_latency_ms
FROM api_call_log
GROUP BY status_code
ORDER BY status_code;

SELECT coalesce(c.name_ko, c.name_en, p.character_num::text) AS character,
       count(*) AS picks,
       round(avg(p.game_rank), 2) AS average_rank
FROM participants p
LEFT JOIN characters c ON c.character_code = p.character_num
GROUP BY c.name_ko, c.name_en, p.character_num
ORDER BY picks DESC
LIMIT 10;
```

`collect_queue`와 `api_call_log`는 `agent_ro`에 공개하지 않았으므로 세 번째와 네 번째 쿼리는 `collector` 또는 `postgres` 연결에서만 실행한다.

## 5. VS Code에서 Python NL2SQL 실행

1. VS Code에서 `C:\er-analytics\ER_NL2SQL\agent_server` 폴더를 연다.
2. 터미널에서 `uv sync`를 실행한다.
3. Python 인터프리터로 `C:\er-analytics\ER_NL2SQL\agent_server\.venv\Scripts\python.exe`를 선택한다.
4. 아래 명령을 실행한다. Python도 루트 `.env`를 자동으로 읽는다.

```powershell
cd C:\er-analytics\ER_NL2SQL\agent_server
uv run pytest
uv run er-agent manual game-count
uv run er-agent manual character-picks
uv run er-agent manual ranker-average-rank
uv run er-agent ask "수집된 경기는 몇 판이야?"
uv run er-agent ask "가장 많이 픽된 실험체 상위 10개는?"
```

Gemini가 생성한 SQL과 가드·실행 결과는 `agent_server/nl2sql_log.jsonl`에 기록된다.

## 6. Postman에서 ER Open API 직접 확인

Postman Environment에 다음 변수를 만든다.

- `er_base_url`: `https://open-api.bser.io`
- `er_api_key`: 실제 키. Secret 타입으로 설정한다.
- `season_id`: 확인한 현재 시즌 ID
- `uid`: 랭커 응답에서 확인한 테스트 UID
- `game_id`: 유저 경기 응답에서 확인한 테스트 gameId

모든 요청에 헤더 `x-api-key: {{er_api_key}}`를 넣는다.

```text
GET {{er_base_url}}/v2/data/Season
GET {{er_base_url}}/v1/rank/top/{{season_id}}/3
GET {{er_base_url}}/v1/user/games/uid/{{uid}}
GET {{er_base_url}}/v1/games/{{game_id}}
GET {{er_base_url}}/v2/data/Character
GET {{er_base_url}}/v1/l10n/Korean
```

Postman은 ER API의 실제 응답 구조를 캡처하는 용도다. 현재 Spring 수집기와 Python 에이전트에는 Postman으로 호출할 자체 HTTP 엔드포인트가 없다.

## 7. 보고서 캡처 권장 순서

1. Docker Desktop 또는 `docker-compose ps`: PostgreSQL healthy 상태
2. Postman: topRanks, userGames, 경기 전체 참가자 응답 구조
3. IntelliJ Run 창: Spring Boot 기동과 `Collection queue drained`
4. Database 창: 테이블 목록, 수집 건수, API 상태코드 집계
5. VS Code 터미널: 테스트 통과, 수동 SQL 결과, 자연어 질문 결과
6. `nl2sql_log.jsonl`: 생성 SQL과 가드 통과 기록

공개되는 보고서에는 API 키를 절대 노출하지 않는다. UID와 닉네임도 흐리거나 마스킹하고, 가능하면 집계 결과 화면을 사용한다.

## 8. 스키마 마이그레이션 적용

`docs/schema_v1.sql`은 볼륨을 **처음 만들 때 한 번만** 적용된다. 이후 스키마 변경은 `db/migrations/V{n}__*.sql`에
멱등 SQL로 추가하고 아래처럼 적용한다. Flyway는 아직 도입하지 않았다. 적용된 파일은 수정하지 않고 새 파일을 추가한다.

### 신규 볼륨

`compose.yaml`의 `docker-entrypoint-initdb.d` 마운트가 `010`(v1) → `015`(V2) → `020`(roles) 순서로 자동 적용한다.
새 마이그레이션을 추가하면 이 마운트에도 파일을 추가한다.

### 기존 로컬 볼륨

이미 만들어진 볼륨에는 V2가 자동으로 적용되지 않는다. 로컬 DB의 `games.start_dtm`이 `timestamp`이면 직접 적용한다.

```powershell
cd C:\er-analytics\ER_NL2SQL
Get-Content db\migrations\V2__games_start_dtm_timestamptz.sql |
  docker-compose exec -T postgres psql -v ON_ERROR_STOP=1 -U postgres -d er_analytics
```

### 운영(EC2)

EC2에 SSH로 접속해 `~/ER_NL2SQL`에서 실행한다. EC2에서는 `docker compose`(하이픈 없음)를 쓴다.
접속 주소와 키 경로는 문서에 기록하지 않는다.

1. **운영 규모로 사전 검증한다.** 참가자 수천 행, `raw` 수 KB 이상인 데이터로 `EXPLAIN ANALYZE`를 확인한다.
   행이 몇 개뿐인 DB에서는 계획이 달라 느린 쿼리가 드러나지 않는다. V2의 backfill이 그랬다(운영 규모에서 42초, 개선안 0.2초).
2. 푸시한 뒤 EC2에서 `git pull --ff-only`. 작업 트리가 깨끗한지 먼저 확인한다.
3. **읽기 전용 사전 점검**: `current_database()`, 버전, 테이블 행 수, `collect_queue`의 DONE이 아닌 작업 수, 마지막 `api_call_log` 시각.
   큐가 비어 있을 때 적용한다. `ALTER`는 커밋될 때까지 `games`에 배타 락을 잡는다(`lock_timeout`은 락 대기 상한이지 보유 시간 제한이 아니다).
4. **백업**: 적용 전에 반드시 만들고 목차를 확인한다.

   ```bash
   umask 077 && mkdir -p ~/backups
   F=~/backups/er_analytics_pre_V2_$(date -u +%Y%m%dT%H%M%SZ).dump
   docker exec er-analytics-postgres-1 pg_dump -U postgres -d er_analytics -Fc > "$F"
   docker exec -i er-analytics-postgres-1 pg_restore --list < "$F" | grep "TABLE DATA"
   ```

5. **적용**: 테이블 소유자인 `postgres`로 실행한다(`collector`는 `ALTER` 권한이 없다). 컨테이너 안 소켓 접속이라 비밀번호는 필요 없다.
   적용 전에 파일이 커밋본과 같은지 해시로 확인한다.

   ```bash
   docker exec -i er-analytics-postgres-1 psql -v ON_ERROR_STOP=1 -U postgres -d er_analytics \
     < db/migrations/V2__games_start_dtm_timestamptz.sql
   ```

6. **검증**: 컬럼 타입, 행 수가 그대로인지, 값을 원본(`participants.raw`)과 대조, `agent_ro`·`collector` 권한.
7. **collector 교체**: DB를 먼저 바꾸고 collector를 나중에 교체한다. 옛 collector는 `startDtm`을 파싱하지 못해 항상 NULL을 넣으므로 새 컬럼 타입과 충돌하지 않는다.

   ```bash
   docker compose --profile collector build collector
   docker compose --profile collector up -d --no-deps collector
   ```

   `--no-deps` 없이 `up`하면 `compose.yaml`이 바뀐 postgres 컨테이너까지 재생성된다(볼륨은 유지). 교체 전에 큐가 유휴인지 다시 확인한다.
8. collector는 시작하자마자 사이클을 한 번 실행한다. `Collection queue drained`가 나온 뒤 새로 수집된 행의 값을 확인한다.

어떤 경우에도 `docker compose down -v`는 실행하지 않는다(볼륨 삭제).
