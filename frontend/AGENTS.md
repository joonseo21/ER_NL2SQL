# frontend 파트 (질문 화면)

공통 규칙은 [CLAUDE.md](../CLAUDE.md)다. 이 파일은 이 파트의 범위만 정한다. spec 개발 순서의 10단계(질문 화면과 후기·수집 현황)에서 프로젝트를 만들었다. 개발 서버(`npm run dev`)와 테스트의 기본은 가짜 응답이고, 프로덕션 빌드(`npm run build`)의 기본은 실제 `/api` 호출이다. 환경 변수 `VITE_API_MODE`를 `mock` 또는 `http`로 명시하면 그 값이 우선한다.

## 담당

- `frontend/`: React + Vite + TypeScript 앱. 조건 선택, 질문 입력, 결과 표·SQL·요약·데이터 근거, 후기, 이용자용 수집 현황, 접수 번호 표시·복구, 최근 질문 목록, 서비스 안내. 서비스 안내 문구는 `src/content/serviceGuide.ts` 한 파일에 있으며 사용자가 확인한 문구라 임의로 고치지 않는다.
- 빌드 결과 `frontend/dist`(Git 제외): Spring 프로그램이 `/`에서 제공하고 API는 `/api`다(spec D-11). 별도 frontend 서비스와 Dockerfile은 없고, 공용 `compose.yaml`의 Spring 서비스가 dist를 읽기 전용으로 연결한다. 소스를 바꾸면 `npm run build`로 dist를 다시 만든다.

## 읽을 문서

- 공통 결정·발견사항·미결정: [docs/spec.md](../docs/spec.md)
- 파트 현황과 요구사항: [docs/parts/frontend.md](../docs/parts/frontend.md). 작업을 마치면 이 문서의 현재 상태와 개발 전달 기록을 갱신한다.
- 다른 파트의 문서(docs/parts의 나머지)는 읽지 않는다.
- 읽는 계약: [docs/contracts/web-api.md](../docs/contracts/web-api.md). 질문 응답의 본문 형식은 여기서 가리키는 [agent-http 계약](../docs/contracts/agent-http.md)의 "응답" 절이다.

## 담당 밖

- `backend/`, `agent_server/`, `db/`의 코드는 읽지 않는다. 화면은 web-api 계약만으로 만든다.
- API가 아직 없으면 계약에 맞춘 모의 응답으로 개발한다. 질문 응답의 예시는 [docs/contracts/samples](../docs/contracts/samples/README.md)에 유형별로 있다.
- 필요한 필드나 엔드포인트가 없으면 web-api 계약의 "변경 요청"에 적고 사용자에게 알린다.
- 이번 사용자 요청(2026-10-29)에서는 web-api 계약 전체가 읽기 전용이다. 서버 보관 기간 확인 등 전달할 변경 요청은 `docs/parts/frontend.md`와 보고에 남겨 api-server가 계약의 "변경 요청"에 옮기게 한다.
- DB 구조, SQL 생성 방식, 수집 방식은 알 필요가 없다. 화면에 필요한 설명 문구는 요구사항 문서에서 가져온다.

## 최근 질문 — 사용자 요청(2026-10-29)

- 사용자 확정: 초대 코드 해시별 최근 10건·7일. 접수(202) 뒤 접수 번호·질문 문장·접수 시각만 localStorage(`er-analytics.recent-questions`)에 저장한다. 초대 코드 원문·결과 본문·SQL·표는 이 저장소 값에 넣지 않는다. 기존 같은 탭 sessionStorage 복구는 유지한다.
- 저장 기본 켬. "이 기기에 남은 질문 기록 지우기"와 "이 기기에 질문 기록 남기기"는 이 브라우저 전체에 적용한다. 끄면 모든 코드의 목록도 삭제하고 끔 설정을 유지한다. 초대 코드 변경은 목록을 지우지 않는다. 서버와 가짜 서버의 기록은 건드리지 않는다.
- 목록 선택은 `QuestionScreen.tsx`의 `handleCheck(requestId, null)`로 GET 한 번만 한다. 처리 중이면 기존 "결과 다시 확인", 404면 기존 안내와 해당 코드 항목 삭제. 질문을 다시 보내거나 횟수를 바꾸지 않는다.
- `src/lib/recentQuestions.ts`에서 형식·중복·건수·만료를 검사한다. 앱을 열 때, 목록 사용 시와 열린 질문 화면의 1분 주기·초점 복귀·다른 탭 변경 때 정리한다. 브라우저가 닫힌 동안 정리 작업이 실행되는 것은 아니다. 서버 보관 기간은 계약에 없어 미확인이다.
- 초대 코드 해시는 기존 `ownerKey()`와 같으며 보안 인증이 아니다. 목록은 기기에 남는 정보다. 저장·삭제 실패 시 안내하고, 저장 끄기를 저장하지 못해도 현재 탭은 기록을 남기지 않는다. 브라우저 재열기 뒤 설정 유지·삭제 성공은 저장소가 정상일 때만 가능하다.

## 검증

`frontend/`에서 실행한다. Node.js 24 LTS와 npm이 필요하며 처음에는 `npm install`을 한다. 모든 검사를 통과시킨 뒤 끝낸다.

| 명령 | 내용 |
|---|---|
| `npm run lint` | ESLint |
| `npm run typecheck` | TypeScript 타입 검사 |
| `npm test` | Vitest 단위·화면 테스트 (한 번 실행) |
| `npm run build` | 타입 검사 후 프로덕션 빌드. 결과는 `frontend/dist` |
| `npm run dev` | 개발 서버 (기본은 가짜 응답, `/api` 프록시는 없음) |
| `npm run test:watch` | 테스트 감시 모드 |
| `npm run preview` | 빌드 결과 미리보기 |

빌드 전에 `.env.local` 등에 `VITE_API_MODE=mock`이 남아 있지 않은지 확인한다. 남아 있으면 프로덕션 빌드도 가짜 응답이 된다. 소스를 바꾸지 않고 빌드만 확인할 때는 `npm run build -- --outDir <임시 폴더> --emptyOutDir`로 출력해 현재 dist를 덮어쓰지 않는다(릴리스 준비가 dist 파일의 해시를 기록해 두었다).
