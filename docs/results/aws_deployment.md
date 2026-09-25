# AWS EC2 배포 확인

확인일: 2026-09-25 (KST)

## 배포 환경

| 항목 | 값 |
|---|---|
| 운영체제 | Ubuntu 24.04 LTS, x86_64 |
| 인스턴스 | EC2 t2.small, 메모리 2 GiB |
| 루트 볼륨 | gp3 20 GiB |
| Docker Engine | 29.8.1 |
| Docker Compose | 5.5.1 |
| 배포 커밋 | `97cc335` |
| 서비스 | PostgreSQL 17, Spring Boot collector |
| 반복 주기 | fixed delay 6시간 |

PostgreSQL 호스트 포트는 `127.0.0.1:5433`에만 바인딩했다. EC2 보안 그룹에는
SSH 22번만 현재 개발자 IP에 허용하고 DB 포트는 열지 않았다.

## 확인 결과

- PostgreSQL 컨테이너 `healthy` 확인
- collector 컨테이너 `running`, 재시작 0회 확인
- collector의 `current_user=collector` DB preflight 성공
- 실제 ER API 응답으로 랭커, 경기, 참가자 적재 시작 확인
- 로컬 Python 에이전트가 SSH 터널과 `agent_ro` 계정으로 EC2 DB 조회 성공
- 터널 조회 시점의 `game_count`: 48

첫 사이클 진행 중 확인한 누적값은 랭커 17명, 경기 81건, 참가자 1,836행,
API 호출 로그 149건이다. 큐는 DONE 95건, PENDING 7건이었으며 collector는 이후에도
백그라운드에서 처리를 계속한다.

API 키, DB 비밀번호, EC2 주소와 SSH 키 경로는 이 문서에 기록하지 않는다.
