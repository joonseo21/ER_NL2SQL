# MVP-0 구현 중간 상태

기록일: 2026-09-25

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
  - Docker 컨테이너 상시 실행 및 6시간 fixed-delay 반복 수집
  - 한글·일본어·한자 등 Unicode 닉네임 UTF-8 단일 인코딩 처리
- Python 단발 NL2SQL 실행기
  - 스키마 설명과 닉네임 마스킹 샘플을 Gemini에 제공
  - PostgreSQL AST 기반 단일 SELECT/테이블 화이트리스트 검사
  - 결과 최대 200행과 읽기 전용 transaction 강제
  - 질문·모델·SQL·가드·실행 결과 JSONL 로그

## 검증 완료

| 검증 | 결과 |
|---|---|
| Spring 컴파일/단위 테스트 | 성공 (`BUILD SUCCESSFUL`, 매퍼 및 Unicode URI 테스트) |
| Spring 애플리케이션 기동(수집 비활성) | 성공 (Spring Boot 3.5.16, Java 21) |
| Python SQL 가드 테스트 | 성공 (11 tests passed) |
| Python 실행 모듈 import | 성공 |
| Docker Compose/PostgreSQL 기동 | 성공 (`localhost:5433`, healthy) |
| collector DB 연결 | 성공 (Spring preflight `current_user=collector`) |
| agent_ro 조회 화이트리스트 | 분석 테이블 SELECT 성공, `collect_queue` SELECT 권한 거부 확인 |
| 위험 SQL 거부 | DML, 다중 문장, 비허용 테이블, 시스템 카탈로그, 주석, CTE 거부 확인 |
| 과도한 결과 제한 | `LIMIT 1000`을 외부 쿼리 `LIMIT 200`으로 제한 확인 |
| AWS EC2 배포 | PostgreSQL healthy, collector running, DB 포트 loopback 한정 |
| SSH 터널 원격 조회 | 로컬 Python `agent_ro`로 EC2 DB 조회 성공 |
| Unicode 닉네임 | 한글 1위 랭커 실호출·DB 적재 및 혼합 Unicode 회귀 테스트 성공 |
| NL2SQL 종단 실행 | 5개 질문 중 3개 최종 성공, 생성 SQL 3건 모두 가드·DB 실행 성공 |

## 실제 실행 결과

- Unicode 인코딩 수정 전 초기 로컬 실행에서는 상위 50명 중 18명의 `userId`를 해석하고 경기 111건·참가자 2,538행을 적재했다.
- 수정 후 AWS 반복 수집에서는 누적 랭커 레코드 67건, 고유 경기 265건, 참가자 5,988행을 적재했다. USER 67건과 GAME 265건은 모두 DONE 상태다.
- 랭커-경기 연결은 770건이지만 동일 gameId를 중복 제거하면 265건이다. 경기당 수집 대상 랭커는 평균 2.91명, 최대 10명으로 상위권 매치 중복이 크게 관측됐다.
- 실제 ER API 초기 호출 195건에서 429 1회를 관측했다. 상세 수치는 `collection_stats.md`에 기록했다.
- 2026-09-25 NL2SQL 호출 8회에서 성공 3회, Gemini 503 실패 4회, 무료 일일 quota 429 실패 1회를 관측했다. 질문 5개 중 3개는 재시도를 포함해 최종 답변에 도달했다.
- SQL 생성에 성공한 3건은 모두 가드와 실제 DB 실행까지 성공했다. 상세는 `nl2sql_trials.md`에 기록했다.

실제 실행 후 `api_findings.md`, `collection_stats.md`, `nl2sql_trials.md`에 측정값을 기록한다.
