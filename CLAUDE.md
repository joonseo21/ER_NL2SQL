# CLAUDE.md

이 파일은 이 저장소에서 **코드를 작성하는 대화/에이전트**가 따를 지침이다. 보고서 등 문서 작업은 별도 대화에서 하며, 그 대화도 이 파일을 읽는다.
스택·구조 결정이 바뀌면 이 파일의 해당 섹션(§3, §4, §8)을 **바로 갱신**한다. 최종 갱신: 2026-09-24.

## 1. 컨텍스트

- 성균관대 소프트웨어학과 학사 졸업 작품(개인, 지도교수 이선재). 제목: 데이터 에이전트를 통한 게임 이용자 분석 및 메타적인 질문 답변 웹사이트.
- 도메인: 이터널 리턴(ER) 단일 게임. 매치 데이터를 수집·적재하고, 사용자의 자연어 질문을 **NL2SQL로 실제 DB에 실행**해 근거 있는 답을 준다. 승률·픽률만 보여주는 기존 통계 사이트가 못하는 복합 질문(상성, 교전 구도, 랭커-일반유저 차이, 빌드 유효성)이 목표.
- 비영리 학술 프로젝트. ER API 이용 정책 준수가 존속 조건 (§5).
- **일정**: 중간보고서 제출 2026-09-27(일), **최종보고서 제출 2026-10-30**. 기능 동결 10/25.
- **운영**: 수집기와 DB는 AWS EC2 1대에서 Docker Compose로 상시 운영한다. 에이전트는 노트북에서 SSH 터널로 접속. 상세는 `docs/MVP-0 추가 결정 (2026-09-24).md`.
- **대화 역할 분리**: 문서(중간보고서 등)는 문서 대화, 코드는 코드 대화. 코드 대화는 보고서를 쓰지 않는 대신, 보고서 근거가 될 실행 결과를 `docs/results/`에 남긴다 (§6-9).

### 개발 방식 (확정)
- 루트 `README.md`(초기 마스터 프롬프트)는 **롤백된 초안**이다. "Phase 1 Function Calling → Phase 2 NL2SQL" 구분과 그 안의 스택 지정은 무효. **처음부터 NL2SQL.**
- **Walking skeleton**: 정교한 사전 설계 대신, 가장 얇은 경로를 로컬에서 끝까지 뚫고 점진 확장한다. 세부 결정은 실제로 필요해질 때 한다.
- **이번 주 최소 범위**: ER API로 매치 데이터 수집 → DB 적재 → 에이전트가 만든 NL2SQL을 그 DB에 실행해보는 환경.

## 2. ER Open API (설계 전 필수 확인)

원문: `docs/reference/이터널 리턴 개발자 포털 (Open API).txt` (v9.4.0). Base URL `https://open-api.bser.io`, 모든 요청에 `x-api-key` 헤더. 응답은 `{code, message, ...}`이고 **페이로드 키가 엔드포인트마다 다르다**(`user`/`userGames`/`userStats`/`topRanks`/`teams`/`data`/`result`). 상태코드: 200, 400, 403(금지 또는 rate limit), 404, 429(=403과 동일 취급), 500.

| 용도 | 엔드포인트 | 응답 키 |
|---|---|---|
| 닉네임→UID | `GET /v1/user/nickname?query=` | `user` |
| 유저 최근 경기(90일) | `GET /v1/user/games/uid/{uid}` | `userGames` |
| **매치 전체 참가자** | `GET /v1/games/{gameId}` | `userGames` |
| 유저 랭크 | `GET /v1/rank/uid/{userId}/{seasonId}/{matchingTeamMode}` | `userRank` |
| 유저 통계 v2 | `GET /v2/user/stats/uid/{userId}/{seasonId}/{matchingMode}` | `userStats` |
| 유니온 팀 | `GET /v1/unionTeam/uid/{userId}/{seasonId}` | `teams` |
| 상위 랭커 | `GET /v1/rank/top/{seasonId}/{matchingTeamMode}[/{serverCode}]` | `topRanks` |
| 정적 게임 데이터 | `GET /v2/data/{metaType}` (`hash`로 목록) | `data` |
| 언어(l10n) | `GET /v1/l10n/{language}` (다운로드 링크 반환, `┃` 구분) | `data` |
| 추천 무기 루트 | `GET /v1/weaponRoutes/recommend` | `result` |

