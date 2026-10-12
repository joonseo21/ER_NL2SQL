# evaluations: NL2SQL 실제 호출 관찰·실험 도구

에이전트(`er_agent`)를 **실제 Gemini와 읽기 전용 DB**로 돌려 지연·토큰·SQL·응답을 기록하는 도구다. 정답 채점 도구가 아니다. `accuracy`는 항상 null이고 "완료"는 정답을 뜻하지 않는다.

- 현재 상태·남은 결정: [docs/parts/agent.md](../../docs/parts/agent.md)
- 결과의 해석과 근거 수치: [docs/archive/nl2sql_experiments_2026-10-12.md](../../docs/archive/nl2sql_experiments_2026-10-12.md)
- 이 파일: 무엇이 있고, 어떻게 실행하고, 결과가 어디에 있는지

## 폴더 구조

| 경로 | 역할 |
|---|---|
| `run.py` | 관찰 실행 본체. 문항 파일을 읽어 사전 점검 후 문항을 순서대로 실제 호출하고 결과를 `results/`에 기록 |
| `summarize.py` | 결과 폴더를 문항별로 요약하거나(`폴더 경로`), 전체 실행 색인을 출력(`--list`) |
| `explain.py` | 결과 폴더에 기록된 생성 SQL을 `EXPLAIN`(ANALYZE 없음, 실행하지 않음)으로 확인 |
| `report.py` | `N.jsonl` 이벤트 로그에서 호출 수·시간·토큰을 집계(run.py가 사용) |
| `cases/experiment_8.json` | 실험용 8문항(추천 3, 단순 통계 4, 예측 1). 기본 사용 대상 |
| `cases/recommendations.json` | 사용자가 처음 제시한 추천 질문 3개 |
| `experiments/gemini_probe.py` | 간단한 프롬프트의 응답 시간 확인. DB 데이터를 보내지 않음 |
| `experiments/thinking_probe.py` | 모델이 어떤 thinking 설정을 받는지 확인. DB 데이터를 보내지 않음 |
| `results/` | 실행 결과(**Git 제외**). 폴더마다 `NOTE.txt`에 목적·명령·결과 요약이 있다 |

## 사전 조건

- `agent_server`에서 `uv sync`가 끝나 있어야 한다. 명령은 모두 `agent_server` 폴더에서 PowerShell로 실행한다.
- 저장소 루트 `.env`에 `GEMINI_API_KEY`와 `GEMINI_MODEL`이 있어야 한다. 값은 출력·기록되지 않는다.
- 대상 DB는 둘 중 하나다.
  - **테스트베드(권장)**: 로컬 Docker `er-nl2sql-testbed`(`127.0.0.1:15446`). 아래 "테스트베드" 절.
  - **운영**: SSH 터널(`localhost:15432`)이 열려 있어야 하고 `.env`의 `AGENT_DATABASE_URL`을 쓴다. 운영 서버(t2.small, vCPU 1)에 부하를 주므로 꼭 필요할 때만 쓴다.
- 외부 전송: 실행하면 질문, 스키마·지표 설명, 공식 이름 목록, 패치·티어별 표본, 생성·수정된 SQL, 요약용 결과 행이 Google Gemini로 나간다. 닉네임과 raw는 보내지 않는다. 사용자가 승인한 범위 안에서만 실행한다.

## 실행

**1. 테스트베드 준비 확인**

```powershell
docker start er-nl2sql-testbed
```

```powershell
docker exec er-nl2sql-testbed pg_isready -U postgres
```

**2. 사전 점검만 (Gemini 호출 없음, `--allow-live-gemini` 불필요)**

```powershell
$env:AGENT_EVAL_DATABASE_URL = 'postgresql://agent_ro@127.0.0.1:15446/er_analytics'
uv run python -m evaluations.run --target snapshot --expected-database er_analytics --snapshot-id testbed-20261012T055131Z --preflight-only --label "사전 점검"
Remove-Item Env:AGENT_EVAL_DATABASE_URL
```

**3. 관찰 실행 (Gemini 호출 발생)** — 0단계와 같은 설정, 8문항 × 3회

