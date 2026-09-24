# MVP-0 개발 기획서: 수집 → 적재 → NL2SQL 한 바퀴

> 코드 대화(에이전트)가 읽는 작업 지시서. CLAUDE.md의 규칙(시크릿, ER API 정책, NL2SQL 안전)을 전제로 한다.
> 작성: 2026-09-23 · 목표 완료: 2026-09-25 · 이후 중간보고서 1.5절과 4장의 근거로 쓴다.

## 0. 목적과 완료 기준

**목적:** 가장 얇은 경로를 로컬에서 끝까지 한 번 뚫어서, 문서만으로는 알 수 없는 것들(실제 응답 구조, rate limit, 수집 속도, LLM이 만든 SQL의 품질)을 확인한다. 완성도보다 **"끝까지 동작함 + 확인된 사실 기록"**이 우선이다.

프로젝트 구조는 모노레포에 데이터 수집 서비스와 NL2SQL서비스 두 개가 띄워질 것이고 DB 분리 및 scale-out, 인프라 구축방법 등은 추후 생각할 것이다.

**완료 기준 (전부 만족하면 MVP-0 종료)**

1. 랭커 목록에서 시작해 수집 루프가 무인으로 돌고, 중단 후 재시작해도 중복 행이 생기지 않는다.
2. 수집 속도(시간당 경기 수, 시간당 API 호출 수, 429/403 발생 여부)를 측정해 기록했다.
3. Python에서 **읽기 전용 계정**으로 DB에 접속해 손으로 쓴 SQL을 실행할 수 있다.
4. Gemini에게 스키마와 마스킹된 샘플 행을 주고 질문 5개 이상을 SQL로 바꾸게 한 뒤, 가드를 통과한 SQL을 실행해서 결과를 얻었다.
5. 위 결과를 모두 `docs/results/`에 md로 남겼다.

## 1. 확정된 결정

| 항목 | 결정 | 이유 |
|---|---|---|
| 수집기 | Spring Boot (`backend/pipeline` 스캐폴드 재사용 여부는 시작 전에 사용자에게 확인) | 신청서·보고서 구조와 일치, 이후 스케줄러·토큰버킷 확장 용이 |
| 시드/확장 | **상위 랭커 목록만 순회.** 경기 참가자를 새 시드로 쓰지 않음 | 실제 `topRanks`에는 UID가 없어 랭커 닉네임별 `/v1/user/nickname` 조회가 필요함(2026-09-24 확인) |
| 적재 대상 | **랭크 스쿼드 경기만** (matchingMode=3, matchingTeamMode=3) | 랭킹 API가 스쿼드만 지원 → 비교 범위 일치 |
| DB | PostgreSQL, **Docker Compose**로 로컬 실행 | 재현성, 읽기 전용 계정 초기화까지 스크립트화 |
| 원본 보관 | 응답 원본은 `jsonb`, 질문에 필요한 필드만 컬럼으로 추출 | 200개 이상 필드의 구조 미확인 + 90일 제약 → 재호출 없이 재가공 가능해야 함 |
| 에이전트 | Python(uv). **프레임워크 없이** 직접 호출 (LangGraph 등은 MVP-0 범위 밖) | 한 바퀴 확인이 목적. 프레임워크 선택은 결과를 보고 결정 |
| LLM | Gemini. 모델명은 `.env`의 `GEMINI_MODEL`로 지정(코드에 하드코딩 금지) | 교체 가능성 확보 |
| LLM에 보내는 데이터 | 스키마 설명 + **닉네임을 마스킹한** 샘플 행 | ER API 정책(개인 식별 정보 제3자 제공 금지) |

## 2. 범위

**포함:** 랭커 수집 루프, 경기·참가자 적재, 캐릭터 코드→이름 메타 적재, 읽기 전용 DB 접근, 단발성 NL2SQL(질문 1개 → SQL 1개 → 실행), 실행 로그.

**제외(하지 않는다):** 프론트엔드, Redis, 인증/인가, Spring↔Python 간 HTTP 연동, 에이전트 프레임워크, 다단계 추론·자기 수정 루프, 답변 문장 생성(결과 표까지만), 참가자 스노우볼, 일반 유저 수집, 스키마 마이그레이션 도구, 배포.

