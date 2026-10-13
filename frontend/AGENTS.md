# frontend 파트 (질문 화면)

공통 규칙은 [CLAUDE.md](../CLAUDE.md)다. 이 파일은 이 파트의 범위만 정한다. 10단계에서 프로젝트를 만들었고 API 서버가 없는 동안 가짜 응답으로 개발한다.

## 담당

- `frontend/`: React + Vite + TypeScript 앱. 조건 선택, 질문 입력, 결과 표·SQL·요약·데이터 근거, 후기, 이용자용 수집 현황
- `compose.yaml`의 frontend 서비스

## 읽을 문서

- 공통 결정·발견사항·미결정: [docs/spec.md](../docs/spec.md)
- 파트 현황과 요구사항: [docs/parts/frontend.md](../docs/parts/frontend.md). 작업을 마치면 이 문서의 현재 상태와 개발 전달 기록을 갱신한다.
- 다른 파트의 문서(docs/parts의 나머지)는 읽지 않는다.
- 읽는 계약: [docs/contracts/web-api.md](../docs/contracts/web-api.md). 질문 응답의 본문 형식은 여기서 가리키는 [agent-http 계약](../docs/contracts/agent-http.md)의 "응답" 절이다.

## 담당 밖

- `backend/`, `agent_server/`, `db/`의 코드는 읽지 않는다. 화면은 web-api 계약만으로 만든다.
- API가 아직 없으면 계약에 맞춘 모의 응답으로 개발한다. 질문 응답의 예시는 [docs/contracts/samples](../docs/contracts/samples/README.md)에 유형별로 있다.
- 필요한 필드나 엔드포인트가 없으면 web-api 계약의 "변경 요청"에 적고 사용자에게 알린다.
- DB 구조, SQL 생성 방식, 수집 방식은 알 필요가 없다. 화면에 필요한 설명 문구는 요구사항 문서에서 가져온다.

## 검증

`frontend/`에서 실행한다. Node.js 24 LTS와 npm이 필요하며 처음에는 `npm install`을 한다. 모든 검사를 통과시킨 뒤 끝낸다.

| 명령 | 내용 |
|---|---|
| `npm run lint` | ESLint |
| `npm run typecheck` | TypeScript 타입 검사 |
| `npm test` | Vitest 단위·화면 테스트 (한 번 실행) |
| `npm run build` | 타입 검사 후 프로덕션 빌드 |
| `npm run dev` | 개발 서버 |
