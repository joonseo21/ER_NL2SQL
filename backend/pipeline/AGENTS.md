# backend/pipeline: 수집기와 API 서버가 함께 있는 Spring 프로그램

공통 규칙은 [CLAUDE.md](../../CLAUDE.md)다. 이 파일은 이 폴더에서 일하는 두 파트의 범위를 정한다.

2026-10-12 사용자 결정으로 수집기와 API 서버는 하나의 Spring 프로그램이다. 프로그램은 하나지만 파트는 둘이고 대화도 따로 둔다. 사용자가 첫 메시지에서 어느 파트인지 알려 준다. 알려 주지 않았으면 먼저 묻는다.

| 파트 | 코드 묶음(패키지) | 하는 일 |
|---|---|---|
| pipeline (수집) | `io.eranalytics.pipeline`의 `collection`, `erapi`, `persistence`, `migration`, `config` | ER API에서 경기를 모아 DB에 저장. DB 구조와 계정 관리 |
| api-server (웹 API) | `io.eranalytics.pipeline.webapi` | 화면의 요청을 받아 초대 코드와 사용량 확인, 에이전트 호출, 질문 기록·후기 저장, 수집 현황 제공 |

## 두 파트가 함께 지킬 것

- 두 묶음은 서로의 코드를 가져다 쓰지 않는다. `config/checkstyle/import-control.xml`이 검사한다. 양쪽에 같은 것이 필요하면 복사하지 말고 사용자에게 알린다.
- 테스트도 같은 구분을 따른다. api-server의 테스트는 `src/test/java/.../webapi` 아래에 둔다.
- 두 파트가 함께 쓰는 파일은 `build.gradle`, `src/main/resources/application.yml`, `PipelineApplication.java`, `config/checkstyle/`, `Dockerfile`이다. 고치기 전에 `git status`로 다른 대화의 미커밋 변경이 없는지 확인하고, 자기 파트에 필요한 줄만 고친 뒤 사용자에게 알린다. 상대 파트의 설정 줄은 건드리지 않는다.
- 수집 기능은 `er.collection.enabled`, 웹 API 기능은 `er.web-api.enabled` 설정으로 켜고 끈다. 한쪽만 켜도 프로그램이 떠야 한다. 나중에 같은 프로그램을 수집용과 웹 API용으로 두 개 띄울 수 있게 하기 위해서다.
- 프로그램이 시작할 때 JPA가 Entity와 DB 구조가 맞는지 검사한다(`ddl-auto=validate`). 새 Entity를 넣으면 그 테이블이 있는 DB에서만 프로그램이 뜬다. 운영에 올리기 전에 해당 마이그레이션이 먼저 적용되어 있어야 한다.

## pipeline 파트 (수집과 DB)

담당:

- 위 표의 수집 묶음과 그 테스트
- `db/`: 마이그레이션과 계정 초기화. api-server가 쓰는 테이블도 마이그레이션은 여기서 작성한다.
- `compose.yaml`의 postgres·collector·migrate 서비스

읽을 문서:

- 공통 결정·발견사항·미결정: [docs/spec.md](../../docs/spec.md)
- 파트 현황과 요구사항: [docs/parts/pipeline.md](../../docs/parts/pipeline.md). 작업을 마치면 이 문서의 현재 상태와 개발 전달 기록을 갱신한다.
- 주인인 계약: [docs/contracts/database.md](../../docs/contracts/database.md)
- 필요할 때: [docs/api_findings.md](../../docs/api_findings.md), [docs/operations.md](../../docs/operations.md)

담당 밖:

- `webapi` 묶음, `agent_server/`, `frontend/`의 코드와 다른 파트의 문서(docs/parts의 나머지)는 읽지 않는다. 다른 파트가 DB에서 무엇을 쓰는지는 database 계약으로 안다.
- 담당 밖 코드를 봐야 원인을 알 수 있으면 사용자에게 어떤 파일이 왜 필요한지 말하고 승인받은 파일만 본다. 수정은 하지 않고 해당 파트에 전달할 내용을 정리한다.

