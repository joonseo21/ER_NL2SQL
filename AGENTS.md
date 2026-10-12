# Codex 작업 안내

Codex가 자동으로 읽는 파일이다. 규칙의 본문은 [CLAUDE.md](CLAUDE.md)에 있고 Claude와 Codex가 같은 규칙을 따른다. 여기에 규칙을 복사하지 않는다.

## 시작할 때

1. [README.md](README.md), [CLAUDE.md](CLAUDE.md), [docs/spec.md](docs/spec.md)를 순서대로 읽는다. spec은 공통 결정만 담는다. 파트 대화는 이어서 자기 파트의 AGENTS.md가 가리키는 docs/parts 문서와 계약만 읽고, 다른 파트의 문서와 docs/archive는 읽지 않는다. 실행·배포는 [docs/operations.md](docs/operations.md), API 사실은 [docs/api_findings.md](docs/api_findings.md)이며 필요한 절만 읽는다.
2. `git status`로 작업 트리를 확인한다. 본인이 만들지 않은 미커밋 변경이 있으면 CLAUDE.md의 에이전트 간 인계 절을 따른다.

## 파트

개발 대화는 파트 하나를 맡는다. 맡은 파트의 폴더에서 시작하면 그 폴더의 AGENTS.md가 함께 읽힌다. 담당 범위, 읽을 계약, 읽지 않을 폴더는 그 파일에 있다.

| 파트 | 폴더 | 주인인 계약 | 읽는 계약 |
|---|---|---|---|
| pipeline | backend/pipeline, db | database | 없음 |
| agent | agent_server, tools | agent-http | database |
| api-server | backend/api-server | web-api | agent-http, database |
| frontend | frontend | 없음 | web-api |

계약은 docs/contracts에 있다. 계약과 파트 경계의 규칙은 CLAUDE.md의 "파트와 계약" 절을 따른다. 리뷰를 맡은 대화는 파트 경계에 묶이지 않는다.

## 역할

- 구현과 코드 리뷰를 맡는다.
- 판단이 필요한 사항은 추측으로 확정하지 않고 사용자에게 묻는다. 답변은 spec이나 해당 문서에 기록한 뒤 구현한다.
- 사용자에게 보내는 메시지는 CLAUDE.md의 "사용자에게 말할 때" 절을 따른다. D-05, Q-03, M1-021, 7-4, V7 같은 번호만 쓰지 말고 무엇인지 말로 풀어 쓴다.

## 실행 환경

- `docs/`는 Git에서 제외되어 있다. EC2처럼 다른 체크아웃에는 spec·operations가 없을 수 있다. 문서가 보이지 않으면 내용을 가정하지 말고 사용자에게 알린다.
- 운영 DB 변경, 마이그레이션, 배포는 사용자가 요청한 범위에서만 수행한다.

## 끝낼 때

- 스타일 검사와 테스트를 통과시킨다. 명령은 operations의 코드 스타일 검사 절에 있다.
- 자기 파트 문서(docs/parts)의 현재 상태와 개발 전달 기록에 변경, 검증 결과, 실행하지 못한 검증, 남은 결정을 적는다. 여러 파트에 걸친 결정·발견사항·미결정만 spec에 적는다.
