# pipeline 파트 (수집기와 DB)

공통 규칙은 [CLAUDE.md](../../CLAUDE.md)다. 이 파일은 이 파트의 범위만 정한다.

## 담당

- `backend/pipeline/`: Spring 수집기
- `db/`: 마이그레이션과 계정 초기화. 다른 파트의 테이블도 마이그레이션은 여기서 작성한다.
- `compose.yaml`의 postgres·collector·migrate 서비스

## 읽을 문서

- 공통 결정·발견사항·미결정: [docs/spec.md](../../docs/spec.md)
- 파트 현황과 요구사항: [docs/parts/pipeline.md](../../docs/parts/pipeline.md). 작업을 마치면 이 문서의 현재 상태와 개발 전달 기록을 갱신한다.
- 다른 파트의 문서(docs/parts의 나머지)는 읽지 않는다.
- 주인인 계약: [docs/contracts/database.md](../../docs/contracts/database.md)
- 필요할 때: [docs/api_findings.md](../../docs/api_findings.md), [docs/operations.md](../../docs/operations.md)

## 담당 밖

- `agent_server/`, `backend/api-server/`, `frontend/`의 코드는 읽지 않는다. 다른 파트가 DB에서 무엇을 쓰는지는 database 계약으로 안다.
- 담당 밖 코드를 봐야 원인을 알 수 있으면 사용자에게 어떤 파일이 왜 필요한지 말하고 승인받은 파일만 본다. 수정은 하지 않고 해당 파트에 전달할 내용을 정리한다.

## 계약을 바꿀 때

- 테이블·컬럼·권한을 바꾸면 같은 작업에서 database 계약을 고친다.
- agent가 읽는 테이블·컬럼이 바뀌면 그 사실을 사용자에게 알린다. agent 쪽 허용 목록과 스키마 설명은 agent 대화가 맞춘다.
- 새 V 파일의 번호는 사용자에게 확인한다.

## 검증

```powershell
.\gradlew.bat spotlessApply
.\gradlew.bat check
```

DB 통합 테스트와 실제 API 검증의 실행 조건은 operations를 따른다.
