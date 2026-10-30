# evaluations: NL2SQL 관찰·채점 도구

갱신일: 2026-10-28, 코드 대조. 선택한 Gemini 또는 OpenAI와 읽기 전용 DB로 질문을 실행해 응답·지연·토큰·SQL을 기록한다. `run.py`의 accuracy는null이며 기록 완료는 정답 판정이 아니다. 정답 표 채점은 `score.py`가 맡는다. 이번 문서 정리에서는 실제 모델/DB에 접속하지 않았다.

- 현재 구현·정식 평가와 시범의 구분: [agent](../../docs/parts/agent.md).
- 현재 DB·비공개 환경 로더·스냅샷 전환·운영/자원 설정: [operations](../../docs/operations.md#로컬-db와-평가-대상).
- 실제 측정 결과는 [시범 보고서](../../docs/archive/nl2sql_pilot_eval_2026-10-17.md), 초기 실험은 [초기 보고서](../../docs/archive/nl2sql_experiments_2026-10-12.md).
- [과거 실행 색인·테스트베드 원문](../../docs/archive/agent_evaluation_history_2026-10-28.md)은 날짜별 이력이다. 옛 V6/5초/비밀번호 없음/자원 설정을 현재 지침으로 쓰지 않는다.

<a id="폴더-구조"></a>

## 도구와 외부 호출 범위

| 도구 | 동작 | 외부 연결 |
|---|---|---|
| `run.py` | 사전 점검 후1~10문항×1~5반복 순차 관찰. 실패 시 중단 또는 계속 | 실제 DB+선택 모델. `--preflight-only`는 DB만 |
| `score.py expect` | 정답 SQL 결과 표/SQL 해시 저장. timeout만 최대3시도 | 실제 DB, 모델 없음 |
| `score.py grade` | 기존 관찰과 저장된 정답 표 채점 | 로컬 파일만 |
| `score.py run` | 질문 파일을10문항 묶음으로 나눠 관찰 후 통합 채점 | 실제 DB+선택 모델 |
| `summarize.py` | 폴더 요약, `--list`로 현재 실행 색인 | 로컬 파일만 |
| `explain.py` | 기록된 SQL에 EXPLAIN(ANALYZE 없음). 모델 SQL을 실행해 결과를 얻는 기능은 아님 | 실제 DB. 자체 expected-database 옵션은 없음 |
| `report.py` | JSONL의 호출/토큰/실행 시간 집계 | 로컬 파일만 |
| `experiments/gemini_probe.py`, `thinking_probe.py` | Gemini 전송/사고 옵션 진단 | 실제 모델 호출, DB 자료 없음 |

`cases/recommendations.json`은 초기3문항, `cases/experiment_8.json`은 반복 관찰용8문항이다. 현재 시범 gold/질문 파일은 저장소 밖 경로로 받는다. `results/`는 Git 제외다. 정식 평가 질문의 확정·사용자 검수·기능별 제외 비교 완료와 시범 도구 구현을 구분한다.

<a id="사전-조건"></a>

## 준비와 공급자 설정

PowerShell에서 agent_server를 작업 폴더로 사용한다. 의존성은 `uv sync`로 준비한다. 실험 전에 operations의 비공개 환경 로더로 정확한 대상 DB·기대 DB명·snapshot-id를 준비한다. 고정15446과 새 전체 복원15448은 서로 다른 데이터이므로 옛 정답 표를 그대로 재사용하지 않는다. 과거 컨테이너 이름·루트 개수·가동 상태를 현재 상태로 간주하지 않는다.

`LLM_PROVIDER`는 gemini(기본)/openai다. 선택 공급자의 `*_API_KEY`, `*_MODEL`이 필요하다. 두 모델 timeout은 기본30초·1~600초. Gemini thinking은 미지정/빈 값이면low, 환경값default이면 모델 기본이며 low/high도 가능하다. OpenAI reasoning은 미지정/빈 값/default이면 옵션을 생략하고 low 등은 명시할 때만 전달한다. 키/비밀번호/실제 접속 문자열을 출력·로그·명령 예시에 넣지 않는다.

실제 관찰은 질문·스키마/지표·공식 이름·범위 표본·SQL·요약용 결과를 **선택 공급자**에게 전송한다. 질문/데이터의 전송 승인을 받은 범위에서만 실행하며, 기본 테스트는 모의 호출이다. `uv --offline`은 패키지 다운로드 옵션이며 실제 모델의 네트워크 호출을 막는 옵션이 아니다.

<a id="실행"></a>

## 관찰 실행

아래 명령은 사용법이며 이번 정리에서 실행하지 않았다. `AGENT_EVAL_DATABASE_URL`, `AGENT_EVAL_DATABASE_NAME`, `AGENT_EVAL_SNAPSHOT_ID`를 비공개 로더로 준비한 뒤 사용한다. `--preflight-only`도 실제 DB 접속이며 현재 코드에서는 선택 공급자의 키/모델 설정 검사도 통과해야 한다.

```powershell
# DB 사전 점검만, 모델 호출 없음
uv run python -m evaluations.run --target snapshot --expected-database $env:AGENT_EVAL_DATABASE_NAME --snapshot-id $env:AGENT_EVAL_SNAPSHOT_ID --preflight-only

# 아래 두 명령은 실제 모델 호출을 수행한다. 승인된 실험에서만 선택해 사용한다.
uv run python -m evaluations.run --target snapshot --expected-database $env:AGENT_EVAL_DATABASE_NAME --snapshot-id $env:AGENT_EVAL_SNAPSHOT_ID --provider gemini --cases evaluations/cases/experiment_8.json --repeat 3 --thinking-level low --timeout-seconds 30 --label "승인된 관찰" --allow-live-llm
uv run python -m evaluations.run --target snapshot --expected-database $env:AGENT_EVAL_DATABASE_NAME --snapshot-id $env:AGENT_EVAL_SNAPSHOT_ID --provider openai --cases evaluations/cases/experiment_8.json --repeat 3 --reasoning-effort low --timeout-seconds 30 --label "승인된 관찰" --allow-live-llm
```

| 옵션 | 코드의 의미 |
|---|---|
| `--target snapshot/production` | snapshot은 별도 AGENT_EVAL_DATABASE_URL·snapshot-id 필수. 기본 운영 URL과 같으면 거부하며 운영으로 대체하지 않음. production은 별도 승인한 작업에서만 사용 |
| `--expected-database` | run의 실제 접속 DB명을 대조하고 다르면 호출 전 중단 |
| `--snapshot-id` | snapshot에서는 필수인 사용자 선언값. DB가 실제 고정돼 있다는 증명은 아님 |
| `--cases`, `--repeat` | 파일은1~10개 id/question만, 중복ID·추가 필드 거부. 반복1~5회, 바깥 루프가 반복 |
| `--provider`, `--model` | 해당 실행의 공급자/모델을 덮어씀. 서비스 배포의 기본 공급자를 바꾸지 않음 |
| `--timeout-seconds` | 선택 공급자의 시도당 timeout을 덮어씀 |
| `--thinking-level low/high` | Gemini 전용. 미지정이면 Settings/환경값을 따름 |
| `--reasoning-effort default/none/minimal/low/medium/high/xhigh/max` | OpenAI 전용. default는 reasoning 생략. 모델별 실제 지원 여부는 코드의 허용 문자열 검사와 별개 |
| `--allow-live-llm` | 실제 모델 실행 명시. 옛 `--allow-live-gemini`는 Gemini에만 호환되며 OpenAI에는 부족 |
| `--preflight-only` | 고정 DB/컨텍스트 점검 후 종료. 위 실제 호출 플래그 없이 가능 |
| `--continue-on-failure`, `--label` | 오류/호출 소진 뒤 계속 여부(기본 중단), manifest/색인의 목적 문구 |

설정/문항 검증→manifest 기록→DB명/agent_ro/이름·범위 점검→반복 관찰→JSONL 지표 합산 순서다. preflight 실패에서는 모델을 호출하지 않는다. SQL20초/가드/수정 정책은 제품 코드와 공유한다. 종료0과 recorded는 관찰 기록 완료, 종료2는 설정·사전 점검·일부 관찰 미완료다. stdout 상태는 recorded/incomplete/preflight_ok/preflight_failed/configuration_failed이며 정확도 통과 여부가 아니다.

<a id="채점-scorepy"></a>

## 채점

gold/질문/정답 표는 저장소 밖 파일을 경로로 전달한다. 기존 시범 파일이 있는 위치는 `C:/er-analytics/pilot-questions`이나, 해당 파일의 검수·스냅샷 준비 상태를 코드만으로 현재 보장할 수 없다. 다음은 경로 자리 표시자를 사용하는 예시다.

```powershell
# 실제 DB에서 정답 표 저장, 모델 호출 없음
uv run python -m evaluations.score expect --gold '<gold.json 경로>' --out '<expected.json 경로>' --expected-database $env:AGENT_EVAL_DATABASE_NAME --snapshot-id $env:AGENT_EVAL_SNAPSHOT_ID
# 로컬 관찰/정답 표만 채점
uv run python -m evaluations.score grade --gold '<gold.json 경로>' --expected '<expected.json 경로>' '<결과폴더1>' '<결과폴더2>'
# 실제 DB/모델 실행 후 채점. 승인된 실험에서만 사용
uv run python -m evaluations.score run --gold '<gold.json 경로>' --expected '<expected.json 경로>' --questions '<questions.json 경로>' --target snapshot --expected-database $env:AGENT_EVAL_DATABASE_NAME --snapshot-id $env:AGENT_EVAL_SNAPSHOT_ID --provider gemini --thinking-level low --repeat 3 --allow-live-llm
```

expect는 agent_ro·읽기 전용·문장당20초로 정답 SQL을 실행하고 timeout만 최대3시도한다. 정답 SQL 해시가 바뀌면 정답 표를 다시 저장해야 하며 stale_answer_key는 정확도 분모에서 제외한다. 정답 표 채점이 요약/안내 문장의 의미까지 자동으로 증명하지는 않는다.

판정 규칙:

| 판정 | 뜻 |
|---|---|
| correct | 응답 유형이 기대와 같고, 답변이면 결과 표가 채점 기준을 만족 |
| wrong_kind | 기대한 응답 유형(답변·안내·되묻기)과 다름 |
| wrong_result | 답변이지만 결과 표가 채점 기준을 만족하지 않음. 이유에 row_count·rows_differ·required_rows_missing·unexpected_rows·too_few_rows |
| wrong_candidates | 되묻기의 후보에 기대한 이름이 없음 |
| unscored | 정답 표가 없거나(no_answer_key) 정답 SQL이 바뀜(stale_answer_key). 정답률 분모에서 뺀다 |
| error | 응답 없이 실행 오류 |

결과 표 비교는 gold.json의 `scoring`을 따른다. `key_columns`(정답 표의 컬럼 이름)의 값만 보며 컬럼 이름·순서·추가 컬럼은 무시한다. 숫자는 0.011 이내이거나 정답 값을 에이전트의 자릿수로 반올림해 같으면 일치로 본다. `mode`는 `all_rows`(행 수와 모든 행 일치), `required`(지정 행 포함), `subset`(에이전트의 모든 행이 정답 표에 있음)이며 `head`·`tail`·`rows`로 반드시 있어야 할 행을 지정한다. `ordered`이면 결과의 첫 `head`행이 정답 표의 첫 `head`행과 같은 순서여야 하며(head_order), `all_rows`에서는 모든 행의 순서가 같아야 한다. 여러 모양의 답을 인정해야 하면 `scoring`을 조합한다(2026-10-17, D-19). `alternatives: [채점…]`은 하나라도 통과하면 정답(모두 실패하면 실패가 가장 적은 쪽의 사유), `all_of: [채점…]`은 모두 통과해야 정답이다. `mode: "grouped"`는 가로 표를 인정하는 채점이다. `group_by`(정답 표의 컬럼)로 정답 표를 묶고(`group_by`를 생략하면 모든 행이 한 묶음이라 전후 비교를 한 행에 나란히 답한 경우를 인정, `row_filter`로 일부 값의 행만, `group_limit`으로 앞의 묶음만), 에이전트의 한 행에 그 묶음의 `value_columns` 값이 컬럼 이름·순서와 무관하게 모두 있으면 통과한다. `identity_columns`(코드나 이름 등)를 주면 그 묶음의 이름표 중 하나도 같은 행에 있어야 한다. 동물이 행인 표와 티어가 행인 표를 각각 대안으로 둘 수 있다. 실패 사유는 `group_missing`이다. 답변의 기준 안내 문구(`required_disclosure`)와 안내·되묻기 문장의 의미는 자동으로 판정하지 않고 `review`에 표시한다. 결과는 `results/score-<UUID>/score.json`과 화면의 표로 남는다.

시간 항목: 관찰마다 전체 시간(`wall_ms`), 선택 모델의 시도별 시간과 합계·최대, SQL 단계별 시간과 합계·최대, SQL 시간 초과 횟수(SQLSTATE 57014), SQL 수정 여부, 논리·물리 호출 수를 남기고 문항·유형·전체별로 중앙값·p90·최대를 낸다. SQL 단계 시간은 범위 적용·가드·접속을 포함해 순수 DB 시간이 아니다. 시간 초과 횟수는 이 항목이 추가된 2026-10-17 이후 run.py 결과에만 있고 이전 결과는 모름(null)으로 둔다.


<a id="결과-폴더-읽는-법"></a>

## 결과 폴더와 진단

| 파일/도구 | 의미 |
|---|---|
| `manifest.json` | 대상·선언 snapshot-id·UTC 시각·git revision·소스/프롬프트 해시·공급자·모델·llm timeout/reasoning·문항·반복·라벨·사전 점검. Gemini에만 예전 gemini 필드도 유지 |
| `observations.json` | 전체 응답·오류 분류·벽시계/모델/SQL 시간·토큰·SQL 수정/timeout·accuracy:null·review_status |
| `N.jsonl` | query_start, llm_attempt, generation_result, resolved_context, sql_attempt, summary_result, query_end. 논리 호출과 물리 시도 분리 |
| `results/score-<UUID>/score.json` | 관찰별 판정·사유·review 항목과 전체/유형/문항별 집계 |
| `NOTE.txt` | 일부 과거 실행에서 사람이 남긴 설명. run.py가 모든 폴더에 자동 생성하는 파일은 아님 |

```powershell
uv run python -m evaluations.summarize --list
uv run python -m evaluations.summarize '<결과폴더>'
# 실제 DB 연결. ANALYZE 없이 계획만 읽음; 먼저 올바른 DB 환경을 별도 확인할 것
uv run python -m evaluations.explain '<결과폴더>' --target snapshot --snapshot-id $env:AGENT_EVAL_SNAPSHOT_ID --case 0
```

요약의 결과 클래스는 컬럼 이름/순서/서식·추가 컬럼 차이를 완화해 비교하지만 SQL 의미의 동등성을 증명하지 않는다. 모델 시간은 논리/물리 호출을 구분하고, sql_stage_ms는 범위 변환·가드·접속을 포함하여 순수 DB 실행 시간이 아니다. 없는 토큰/옛 로그의 timeout 횟수는0으로 채우지 않는다. 스냅샷·코드·정답/채점 기준이 다르면 결과를 같은 실험으로 합치지 않는다.

<a id="알아-둘-한계"></a>

## 검증과 남은 범위

2026-10-28 agent_server에서 Ruff check·format --check101파일, 오프라인pytest619건 통과(PostgreSQL73건 제외). 실제 DB/모델·운영·위 평가 명령은 이번에 실행하지 않았다. 측정 도구의 모의 테스트 통과와 실제 모델 정확도/유료 등급 검증은 다르다. 실제 측정 기록은 위 보고서 링크, 현재 미결정/정식 평가 완료 조건은 agent 파트 문서에 둔다.

<a id="실행-색인-2026-10-12"></a>
<a id="테스트베드-로컬-docker"></a>

과거 실행 색인·테스트베드 구성은 [이동한 원문](../../docs/archive/agent_evaluation_history_2026-10-28.md)을 참조한다.