```powershell
$env:AGENT_EVAL_DATABASE_URL = 'postgresql://agent_ro@127.0.0.1:15446/er_analytics'
uv run python -m evaluations.run --target snapshot --expected-database er_analytics --snapshot-id testbed-20261012T055131Z --cases evaluations/cases/experiment_8.json --repeat 3 --thinking-level low --timeout-seconds 60 --continue-on-failure --label "설명 한 줄" --allow-live-gemini
Remove-Item Env:AGENT_EVAL_DATABASE_URL
```

| 옵션 | 의미 |
|---|---|
| `--target snapshot` / `production` | 대상. snapshot은 `AGENT_EVAL_DATABASE_URL`이 필요하고 운영 URL로 대체되지 않는다 |
| `--expected-database` | 연결된 DB 이름이 이 값과 다르면 호출 전에 중단 |
| `--snapshot-id` | snapshot 대상일 때 필수. 사용자 선언 값이며 고정 여부를 증명하지 않는다 |
| `--cases` | 문항 JSON(1~10개, `id`와 `question`만) |
| `--repeat N` | 1~5회. 반복이 바깥 루프라 설정이 시간에 고르게 퍼진다 |
| `--thinking-level low\|high` | Gemini thinking 수준. 생략하면 모델 기본(기본이 느림) |
| `--timeout-seconds N` | 요청 타임아웃. 생략하면 `GEMINI_TIMEOUT_SECONDS` 또는 30초 |
| `--continue-on-failure` | API 실패·예기치 않은 오류 뒤에도 다음 문항을 계속. 생략하면 첫 실패에서 중단 |
| `--label` | `manifest.json`과 색인에 남는 목적 한 줄 |
| `--preflight-only` | DB 점검만 하고 종료 |

같은 기능을 환경변수로도 줄 수 있다: `GEMINI_TIMEOUT_SECONDS`(1~600), `GEMINI_THINKING_LEVEL`(low, high). 명령줄 값이 우선한다.

종료 코드 0은 모든 문항 기록 완료, 2는 설정 오류·사전 점검 실패·일부 미완료다. 어느 쪽이든 폴더는 남는다. stdout의 `status`는 `recorded`, `incomplete`, `preflight_failed`, `configuration_failed`, `preflight_ok` 중 하나다.

**4. 결과 보기**

```powershell
uv run python -m evaluations.summarize --list
```

```powershell
uv run python -m evaluations.summarize evaluations\results\<폴더>
```

요약 표의 열: 종류(answer/notice/clarification), SQL 수정 횟수, 요약 누락(answer인데 summary가 없는 횟수), 결과 클래스(컬럼명·순서·서식·추가 컬럼을 무시한 결과 표의 종류 수), SQL 변형 수, 벽시계 중앙값, LLM 호출 최대 시간, 입력·생각 토큰 중앙값. 결과 클래스는 한쪽에만 있는 코드 컬럼이 있으면 같은 값도 다르게 센다.

**5. 생성 SQL의 실행 계획 확인 (실행하지 않음)**

```powershell
$env:AGENT_EVAL_DATABASE_URL = 'postgresql://agent_ro@127.0.0.1:15446/er_analytics'
uv run python -m evaluations.explain evaluations\results\<폴더> --target snapshot --snapshot-id testbed-20261012T055131Z --case 0
Remove-Item Env:AGENT_EVAL_DATABASE_URL
```

## 코드가 하는 일 (`run.py`의 흐름)

1. `--allow-live-gemini`(또는 `--preflight-only`)를 확인한다. 없으면 설정을 읽기 전에 중단한다.
2. `cases`를 읽어 검증한다(문항 1~10개, id 중복·추가 필드 거부).
3. `selected_settings`가 대상 DB와 Gemini 설정을 고른다. snapshot은 운영 URL로 대체되지 않는다. 옵션이 설정을 덮어쓰고 `Settings`가 값을 다시 검증한다.
4. `results/<UUID>/manifest.json`을 쓴다(대상, 설정, 코드 해시, 문항, 반복, 라벨).
5. `preflight`가 연결된 DB 이름을 확인하고 agent_ro로 이름 카탈로그와 범위 집계를 읽는다. 실패하면 Gemini를 부르지 않고 `preflight_failed`로 끝난다.
6. 반복 × 문항 순서로 `observe_case`가 `er_agent.bootstrap.run_query`를 부른다. 이 안에서 에이전트가 이름·범위 검증 → Gemini SQL 생성 → SQL 가드 → 실행 → 오류 시 1회 수정 → 결과 요약을 수행한다.
7. 호출마다 `N.jsonl`(N은 실행 순서)을 `report.read_metrics`가 읽어 호출 수·시간·토큰을 `observations.json`에 합친다.

