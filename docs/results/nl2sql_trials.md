# NL2SQL 실행 결과

확인일: 2026-09-25 (KST)

## 실험 구성

- 모델: `gemini-3.8-flash`
- 입력: 사용자의 한국어 자연어 질문
- 처리 순서: 스키마·마스킹 샘플 구성 → Gemini SQL 생성 → SQL AST 가드 → `agent_ro`로 PostgreSQL 실행
- 안전장치: 단일 SELECT, 허용 테이블 화이트리스트, 읽기 전용 트랜잭션, 5초 statement timeout, 최대 200행
- 원본 실행 로그: `agent_server/nl2sql_log.jsonl` (Git 제외)

## 2026-09-25 실행 결과

| 질문 | 호출 | 최종 결과 | 생성 SQL 요약 | 결과 행 | 성공 소요시간 |
|---|---:|---|---|---:|---:|
| 수집된 경기는 몇 판이야? | 1회 | 성공 | `games` 행 수 집계 | 1 | 20.191초 |
| 가장 많이 픽된 실험체 상위 10개는? | 1회 | 성공 | 참가자-실험체 조인 후 픽 수 상위 10개 | 10 | 21.989초 |
| 랭커와 비랭커의 실험체별 평균 킬 차이는? | 2회 | 재시도 후 성공 | `FILTER` 집계로 두 집단 평균 킬과 차이 계산 | 90 | 14.489초 |
| 실험체별 평균 순위와 우승률, 10판 이상 | 2회 | 실패 | SQL 생성 전 Gemini 503 | 0 | - |
| 같은 실험체라도 무기에 따라 평균 순위가 다른가? | 2회 | 실패 | SQL 생성 전 Gemini 503 및 429 | 0 | - |

호출 단위로는 8회 중 3회 성공(37.5%), 질문 단위로는 5개 중 3개가 최종 답변에 도달했다(60%).
성공한 3건의 평균 응답시간은 약 18.9초이며 최소 14.5초, 최대 22.0초였다.

## 파이프라인 단계별 결과

| 단계 | 도달 | 성공 | 해석 |
|---|---:|---:|---|
| Gemini SQL 생성 | 8 | 3 | 외부 모델 가용성과 quota가 주요 병목 |
| SQL 가드 | 3 | 3 | 생성된 SQL 3건 모두 정책 통과 |
| PostgreSQL 실행 | 3 | 3 | 가드를 통과한 SQL 모두 실행 성공 |

이번 표본에서는 SQL이 생성된 이후 가드 실패나 DB 실행 실패는 없었다. 즉 현재 관측된 실패는
NL2SQL 문법·DB 연결 문제가 아니라 Gemini 응답을 얻기 전 단계에서 발생했다. 다만 성공 표본이
3건뿐이므로 SQL 정확도 100%로 일반화할 수는 없다.

## 성공 SQL 예시

### 경기 수 집계

```sql
SELECT COUNT(*) AS game_count
FROM games
LIMIT 200;
```

### 랭커-비랭커 평균 킬 차이

```sql
SELECT
    COALESCE(c.name_ko, p.character_num::text) AS character_name,
    AVG(p.player_kill) FILTER (WHERE p.is_ranker) AS ranker_avg_kills,
    AVG(p.player_kill) FILTER (WHERE NOT p.is_ranker) AS non_ranker_avg_kills,
    AVG(p.player_kill) FILTER (WHERE p.is_ranker)
      - AVG(p.player_kill) FILTER (WHERE NOT p.is_ranker) AS kill_diff
FROM participants p
LEFT JOIN characters c ON p.character_num = c.character_code
GROUP BY p.character_num, c.name_ko
ORDER BY kill_diff DESC NULLS LAST
LIMIT 200;
```

사용자가 결과를 눈으로 확인했을 때 질문 의도와 대체로 부합했다. 다만 정답 데이터셋과의 비교,
수치 재계산 같은 정량적 정확도 평가는 아직 수행하지 않았으므로 보고서에서는 이를 정성 확인으로
구분한다.

## 실패 및 quota 분석

- 2026-09-25의 실패 5회는 Gemini `503 UNAVAILABLE` 4회와 `429 RESOURCE_EXHAUSTED` 1회다.
- 429 응답에는 `gemini-3.8-flash` 무료 등급의 프로젝트·모델별 일일 요청 한도가 20회라고 명시됐다.
- 500/502/503/504에는 최대 3회 재시도한다. 성공한 세 질문도 각각 3회, 3회, 2회째 모델 호출에서
  응답을 받았으므로 질문 한 개가 여러 quota 요청을 소비할 수 있다.
- 429는 마지막의 복합 질문에서 관측됐지만, 로그만으로 질문 난이도가 429의 원인이라고 결론낼 수
  없다. 직접 원인은 누적 요청 한도 소진이며, 복합 질문의 독립적인 성공률은 추가 표본이 필요하다.
- 429는 일일 한도이므로 짧은 지수 백오프만으로 해결되지 않는다. 다음 개발에서는 429를 즉시
  사용자 친화적 오류로 변환하고, quota 초기화 시각 이후 재시도하거나 유료 quota·대체 모델을
  선택해야 한다.

## 초기 개발 중 관측

- `gemini-flash-latest` 초기 실행 3회는 모두 모델 고수요에 따른 503으로 실패했다.
- 이후 모델을 `gemini-3.8-flash`로 변경해 SQL 생성과 실행 성공 사례를 확보했다.
- 직접 `Models.generate_content` 호출의 AFC 경고를 제거하기 위해 `Chat.send_message`로 전환했다.
- 실패 로그의 `model_attempts=0`은 실제 시도 횟수가 0이라는 뜻이 아니라, 현재 구현이 성공 시에만
  최종 시도 횟수를 기록하는 계측 한계다. 실패 시도 횟수 기록은 후속 개선 항목이다.

## 결론

MVP-0는 자연어 질문에서 SQL을 생성하고, 안전성 검사 후 실제 수집 DB에서 결과를 반환하는 종단
경로를 입증했다. 반면 현재 무료 Gemini quota와 간헐적 503 때문에 반복 실험과 복합 질문의 안정적
서비스는 어렵다. 따라서 본 결과는 기능 가능성 검증으로 해석하고, 서비스 수준의 안정성 확보에는
quota 확대, 호출 실패 정책, 캐시 또는 대체 모델 전략이 필요하다.
