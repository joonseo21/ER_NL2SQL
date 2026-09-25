# games.start_dtm 시간대 수정과 backfill

작업일: 2026-09-25 (KST)

## 문제

ER API 원본 `startDtm`은 `2026-09-25T05:10:50.050+0900`처럼 콜론 없는 오프셋을 가진다.
`BattleUserResultMapper`가 `LocalDateTime.parse()`를 써서 파싱에 실패했고, `games.start_dtm`
(`timestamp`) 265건이 모두 NULL이었다. `participants.raw`에는 265경기 모두 `startDtm`이 있었다.

## 변경

- 매퍼: `OffsetDateTime`으로 파싱한다. `+0900`은 `+09:00`으로 정규화하며 `+09:00`, `+09`, `Z`도 처리한다.
  값이 없거나, 깨졌거나, 오프셋이 없으면 NULL이다(오프셋이 없으면 시간대를 알 수 없다).
- DB: `db/migrations/V2__games_start_dtm_timestamptz.sql`. `games.start_dtm`을 `timestamptz`로 바꾸고
  `participants.raw`에서 NULL 행만 backfill한다. 재실행해도 값이 바뀌지 않는다.
  기존 시각 값(있다면)은 KST로 해석해 변환한다.
- `agent_server/schema_context.md`: 시간대 포함 컬럼임을 명시하고, 날짜·시간 집계는
  `AT TIME ZONE 'Asia/Seoul'`로 변환하도록 규칙을 추가했다.

## 운영 DB 적용 결과 (EC2 `er_analytics`, PostgreSQL 17.11)

| 항목 | 적용 전 | 적용 후 |
|---|---|---|
| `start_dtm` 타입 | `timestamp` | `timestamptz` |
| 게임 수 / NULL 수 | 265 / 265 | 265 / 0 |
| 최소·최대 (KST) | - | 2026-09-10 07:52:54 ~ 2026-09-25 16:38:10 |
| `raw.startDtm`과 불일치 | - | 0건 |

- 적용 전 `pg_dump -Fc` 백업(7.06 MB)을 만들고 목차에서 주요 테이블 데이터를 확인했다.
- backfill 사전 점검: 265경기 모두 `startDtm` 보유, 형식 오류 0건, 경기 내 값이 서로 다른 경우 0건,
  오프셋은 5,988행 모두 `+0900`.
- collector를 새 코드로 재빌드해 교체했다(postgres 컨테이너와 볼륨은 그대로). 교체 후 첫 사이클에서
  경기 14건이 새로 수집됐고 모두 `start_dtm`이 채워졌다. 최종 279경기, NULL 0건.

## 검증

- Gradle 전체 테스트 20건 통과(매퍼 19건: `+0900`, `+09:00`, `+09`, `Z`, 음수 오프셋, null, 누락, 깨진 값 9종, 오프셋 없음).
- `agent_server` pytest 11건 통과. SQL 검증기가 `AT TIME ZONE` 쿼리를 통과시키는 것을 확인했다.
- 일회용 Postgres 17에서 엣지 케이스(깨진 값, 잘못된 날짜, JSON null, 숫자, 기존 값 보존)와
  2회 실행 멱등성, 신규 볼륨 초기화 체인(`010` → `015` → `020`)을 확인했다.
- 실제 `CollectorRepository.upsertGame`이 `OffsetDateTime`을 `timestamptz`로 정확히 저장하는 것을 확인했다.

## 발견한 문제: V2의 backfill 쿼리가 느리다

운영에서 V2 적용에 3분 안팎이 걸렸다. 일회용 DB(11행)에서는 즉시 끝나 보이지 않던 문제다.
운영 규모(게임 265, 참가자 5,988, `raw` 평균 17 kB)로 재현하니 플래너가 Nested Loop를 골라
`per_game` 집계를 게임 수만큼(265회) 반복 실행했다.

| 쿼리 | 실행 시간 |
|---|---|
| V2에 커밋된 형태 | 42.1초 (로컬), 운영은 약 3분 |
| CTE를 `MATERIALIZED`로 한 번만 계산 | 0.2초 (Hash Join) |

- 영향: 첫 실행은 `ALTER`가 잡은 `games`의 배타 락을 커밋까지 유지했다. collector가 유휴 상태였고
  조회 세션도 없어 실제 피해는 없었다. 재실행 시에는 `ALTER`가 건너뛰어져 배타 락 없이 CPU만 쓴다.
- 이미 적용된 V2 파일은 수정하지 않았다. backfill을 다시 돌려야 한다면 CTE에 `MATERIALIZED`를 붙인 쿼리를 쓴다.
- `docs/results/db_schema_snapshot.sql`은 적용 전 스냅샷이라 `start_dtm timestamp`로 남아 있다.