**가능**: 위 표의 데이터 전부. 핵심은 `BattleUserResult`(200개 이상 필드: 킬/어시스트, 데미지 종류별 분해, 장비, 스킬 순서, 크레딧, 시작 지역, 버전 등)와, gameId 하나로 그 매치 참가자 전원의 결과를 받는 엔드포인트.

**제약 (스키마·수집 설계에 반드시 반영)**
1. UID는 영구적이지 않다(닉네임 변경 시 바뀜, userNum 검색 폐지). 동일 인물의 장기 추적은 불안정.
2. 경기 목록은 **최근 90일만**. 정기 수집이 없으면 데이터가 유실된다.
3. **`BattleUserResult`에는 uid가 없고 nickname만 있다(문서 기준).** 참가자 행의 키는 (gameId, nickname) 계열이 되고, 스노우볼 확장(매치→새 유저의 최근 경기)에는 nickname→UID 조회 1콜이 추가로 든다. 실제 호출로 확인할 것.
4. `mmrBefore`/`mmrAfter`는 일부 키에만 제공되는 민감 정보 → nullable.
5. 랭킹/랭크 API는 **스쿼드(matchingTeamMode=3)만**. 랭커-일반유저 비교는 스쿼드 한정.
6. 벌크/검색 엔드포인트가 없다. 개별 UID/gameId 순회(스노우볼)만 가능 — 수집 속도의 병목.
7. rate limit 수치는 문서에 없다. 키 신청 문서의 설계 가정은 Standard Key 5 RPS이나, **실제 발급 한도는 미확인.** 429/403 응답으로 확인하고 토큰버킷으로 자체 상한을 둔다.
8. 문서에 페이지네이션 언급이 없고, 일부 예시와 필드 정의가 불일치한다(예: `characterStat`). 문서 예시를 그대로 믿지 말고 **실제 호출 응답으로 검증**한다.

`docs/api_samples/*.json`은 공식 문서 예시를 바탕으로 한 가상 값(`TestPlayer` 등)으로 보이며 **실제 응답이 아니다.** 필드 구조 참고용으로만 쓴다.

## 3. 스택 (큰 틀만 확정)

| 항목 | 상태 |
|---|---|
| 백엔드: Spring Boot (Java 21, Gradle) — ER API 수집, 인가/인증, 클라이언트 요청 API | 확정(큰 틀). 인증·질문 한도는 MVP 이후(§9) |
| 에이전트: Python(uv), **DB에 직접 접속해 NL2SQL 실행** (백엔드 경유 안 함) | 확정(큰 틀) |
| DB: PostgreSQL | 확정 |
| Redis | 미정 (필요해지면 도입) |
| 에이전트 프레임워크(LangGraph/FastAPI/LangChain 등) | MVP-0에서는 미사용. `agent_server/pyproject.toml`은 uv 기반 직접 호출 구성 |
| 프론트엔드: React | 예정, 이번 주 범위 밖 |
| 스키마 마이그레이션 도구 | **Flyway** (현 스키마를 V1로 고정, 이후 새 파일 추가) |
| 인프라 | AWS EC2(기존 계정, 유료) + Docker Compose(postgres, collector). DB 포트 외부 비공개 |
| CI/CD | GitHub Actions, collector만 재배포 (여유 시 도입) |
| LLM 제공자 | Gemini 우선. 호출은 함수 하나로 감싸 교체 가능하게 |

서비스는 Spring/Python으로 분리하지만 **유레카·API 게이트웨이 같은 MSA 인프라는 필요가 생기기 전까지 도입하지 않는다.**

## 4. 저장소 구조

**원레포(단일 git) 전환 완료.** 저장소 루트는 `ER_NL2SQL/`이다.

