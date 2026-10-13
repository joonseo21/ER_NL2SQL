import { describe, expect, it } from 'vitest'
import { ApiError } from './errors'
import { createMockApi, MOCK_SCENARIOS } from './mock'
import type { MockScenarioId } from './mock'

const request = { question: '다이아몬드에서 아드리아나 승률은?' }

async function ask(scenario: MockScenarioId) {
  return createMockApi({ delayMs: 0, scenario: () => scenario }).askQuestion(request, 'CODE')
}

describe('가짜 응답이 계약의 유형별 규칙을 지킨다', () => {
  it.each(['answer', 'answer-without-summary', 'answer-empty-result'] as const)(
    '%s: sql과 적용 조건이 있고 되묻기 후보가 없다',
    async (id) => {
      const res = await ask(id)
      expect(res.kind).toBe('answer')
      if (res.kind !== 'answer') return
      expect(res.sql).toBeTruthy()
      expect(res.applied_conditions.season_id).toBeTypeOf('number')
      expect(res.clarifications).toEqual([])
    },
  )

  it('안내: message가 있고 SQL·표·요약이 없다', async () => {
    const res = await ask('notice')
    expect(res.kind).toBe('notice')
    expect(res.message).toBeTruthy()
    expect(res.sql).toBeNull()
    expect(res.rows).toEqual([])
    expect(res.summary).toBeNull()
  })

  it('되묻기: message와 후보가 있다', async () => {
    const res = await ask('clarification')
    expect(res.kind).toBe('clarification')
    if (res.kind !== 'clarification') return
    expect(res.message).toBeTruthy()
    expect(res.clarifications[0].candidates.length).toBeGreaterThan(0)
  })

  it('요약 없는 답변은 summary가 null이어도 표가 남아 있다', async () => {
    const res = await ask('answer-without-summary')
    expect(res.summary).toBeNull()
    expect(res.rows.length).toBeGreaterThan(0)
  })

  it('빈 결과는 컬럼을 보존하고 행이 없다', async () => {
    const res = await ask('answer-empty-result')
    expect(res.columns.length).toBeGreaterThan(0)
    expect(res.rows).toEqual([])
  })

  it('행별 표본 근거의 row_index가 rows 범위 안에 있다', async () => {
    const res = await ask('answer')
    for (const group of res.evidence?.groups ?? []) {
      expect(group.row_index).toBeGreaterThanOrEqual(0)
      expect(group.row_index).toBeLessThan(res.rows.length)
    }
  })
})