## 결과 폴더 읽는 법

| 파일 | 내용 |
|---|---|
| `NOTE.txt` | 첫 줄은 목적. 이어서 실행 명령, 결과, 해석이 있는 문서 위치 |
| `manifest.json` | 시작 시각(UTC), 대상, 스냅샷 ID, git 리비전, 에이전트 소스 해시, 모델, `gemini`(타임아웃·thinking), 반복, 라벨, 문항, `database_context`(사전 점검 값). 사전 점검 실패 시 `preflight_error_category` |
| `observations.json` | 실행 한 건당 항목: `id`, `repetition`, `response`(전체 응답 JSON), `error_category`, `wall_ms`, `logical_calls`, `physical_calls`, `llm_attempt_ms`, `sql_stage_ms`, `sql_repaired`, `tokens`, `exhausted_llm_call`, `accuracy`(null) |
| `N.jsonl` | 한 질문의 이벤트 로그: `query_start`, `llm_attempt`(호출별 시간·토큰·오류 코드), `generation_result`(의도·생성 SQL), `resolved_context`, `sql_attempt`(가드 후 SQL·바인딩·결과·오류), `summary_result`, `query_end` |
| `report.json`, `diagnostics.json` | 평가 도구 이전의 일회성 보고(index-bench, 사전 점검 실패 진단) |

`sql_stage_ms`는 범위 확인·가드·접속·실행을 합친 값이라 순수 DB 시간이 아니다. `tokens`의 항목이 없으면 0이 아니라 null이다.

## 실행 색인 (2026-10-12)

최신 색인은 `summarize --list`로 만든다. 아래는 이 시점의 내용이다.

| id | 시작(KST) | 대상 | gemini | 반복×문항 | 상태 | 목적 |
|---|---|---|---|---|---|---|
| index-be… | - | 복원 DB | - | - | report.json만 | 범위 집계 인덱스 전후 비교. 전 5,016ms 취소 → 후 666/629/619ms |
| f6df0a62 | 10-12 12:51 | 운영 | 기본 | 1×3 | 사전 점검 실패 | V5 직후 첫 시도. 범위 집계 5초 초과(57014), Gemini 0회 |
| 191d5f44 | 10-12 12:57 | 운영 | 기본 | 1×3 | 사전 점검 실패 | 같은 명령 재시도, 동일 실패 |
| e8e9e6ed | 10-12 14:08 | 운영 | 30s/기본 | 1×3 | incomplete 1/3 | 기본 설정에서 첫 호출이 504×3 |
| 0aad7c5d | 10-12 14:11 | 운영 | 180s/기본 | 1×3 | complete | 타임아웃 180초. 호출당 18~82초, 생성 SQL 5초 초과 |
| 8c4308da | 10-12 14:17 | 운영 | 180s/low | 1×3 | complete | + thinking low. 호출당 2.8~4.9초, matchup 답변 |
| 3ae928b1 | 10-12 17:26 | 테스트베드 | 60s/low | 3×8 | complete | 0단계 재현성. 24회 완료, API 실패 0건 |

0aad7c5d와 8c4308da는 당시 `--timeout-seconds`·`--thinking-level` 옵션이 없어 임시 스크립트로 같은 값을 덮어썼다. 지금은 위 옵션으로 같은 실행을 재현할 수 있고, 그 스크립트는 삭제했다.

## 테스트베드 (로컬 Docker)

운영 DB를 EC2에서 `pg_dump -Fc`로 뜬 스냅샷의 복원본이다. DB 구조·데이터에 큰 변경이 없는 한 NL2SQL 성능 테스트베드이자 평가용 고정 스냅샷 후보로 쓴다.

| 항목 | 값 |
|---|---|
| 컨테이너 | `er-nl2sql-testbed`, postgres:17-alpine(PostgreSQL 17.11), `127.0.0.1:15446`, trust 인증 |
| 스냅샷 | 2026-10-12 05:51:31 UTC, 덤프 `er_analytics_testbed_20261012T055131Z.dump`(1,005.5MB), 스냅샷 ID `testbed-20261012T055131Z` |
| 내용 | V6까지 적용, games 59,151 / participants 1,351,240 / users 107,017, 이름 8개 테이블 2,224행 |
| 계정 | `collector`, `agent_ro`(비밀번호 없음, agent_ro는 읽기 전용·5초) |
| 볼륨·덤프 위치 | 볼륨 `er_nl2sql_testbed_data`, 덤프 폴더 `C:\er-analytics\testbed-private`(저장소 밖, 닉네임 포함) |
| 속도 | 같은 쿼리가 운영(t2.small)의 약 1/2~1/3 시간. 로컬 수치에 2~3배를 곱해 읽는다 |