| 경로 | 현재 상태 |
|---|---|
| `agent_server/` | uv 기반 Python NL2SQL 실행기 |
| `backend/pipeline/` | Spring Boot 수집기. 로컬/컨테이너 단일 실행과 주기 실행 지원 |
| `frontend/` | 비어 있음 |
| `docs/schema_v1.sql` | 현재 PostgreSQL 기준 스키마 |
| `docs/api_samples/` | 가상 샘플 (§2) |
| `docs/results/` | (신규) 실행 결과 근거 자료 (§6-9) |
| `docs/reference/이터널 리턴 개발자 포털 (Open API).txt` | 공식 문서(v9.4.0) 로컬 참고본 |
| `README.md` | 롤백된 초안. 규범 아님 |

루트 Git 저장소는 초기화되어 있으나 첫 커밋과 원격 저장소 연결은 사용자가 요청할 때 진행한다.

## 5. 하드 제약 / 안전 규칙

**시크릿**
- ER API 키, LLM API 키, DB 비밀번호를 코드·로그·출력·커밋에 넣지 않는다. `.env`(루트 `.env.example` 참고) 또는 `*-api-key.properties`(gitignore 대상)만 쓴다.
- 실제 키가 `backend/pipeline/src/main/resources/application-api-key.properties`에 있다. 이 파일을 읽을 때 **값을 출력·인용하지 않는다.**

**ER API 정책**
- 비영리, 재판매·과금 금지. 개인을 식별 가능하게 제3자에 공개하지 않고 집계·통계 위주로 활용한다.
- 무분별한 병렬 호출 금지. 큐 기반 스케줄러 + 토큰버킷. 429/403/404는 사용자에게 자연어로 안내한다.
- 조회는 자체 DB에서 처리하고 API 직접 호출은 최소화한다.

**DB**
- 스키마 변경은 `docs/schema_v1.sql`에 반영한다. 마이그레이션 도구를 도입한 뒤에는 **적용된 마이그레이션을 수정하지 않고** 새 파일을 추가한다.
- 쿼리는 항상 파라미터 바인딩. 문자열 이어붙이기 금지.
- 스키마를 바꾸면 LLM용 스키마 설명 파일과 `agent_ro` 권한도 **같은 커밋에서** 갱신한다. 새 컬럼은 `raw` JSONB에서 backfill한다.
- 운영 DB: 마이그레이션 전 `pg_dump` 백업. **`docker compose down -v` 금지**(볼륨 삭제).
- 수집·적재는 **멱등**해야 한다(같은 gameId를 다시 받아도 중복 행이 생기지 않게 upsert). 재시도·중복 수집이 일상이다.
- 원본 응답 필드(camelCase) ↔ DB 컬럼 매핑은 한 곳에서만 정의한다.

**NL2SQL 실행 안전 (에이전트가 LLM 생성 SQL을 실행하므로 필수)**
- 에이전트용 DB 계정은 **읽기 전용(SELECT만)**. 쓰기 권한이 있는 연결로 LLM 생성 SQL을 절대 실행하지 않는다.
- 단일 SELECT 문만 허용(다중 statement, DDL/DML, 세미콜론 체이닝 거부). 접근 가능한 테이블/뷰는 화이트리스트로 제한한다.
- statement timeout, 결과 행 수 상한(LIMIT 강제)을 둔다.
- 질문, 생성된 SQL, 실행 결과 요약을 로그로 남긴다 (디버깅과 보고서 근거를 겸함).

**파괴적 작업**: 파일·폴더 삭제, git 히스토리 변경 등은 사용자 확인 없이 하지 않는다.

## 6. 작업 방식