## 3. 단계 1: 수집·적재 (Spring Boot)

### 3.0 사전 확인 (코드 작성 전에 실제 호출 1~2회로 확인하고 `docs/results/api_findings.md`에 기록)

- [ ] 현재 `seasonId` 값 (`/v2/data/Season` 등에서 확인)
- [ ] `/v1/rank/top/{seasonId}/3` 응답에 실제로 `uid`가 있는지, 몇 명이 오는지
- [ ] `/v1/user/games/uid/{uid}` 응답이 몇 경기를 주는지, **페이지네이션(`next` 등) 키가 있는지**
- [ ] `/v1/games/{gameId}` 응답의 참가자 수(스쿼드 기준 예상 24명 내외)와 팀 번호 필드명
- [ ] `BattleUserResult`에 uid 계열 필드가 정말 없는지
- [ ] `killerCharacter`, `killDetail` 등 사망 관련 필드가 실제로 채워지는지 (상성 질문 가능성 판단용, 문서상 "레거시"로 표기됨)
- [ ] `mmrBefore`/`mmrAfter`가 우리 키로 오는지

```
이터널 리턴 api 안내 사이트 참고
https://developer.eternalreturn.io/static/media/OpenAPI_KR_20251118.html
```

### 3.1 수집 흐름

```
[시작] topRanks(seasonId, 3) → 상위 N명의 nickname→userId 조회 → rankers upsert, USER 작업 등록
  ↓
[USER 작업] /v1/user/games/uid/{uid}
   → 랭크 스쿼드 경기만 필터 → 처음 보는 gameId를 큐에 GAME 작업으로 등록
  ↓
[GAME 작업] /v1/games/{gameId}
   → games 1행 + participants N행 upsert (원본 jsonb 포함)
  ↓
큐가 빌 때까지 반복 (GAME 작업을 USER 작업보다 우선 처리)
```

- **큐는 DB 테이블로 구현한다** (`collect_queue`). 재시작해도 이어서 돌고, 진행 상황을 SQL로 볼 수 있다. MQ는 도입하지 않는다(→ §7 확장).
- 초기 규모: 랭커 상위 **50명**부터 시작한다. 속도를 측정한 뒤 늘린다.
- 실행 형태: 웹 엔드포인트 없이 `CommandLineRunner` 또는 `@Scheduled` 단일 워커. 병렬 처리하지 않는다.

### 3.2 Rate limit과 에러

- 토큰버킷 자체 상한: **초기 1 RPS**로 시작하고, 429/403이 없으면 단계적으로 올린다(상한값은 설정 파일로).
- 429/403: 지수 백오프 후 재시도, 작업 상태를 `RETRY`로 두고 시도 횟수를 기록한다. 5회 실패하면 `FAILED`.
- 404: 해당 작업만 `FAILED`로 두고 진행한다(닉네임 변경 등으로 UID가 무효화될 수 있음).
- 모든 API 호출을 `api_call_log`에 기록한다(엔드포인트, 상태코드, 소요시간, 시각). **수집 속도 측정의 근거가 된다.**

### 3.3 스키마 초안 (`docs/schema_v1.sql`에 확정본 기록)

실제 응답을 확인한 뒤 컬럼명·타입을 확정한다. 아래는 출발점이다.

```sql
-- 시드
rankers(uid PK, nickname, rank, mmr, season_id, fetched_at)

-- 수집 큐
collect_queue(id PK, job_type['USER'|'GAME'], target_key, status['PENDING'|'DONE'|'RETRY'|'FAILED'],
              attempts, last_error, created_at, updated_at, UNIQUE(job_type, target_key))

-- 경기 (경기 단위 공통 정보)
games(game_id PK, season_id, matching_mode, matching_team_mode,
      version_season, version_major, version_minor, start_dtm, server_name, fetched_at)

-- 참가자 (핵심 조회 테이블)
participants(game_id FK, nickname, team_number, character_num, best_weapon, best_weapon_level,
             game_rank, player_kill, player_assistant, monster_kill, damage_to_player,
             mmr_before NULL, mmr_gain NULL, mmr_after NULL, play_time, is_ranker BOOLEAN,
             raw jsonb, PRIMARY KEY(game_id, nickname))

-- 메타: 코드 → 이름 (LLM이 "아야"를 character_num으로 바꾸려면 필수)
characters(character_code PK, name_ko, name_en)
weapon_types(code PK, name_ko)          -- bestWeapon 해석용, 확인 후 추가

-- 관측
api_call_log(id PK, endpoint, status_code, latency_ms, called_at)
```

