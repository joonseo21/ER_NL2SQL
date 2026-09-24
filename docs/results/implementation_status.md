# MVP-0 구현 중간 상태

기록일: 2026-09-24

## 구현 완료

- PostgreSQL 스키마 7개 테이블과 조회 인덱스 정의
- Docker Compose 초기화 시 `collector` 쓰기 계정과 `agent_ro` 읽기 전용 계정 분리
- `agent_ro` 기본 read-only transaction 및 5초 statement timeout 설정
- Spring Boot 단일 워커 수집기
  - 랭커 → USER 작업 → GAME 작업 흐름
  - DB 영속 큐와 GAME 우선 처리
  - 1 RPS 기본 게이트, 403/429/5xx 지수 백오프, 최대 5회 시도
  - 경기/참가자 upsert 및 참가자 원본 JSONB 보관
  - 캐릭터 영문/한글 메타데이터 적재 경로
  - API 상태코드·지연시간 로그
- Python 단발 NL2SQL 실행기
  - 스키마 설명과 닉네임 마스킹 샘플을 Gemini에 제공
  - PostgreSQL AST 기반 단일 SELECT/테이블 화이트리스트 검사
  - 결과 최대 200행과 읽기 전용 transaction 강제
  - 질문·모델·SQL·가드·실행 결과 JSONL 로그

## 검증 완료

| 검증 | 결과 |
|---|---|
| Spring 컴파일/단위 테스트 | 성공 (`BUILD SUCCESSFUL`, 1 test) |
| Spring 애플리케이션 기동(수집 비활성) | 성공 (Spring Boot 3.5.16, Java 21) |
| Python SQL 가드 테스트 | 성공 (11 tests passed) |
| Python 실행 모듈 import | 성공 |
| Docker Compose/PostgreSQL 기동 | 성공 (`localhost:5433`, healthy) |
| collector DB 연결 | 성공 (Spring preflight `current_user=collector`) |
| agent_ro 조회 화이트리스트 | 분석 테이블 SELECT 성공, `collect_queue` SELECT 권한 거부 확인 |
| 위험 SQL 거부 | DML, 다중 문장, 비허용 테이블, 시스템 카탈로그, 주석, CTE 거부 확인 |
| 과도한 결과 제한 | `LIMIT 1000`을 외부 쿼리 `LIMIT 200`으로 제한 확인 |

## 실제 실행 결과

- 상위 랭커 50명의 닉네임을 조회해 18명의 `userId`를 해석했다.
- USER 18건과 GAME 111건이 모두 DONE이며, 경기 111건·참가자 2,538행을 적재했다.
- 실제 호출 195건에서 429 1회를 관측했다. 상세 수치는 `collection_stats.md`에 기록했다.
- Gemini 질문 5개 실행과 `agent_ro` INSERT 거부 확인은 아직 남아 있다.

실제 실행 후 `api_findings.md`, `collection_stats.md`, `nl2sql_trials.md`에 측정값을 기록한다.