describe('가짜 서버의 동작', () => {
  it('질문 문구와 log_id를 요청마다 바꾸고 원본 샘플을 오염시키지 않는다', async () => {
    const api = createMockApi({ delayMs: 0 })
    const first = await api.askQuestion({ question: '첫 질문' }, 'CODE')
    const second = await api.askQuestion({ question: '둘째 질문' }, 'CODE')
    expect(first.question).toBe('첫 질문')
    expect(second.question).toBe('둘째 질문')
    expect(first.log_id).not.toBe(second.log_id)
  })

  it('답변·안내·되묻기 모두 남은 횟수를 하나씩 줄인다', async () => {
    let scenario: MockScenarioId = 'answer'
    const api = createMockApi({ delayMs: 0, scenario: () => scenario, initialRemaining: 10 })
    expect((await api.askQuestion(request, 'CODE')).remaining_today).toBe(9)
    scenario = 'notice'
    expect((await api.askQuestion(request, 'CODE')).remaining_today).toBe(8)
    scenario = 'clarification'
    expect((await api.askQuestion(request, 'CODE')).remaining_today).toBe(7)
  })

  it('한도 초과는 접수 단계에서 거절되어 횟수를 소비하지 않는다', async () => {
    let scenario: MockScenarioId = 'error-invite-limit'
    const statuses: string[] = []
    const api = createMockApi({ delayMs: 0, scenario: () => scenario, initialRemaining: 5 })
    await expect(api.askQuestion(request, 'CODE', (s) => statuses.push(s))).rejects.toMatchObject({
      code: 'INVITE_LIMIT_EXCEEDED',
      status: 429,
    })
    scenario = 'error-global-limit'
    await expect(api.askQuestion(request, 'CODE')).rejects.toMatchObject({ code: 'GLOBAL_LIMIT_EXCEEDED' })
    expect(statuses).toEqual([]) // 접수되지 않았으므로 진행 상태도 없다

    scenario = 'answer'
    expect((await api.askQuestion(request, 'CODE')).remaining_today).toBe(4)
  })

  it('접수한 뒤 에이전트가 실패하면 횟수는 소비된 채 오류가 된다', async () => {
    let scenario: MockScenarioId = 'error-agent'
    const statuses: string[] = []
    const api = createMockApi({ delayMs: 0, scenario: () => scenario, initialRemaining: 5 })

    await expect(api.askQuestion(request, 'CODE', (s) => statuses.push(s))).rejects.toMatchObject({
      code: 'AGENT_CONNECTION_FAILED',
      message: '질문 처리 서버에 연결하지 못했습니다. 잠시 뒤 다시 이용해 주세요.',
      accepted: { requestId: 'mock-request-1', remainingToday: 4 },
    })
    expect(statuses).toEqual(['accepted', 'processing'])

    scenario = 'answer'
    expect((await api.askQuestion(request, 'CODE')).remaining_today).toBe(3) // 5 → 4(실패) → 3
  })

  it('처리 지연 시나리오는 접수 정보를 담은 PROCESSING_TIMEOUT이고, 다시 확인하면 답변을 받는다', async () => {
    const api = createMockApi({ delayMs: 0, scenario: () => 'error-timeout', initialRemaining: 5 })

    const error = await api.askQuestion(request, 'CODE').catch((e: unknown) => e)
    expect(error).toMatchObject({
      code: 'PROCESSING_TIMEOUT',
      accepted: { requestId: 'mock-request-1', remainingToday: 4 },
    })

    const response = await api.checkQuestion('mock-request-1', 4, 'CODE')
    expect(response).toMatchObject({ kind: 'answer', remaining_today: 4 })
  })

  it('후기는 가짜 서버가 돌려준 log_id에만 저장되고 다시 보내도 저장된다', async () => {
    const api = createMockApi({ delayMs: 0 })
    const { log_id } = await api.askQuestion(request, 'CODE')

    await expect(api.submitFeedback(log_id, { rating: 'like' }, 'CODE')).resolves.toEqual({ saved: true })
    await expect(
      api.submitFeedback(log_id, { rating: 'dislike', comment: '바꿨어요' }, 'CODE'),
    ).resolves.toEqual({ saved: true })
  })

  it('모르는 log_id의 후기는 QUESTION_NOT_FOUND다', async () => {
    const api = createMockApi({ delayMs: 0 })
    await expect(api.submitFeedback('unknown', { rating: 'like' }, 'CODE')).rejects.toMatchObject({
      code: 'QUESTION_NOT_FOUND',
      status: 404,
    })
  })

  it('의견이 500자를 넘으면 VALIDATION_FAILED, 500자는 허용한다', async () => {
    const api = createMockApi({ delayMs: 0 })
    const { log_id } = await api.askQuestion(request, 'CODE')
    await expect(
      api.submitFeedback(log_id, { rating: 'like', comment: '가'.repeat(500) }, 'CODE'),
    ).resolves.toEqual({ saved: true })
    await expect(
      api.submitFeedback(log_id, { rating: 'like', comment: '가'.repeat(501) }, 'CODE'),
    ).rejects.toMatchObject({ code: 'VALIDATION_FAILED', status: 400 })
  })

  it('수집 현황은 계약의 확정 형식(티어별 이용자 수·참가 기록 수, 패치, 시각 3종)을 따른다', async () => {
    const summary = await createMockApi({ delayMs: 0 }).getCollectionSummary('CODE')
    expect(Object.keys(summary).sort()).toEqual(
      ['by_tier', 'cached_until', 'latest_collected_at', 'patches', 'snapshot_at', 'total_games'].sort(),
    )
    expect(summary.total_games).toBeGreaterThan(0)
    for (const row of summary.by_tier) {
      expect(Object.keys(row).sort()).toEqual(['participant_appearances', 'tier', 'users'])
      expect(row.users).toBeGreaterThanOrEqual(0)
      expect(row.participant_appearances).toBeGreaterThanOrEqual(0)
    }
    // 조건 목록과 같은 형식의 패치이며 버전 내림차순이다.
    expect(summary.patches[0]).toEqual({
      major: 5,
      minor: 0,
      first_match: expect.any(String),
      last_match: expect.any(String),
    })
    expect(Date.parse(summary.cached_until)).toBeGreaterThan(Date.parse(summary.snapshot_at))
  })

  it('수집 현황 예시에는 티어를 확인하지 못한 항목(null)과 자료가 없는 티어(0)가 있다', async () => {
    const { by_tier } = await createMockApi({ delayMs: 0 }).getCollectionSummary('CODE')
    expect(by_tier.some((row) => row.tier === null)).toBe(true)
    expect(by_tier.some((row) => row.tier !== null && row.users === 0 && row.participant_appearances === 0)).toBe(
      true,
    )
  })

  it('수집 현황도 잘못된 초대 코드는 거절한다', async () => {
    await expect(createMockApi({ delayMs: 0 }).getCollectionSummary('INVALID')).rejects.toMatchObject({
      code: 'INVITE_INVALID',
    })
  })

  it('정상 질문은 접수 → 처리 중 → 완료 순서로 진행 상태를 알린다', async () => {
    const statuses: string[] = []
    const api = createMockApi({ delayMs: 0 })
    await api.askQuestion(request, 'CODE', (s) => statuses.push(s))
    expect(statuses).toEqual(['accepted', 'processing', 'completed'])
  })

  it('잘못된 초대 코드는 INVITE_INVALID다', async () => {
    const api = createMockApi({ delayMs: 0 })
    await expect(api.verifyInvite('INVALID')).rejects.toBeInstanceOf(ApiError)
    await expect(api.getConditions('')).rejects.toMatchObject({ code: 'INVITE_INVALID' })
    await expect(api.askQuestion(request, 'INVALID')).rejects.toMatchObject({ code: 'INVITE_INVALID' })
  })

  it('공백뿐인 질문은 VALIDATION_FAILED다', async () => {
    const api = createMockApi({ delayMs: 0 })
    await expect(api.askQuestion({ question: '   ' }, 'CODE')).rejects.toMatchObject({
      code: 'VALIDATION_FAILED',
    })
  })

  it('조건 목록은 최신 패치가 먼저이고 기간이 없는 패치도 있다', async () => {
    const { patches, tiers, characters } = await createMockApi({ delayMs: 0 }).getConditions('CODE')
    expect(patches[0]).toMatchObject({ major: 5, minor: 0 })
    expect(patches.some((p) => p.first_match === null)).toBe(true)
    expect(tiers).toContain('다이아몬드')
    expect(characters).toContainEqual({ code: 17, name: '아드리아나' })
  })

  it('개발용 시나리오 목록이 비어 있지 않고 id가 겹치지 않는다', () => {
    const ids = MOCK_SCENARIOS.map((s) => s.id)
    expect(new Set(ids).size).toBe(ids.length)
  })
})