- 적재는 `ON CONFLICT DO NOTHING/UPDATE`로 멱등하게 한다.
- camelCase ↔ snake_case 매핑은 한 클래스에서만 정의한다.
- `is_ranker`는 적재 시점에 `rankers`의 닉네임과 일치하면 true. 닉네임 기준이라 부정확할 수 있음을 결과에 기록한다.
- 캐릭터 한글 이름은 `/v2/data/Character`와 `/v1/l10n/Korean`에서 얻는다. 둘 다 안 되면 코드값만 두고 기록한다.

## 4. 단계 2: Python에서 DB 접근

- Docker Compose 초기화 스크립트에서 두 계정을 만든다: `collector`(쓰기, Spring 전용), `agent_ro`(SELECT만, 화이트리스트 테이블만).
  - `agent_ro`에는 `collect_queue`, `api_call_log` 권한을 주지 않는다.
- `agent_ro`에 `statement_timeout = 5s`를 건다.
- `agent_server`(uv)에 `psycopg` 계열 드라이버를 추가하고 연결 정보는 `.env`에서 읽는다.
- 확인 항목: 손으로 쓴 SQL 3개(전체 경기 수, 캐릭터별 픽 수, 랭커 여부별 평균 순위)가 실행되는지, `agent_ro`로 INSERT를 시도하면 거부되는지.

## 5. 단계 3: 최소 NL2SQL

### 5.1 흐름 (함수 하나면 충분)

```
질문 → 프롬프트 구성 → Gemini → SQL 추출 → 가드 → 실행(agent_ro) → 결과 표 출력 → 로그 저장
```

### 5.2 프롬프트 구성

- 테이블과 컬럼 목록, 각 컬럼의 한 줄 설명(코드값 의미 포함, 예: `game_rank`는 1이 우승)
- 테이블별 샘플 3행. **닉네임은 `player_1` 같은 값으로 바꿔서** 보낸다.
- 규칙: PostgreSQL 방언, SELECT 한 문장만, 코드블록 하나로 출력.
- 스키마 설명은 코드에 문자열로 흩어두지 말고 파일 하나(`schema_context.md` 등)로 관리한다. 이 파일이 이후 확장의 핵심 자산이 된다.

### 5.3 가드 (CLAUDE.md §5 NL2SQL 안전)

- SELECT로 시작하는 단일 문장만 허용한다. 세미콜론 체이닝과 DDL/DML 키워드는 거부한다.
- LIMIT이 없으면 `LIMIT 200`을 강제한다.
- 실행은 반드시 `agent_ro` 연결로만 한다.
- 가드에 걸리거나 실행이 실패하면 원인을 기록하고 끝낸다(자기 수정 재시도는 MVP-0 범위 밖).

### 5.4 테스트 질문 세트 (난이도별, 최소 5개)

| 난이도 | 질문 예시 | 확인하려는 것 |
|---|---|---|
| 하 | 수집된 경기는 몇 판이야? | 기본 동작 |
| 하 | 가장 많이 픽된 실험체 상위 10개는? | 코드→이름 조인 |
| 중 | 실험체별 평균 순위와 우승률 (10판 이상만) | 집계와 필터 |
| 중 | 랭커와 비랭커의 실험체별 평균 킬 차이 | `is_ranker` 활용 |
| 중 | 같은 실험체라도 무기에 따라 평균 순위가 다른가? | 다중 그룹핑 |
| 상 | ○○ 실험체가 있는 팀을 상대로 가장 순위가 좋은 실험체는? | 같은 경기 내 셀프 조인(상성의 원형) |
| 상 | 최근 패치(version_major)에서 픽률이 가장 많이 오른 실험체는? | 버전 비교 |

