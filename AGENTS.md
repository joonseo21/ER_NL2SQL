# Codex 작업 안내

Codex가 자동으로 읽는 파일이다. 규칙의 본문은 [CLAUDE.md](CLAUDE.md)에 있고 Claude와 Codex가 같은 규칙을 따른다. 여기에 규칙을 복사하지 않는다.

## 시작할 때

1. [README.md](README.md), [CLAUDE.md](CLAUDE.md), [docs/spec.md](docs/spec.md)를 순서대로 읽는다. 실행·배포는 [docs/operations.md](docs/operations.md), API 사실은 [docs/api_findings.md](docs/api_findings.md)다.
2. `git status`로 작업 트리를 확인한다. 본인이 만들지 않은 미커밋 변경이 있으면 CLAUDE.md의 에이전트 간 인계 절을 따른다.

## 역할

- 구현과 코드 리뷰를 맡는다.
- 판단이 필요한 사항은 추측으로 확정하지 않고 사용자에게 묻는다. 답변은 spec이나 해당 문서에 기록한 뒤 구현한다.

## 실행 환경

- `docs/`는 Git에서 제외되어 있다. EC2처럼 다른 체크아웃에는 spec·operations가 없을 수 있다. 문서가 보이지 않으면 내용을 가정하지 말고 사용자에게 알린다.
- 운영 DB 변경, 마이그레이션, 배포는 사용자가 요청한 범위에서만 수행한다.

## 끝낼 때

- 스타일 검사와 테스트를 통과시킨다. 명령은 operations의 코드 스타일 검사 절에 있다.
- spec의 개발 전달 기록에 변경, 검증 결과, 실행하지 못한 검증, 남은 결정을 적는다.