계약을 바꿀 때:

- 테이블·컬럼·권한을 바꾸면 같은 작업에서 database 계약을 고친다.
- agent가 읽는 테이블·컬럼이 바뀌면 그 사실을 사용자에게 알린다. agent 쪽 허용 목록과 스키마 설명은 agent 대화가 맞춘다.
- 새 V 파일의 번호는 사용자에게 확인한다.

## api-server 파트 (웹 API)

담당:

- `webapi` 묶음과 그 테스트. 이 묶음 안에 컨트롤러, 서비스, Entity, Repository, 설정을 모두 둔다.
- 저장에는 JPA를 적극적으로 쓴다(2026-10-12 사용자 결정. 예전의 JdbcTemplate 계획을 대체한다).
- DB에는 수집기의 계정(collector)이 아니라 권한이 좁은 전용 계정으로 접속한다. 이 계정은 닉네임을 읽을 수 없다. 한 프로그램 안에 DB 연결을 둘 두는 설정이 필요하다. 설정이 일정에 비해 과하다고 판단되면 임의로 collector 계정을 쓰지 말고 사용자에게 묻는다.

읽을 문서:

- 공통 결정·발견사항·미결정: [docs/spec.md](../../docs/spec.md)
- 파트 현황과 요구사항: [docs/parts/api-server.md](../../docs/parts/api-server.md). 작업을 마치면 이 문서의 현재 상태와 개발 전달 기록을 갱신한다.
- 주인인 계약: [docs/contracts/web-api.md](../../docs/contracts/web-api.md)
- 읽는 계약: [docs/contracts/agent-http.md](../../docs/contracts/agent-http.md), [docs/contracts/database.md](../../docs/contracts/database.md)

담당 밖:

- 수집 묶음(`collection`, `erapi`, `persistence`, `migration`), `db/migrations/`, `agent_server/`, `frontend/`의 코드와 다른 파트의 문서는 읽지 않는다. 에이전트의 응답 형식과 DB 구조는 계약으로 안다.
- 테이블이 필요하면 직접 마이그레이션을 만들지 않고 database 계약의 "변경 요청"에 테이블과 컬럼을 적고 사용자에게 알린다. 마이그레이션은 pipeline 파트가 작성한다.
- 에이전트 응답에 필요한 필드가 없으면 agent-http 계약의 "변경 요청"에 적는다.
- 에이전트가 아직 HTTP로 뜨지 않았으면 [docs/contracts/samples](../../docs/contracts/samples/README.md)의 응답 예시를 돌려주는 가짜 에이전트로 개발한다.
- 담당 밖 코드를 봐야 원인을 알 수 있으면 사용자에게 어떤 파일이 왜 필요한지 말하고 승인받은 파일만 본다.

계약을 바꿀 때:

- 경로·요청·응답·오류 코드를 바꾸면 같은 작업에서 web-api 계약을 고친다.
- frontend가 쓰고 있을 수 있는 필드의 삭제·이름 변경은 먼저 사용자에게 알린다.

알아둘 현재 상태:

- `application.yml`의 `spring.main.web-application-type`이 `none`이라 지금은 웹 요청을 받지 않는다. 웹 API를 켤 때 함께 바꿔야 하며, 수집만 켠 실행이 깨지지 않게 한다.
- `er.api.*` 설정은 ER Open API(게임 데이터 API) 접속 정보다. 웹 API의 설정은 `er.web-api.*`에 둔다.

## 검증

```powershell
.\gradlew.bat spotlessApply
.\gradlew.bat check
```

`check`는 두 파트의 코드 스타일 검사와 단위 테스트를 모두 돌린다. 상대 파트의 테스트가 실패하면 고치지 말고 사용자에게 알린다. DB 통합 테스트와 실제 API 검증의 실행 조건은 operations를 따른다.
