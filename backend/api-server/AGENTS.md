# api-server 파트 (Spring API 서버)

공통 규칙은 [CLAUDE.md](../../CLAUDE.md)다. 이 파일은 이 파트의 범위만 정한다. 아직 코드가 없다(9단계).

## 담당

- `backend/api-server/`: 수집기와 별도인 Spring 앱. 초대 코드와 사용량 확인, 에이전트 호출, 질문 기록·후기 저장, 수집 현황 제공
- `compose.yaml`의 api-server 서비스

수집기의 JPA 결정은 이 앱에 적용하지 않는다. 저장은 JdbcTemplate을 쓴다(D-07).

## 읽을 문서

- 공통 결정·발견사항·미결정: [docs/spec.md](../../docs/spec.md)
- 파트 현황과 요구사항: [docs/parts/api-server.md](../../docs/parts/api-server.md). 작업을 마치면 이 문서의 현재 상태와 개발 전달 기록을 갱신한다.
- 다른 파트의 문서(docs/parts의 나머지)는 읽지 않는다.
- 주인인 계약: [docs/contracts/web-api.md](../../docs/contracts/web-api.md)
- 읽는 계약: [docs/contracts/agent-http.md](../../docs/contracts/agent-http.md), [docs/contracts/database.md](../../docs/contracts/database.md)

## 담당 밖

- `backend/pipeline/`, `agent_server/`, `frontend/`, `db/migrations/`의 코드는 읽지 않는다. 에이전트의 응답 형식과 DB 구조는 계약으로 안다.
- 테이블이 필요하면 직접 마이그레이션을 만들지 않고 database 계약의 "변경 요청"에 테이블과 컬럼을 적고 사용자에게 알린다. 마이그레이션은 pipeline이 작성한다.
- 에이전트 응답에 필요한 필드가 없으면 agent-http 계약의 "변경 요청"에 적는다.
- 담당 밖 코드를 봐야 원인을 알 수 있으면 사용자에게 어떤 파일이 왜 필요한지 말하고 승인받은 파일만 본다.

## 계약을 바꿀 때

- 경로·요청·응답·오류 코드를 바꾸면 같은 작업에서 web-api 계약을 고친다.
- frontend가 쓰고 있을 수 있는 필드의 삭제·이름 변경은 먼저 사용자에게 알린다.

## 검증

수집기와 같은 Spotless·Checkstyle 구성을 쓴다. 프로젝트를 만들 때 `backend/pipeline`의 build.gradle 설정과 config/checkstyle을 기준으로 맞춘다.