"상" 난이도는 실패해도 괜찮다. 실패한 이유 자체가 보고서의 분석 거리다.

### 5.5 로그

질문, 사용 모델, 생성 SQL, 가드 결과, 실행 성공 여부, 결과 행 수, 소요시간을 `nl2sql_log.jsonl`(또는 테이블)에 남긴다.

## 6. 산출물 (`docs/results/`)

| 파일 | 내용 | 보고서 사용처 |
|---|---|---|
| `api_findings.md` | §3.0 체크리스트 결과, 문서와 다른 점 | 1.4 표 2, 3장 |
| `collection_stats.md` | 실행 시간, API 호출 수, 경기/참가자 수, 시간당 처리량, 429/403 횟수, 설정한 RPS | 1.5, 4장 |
| `nl2sql_trials.md` | 질문별 생성 SQL, 성공/실패, 결과 요약, 실패 원인 분류 | 1.5, 4장 |
| `schema_v1.sql` (docs/) | 확정 스키마 | 3장 ERD |

숫자는 실제 실행 값만 적는다. 추정치는 "추정"이라고 표시한다.

## 7. 확장 로드맵 (MVP-0 이후, 결과를 보고 결정)

MVP-0에서는 구현하지 않는다. 각 항목에 **도입 조건**을 둬서, 필요가 확인될 때만 추가한다.

| 확장 | 도입 조건(트리거) | 후보 기술 |
|---|---|---|
| 참가자 스노우볼, 일반 유저 수집 | 랭커 경기만으로 표본이 부족하거나 "일반 유저" 비교가 필요할 때 | nickname→UID 조회 추가, 큐 우선순위 |
| 정기 수집 | 90일 유실을 막아야 할 때(MVP-0 직후 거의 확정) | `@Scheduled`, 이후 필요 시 MQ(RabbitMQ 등) |
| 파생 테이블/뷰 | LLM이 같은 복잡한 조인을 반복해서 틀릴 때 | 팀 단위 뷰, 실험체×무기 집계 뷰 |
| 자기 수정 루프, 다단계 추론 | 실행 에러나 빈 결과로 실패하는 비율이 높을 때 | LangGraph 등 그래프형 프레임워크 |
| 스키마 선택(RAG) | 스키마 설명이 커져서 프롬프트가 비대해질 때 | 테이블 설명 임베딩 검색 |
| 답변 생성 | SQL 결과를 사람이 읽기 어려울 때(웹 연결 시점) | 결과 요약 프롬프트 |
| 웹 연동 | 에이전트가 안정화된 뒤 | Spring → Python HTTP, React 채팅 UI |
| 캐시 | 같은 질문 반복, 응답 지연이 문제일 때 | Redis |
| 평가 | 4장 정량 평가 시점 | 질문–정답 SQL 세트, 실행 정확도 |

## 8. 작업 순서

1. 저장소 원레포 전환(CLAUDE.md §4), 스캐폴드 재사용 여부 확인
2. Docker Compose + 초기화 SQL(두 계정) → 기동 확인
3. §3.0 사전 확인 → `api_findings.md`
4. 스키마 확정 → `schema_v1.sql`
5. 수집 루프 구현 → 랭커 50명으로 실행 → `collection_stats.md`
6. 메타(캐릭터 이름) 적재
7. Python `agent_ro` 접속 + 수동 SQL 3개
8. NL2SQL 함수 + 가드 → 질문 세트 실행 → `nl2sql_trials.md`
9. CLAUDE.md §3·§8 갱신(스택 결정, 실행 명령)

## 9. 아직 열린 질문 (코드 대화에서 확인 후 사용자에게 보고)

- 스캐폴드를 재사용할지 새로 만들지
- 실제 rate limit 한도, 랭커 목록 크기, 경기 목록 페이지네이션 여부
- 사망 관련 필드가 쓸 만한지 → 상성·교전 질문을 표 1에 넣을 수 있는지 결정