**자원 제한(운영 근사 / 해제)**: 평소에는 1 CPU·2GB로 걸어 둔다.

```powershell
docker update --cpus=1 --memory=2g --memory-swap=2g er-nl2sql-testbed
```

```powershell
docker update --cpus=20 --memory=16g --memory-swap=16g er-nl2sql-testbed
```

두 번째 줄의 20과 16g는 이 PC의 Docker 자원이다. `--cpus=0 --memory=0`은 제한을 풀지 못하고, `--memory`를 올릴 때 `--memory-swap`을 같이 올리지 않으면 거부된다. 확인은 `docker inspect er-nl2sql-testbed --format "{{.HostConfig.NanoCpus}} {{.HostConfig.Memory}}"`.

**처음부터 재구성**

EC2에서 덤프(collector는 멈추지 않아도 된다, 약 8분, 1 vCPU 서버에 부하):

```bash
umask 077
mkdir -p ~/backups
dump_file=~/backups/er_analytics_testbed_$(date -u +%Y%m%dT%H%M%SZ).dump
time docker compose exec -T postgres pg_dump -U postgres -d er_analytics -Fc > "$dump_file"
sha256sum "$dump_file"
```

PC로 복사(`scp`)해 `C:\er-analytics\testbed-private`에 두고 해시를 비교한 뒤:

```powershell
docker run -d --name er-nl2sql-testbed -p 127.0.0.1:15446:5432 -e POSTGRES_USER=postgres -e POSTGRES_HOST_AUTH_METHOD=trust -v er_nl2sql_testbed_data:/var/lib/postgresql/data -v C:\er-analytics\testbed-private:/dump:ro postgres:17-alpine -c max_wal_size=4GB -c maintenance_work_mem=1GB
docker exec er-nl2sql-testbed psql -U postgres -c "CREATE ROLE collector LOGIN" -c "CREATE ROLE agent_ro LOGIN" -c "CREATE DATABASE er_analytics"
docker exec er-nl2sql-testbed psql -U postgres -d er_analytics -c "GRANT CONNECT ON DATABASE er_analytics TO collector, agent_ro" -c "GRANT USAGE ON SCHEMA public TO collector, agent_ro" -c "ALTER ROLE agent_ro SET default_transaction_read_only = on" -c "ALTER ROLE agent_ro SET statement_timeout = '5s'"
docker exec er-nl2sql-testbed pg_restore -U postgres -d er_analytics -j 4 --exit-on-error /dump/<덤프 파일명>
docker exec er-nl2sql-testbed vacuumdb -U postgres -d er_analytics --analyze --jobs 4
```

복원은 약 9분, `vacuumdb`는 약 30초가 걸렸다. `pg_restore`는 통계와 visibility map을 복원하지 않으므로 `vacuumdb`가 꼭 필요하다. 새 스냅샷이면 위 `--snapshot-id`와 문서의 스냅샷 값을 함께 갱신한다.

**제거**: `docker rm -f er-nl2sql-testbed`, `docker volume rm er_nl2sql_testbed_data`.

## 알아 둘 한계

- 같은 설정으로 반복해도 LLM이 만드는 SQL·질문 해석이 달라진다. 설정 비교는 반복(3회 이상)으로 한다.
- 정답 SQL이 없어 정확도는 판단하지 못한다. 정답 세트는 M1-040으로 후속이다.
- 입력 토큰이 호출당 약 49~65k이고 질문당 2~3회 호출한다. 반복·문항 수에 비례해 비용이 든다.
- 로컬 속도는 운영보다 빠르다. 5초 SQL 판단은 로컬에서 비교용으로, 최종 확인은 운영 읽기 전용 소량으로 한다.
- 이 서버(t2.small)에서는 `EXPLAIN (ANALYZE)`의 시간이 부풀려진다. 시간은 psql `\timing`으로 잰다.
- `results/`와 덤프는 Git에 넣지 않는다.