1. **작은 것도 계획 먼저, 단 가볍게.** 사소하지 않은 변경은 스키마/인터페이스/흐름을 짧게 제시하고 OK를 받은 뒤 코딩한다. walking skeleton 원칙상 과설계하지 않는다.
2. **스키마·계약 변경은 크게 알린다.** 테이블/컬럼, 서비스 간 인터페이스가 바뀌면 무엇이 영향받는지 명시한다.
3. **정확성 우선.** 멱등성, nullable 처리(§2-4), rate limit, 에러 시 명확한 메시지.
4. **증거로 검증한다.** 빌드/테스트/실행 결과를 보여준다. 실제 ER API를 호출해 확인하지 않은 것을 "된다"고 하지 않는다.
5. **범위 유지.** 요청하지 않은 기능·추상화를 만들지 않는다. 탄탄한 최소 경로가 우선.
6. **계약은 한 곳에.** 스키마는 `docs/schema_v1.sql`, API 사실은 §2와 공식 문서.
7. **간결하게.** 짧은 설명, 명확한 다음 단계.
8. **불확실하면 추측하지 말고** 질문으로 올리고 대안을 제시한다(rate limit 한도, 페이지네이션 등).
9. **결과 근거를 남긴다.** 수집 건수, 예시 질문과 생성된 SQL·결과, 발견한 API 제약 등을 `docs/results/`에 짧은 md로 기록한다. 문서 대화가 보고서 "구현 및 결과분석"에 그대로 가져다 쓴다.

## 7. 코드 컨벤션

- 각 서브프로젝트의 기존 언어/프레임워크 컨벤션을 따른다.
- 주석은 이유가 자명하지 않을 때만, 한 줄로.

## 8. 명령어 (스켈레톤 확정 후 갱신)

현재 확인된 것만 적는다.

```sh
# repository root
docker-compose up -d
docker-compose --profile collector build collector
docker-compose --profile collector up -d collector
docker-compose logs -f collector

# agent_server
uv sync
uv run pytest
uv run er-agent manual game-count
uv run er-agent ask "수집된 경기는 몇 판이야?"

# backend/pipeline (Windows)
.\gradlew.bat test
.\gradlew.bat bootRun --args="--er.collection.enabled=true"
```

컨테이너 collector는 기본 6시간 간격으로 반복한다. 로컬 IntelliJ 실행은 기본적으로 한 사이클 뒤 종료한다.
커밋 전 최소 기준: 빌드와 테스트가 통과할 것.

## 9. MVP 이후 로드맵 (10/30 이후, 포트폴리오용) — 지금은 구현하지 않는다

10/30 제출은 기능을 추린 MVP다. 이후 아래 기능을 붙일 예정이므로, **지금 코드는 이것들을 막지 않는 형태로만** 만든다. 미리 구현하지 않는다(§6-5).

| 예정 기능 | 지금 지켜둘 설계 조건 |
|---|---|
| 유저 계정(회원가입·로그인) | 인증·인가는 Spring이 담당한다. 에이전트에 인증 로직을 넣지 않는다 |
| 유저별 하루 질문 수 제한 | 최종 구조는 **클라이언트 → Spring → 에이전트(HTTP)**. 그래서 에이전트의 NL2SQL은 CLI에 묶지 말고 `answer(question) -> 결과` 형태의 함수로 둔다(나중에 FastAPI 등으로 감싸기 쉽게). 질문 로그 테이블에는 `user_id`(nullable) 컬럼을 둔다 |
| 유사한 실험체 추천 | 실험체별 집계(평균 순위, 무기 분포, 킬·딜 지표 등)를 뷰로 만들 때 재사용 가능하게 이름·정의를 문서화한다 |
| 프론트 UI/UX 개선 | 에이전트 응답 형식을 한 곳에서 정의한다: `{question, sql, columns, rows, answer_text, elapsed_ms, log_id}` |
| 실사용자 피드백 | 답변마다 `log_id`를 발급해, 나중에 좋아요/싫어요·코멘트를 이 id에 연결한다 |
| 공개 서비스 운영 | ER API 정책(비영리·과금 금지·개인 식별 정보 비공개)은 계속 적용된다. 계정 정보는 최소한만 수집한다 |

## 10. 참고 문서 우선순위

1. ER API → `docs/reference/이터널 리턴 개발자 포털 (Open API).txt` (그리고 §2의 실제 호출 검증)
2. 연구 목적·일정 → `연구논문작품_신청서_260325_221905.pdf`, `연구논문작품_중간보고서.hwp`
3. `README.md`는 참고용 사료일 뿐이다.
