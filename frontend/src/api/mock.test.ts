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

  it('오류 시나리오는 횟수를 줄이지 않고 코드별 ApiError를 던진다', async () => {
    let scenario: MockScenarioId = 'error-invite-limit'
    const api = createMockApi({ delayMs: 0, scenario: () => scenario, initialRemaining: 5 })
    await expect(api.askQuestion(request, 'CODE')).rejects.toMatchObject({
      code: 'INVITE_LIMIT_EXCEEDED',
      status: 429,
    })
    scenario = 'error-global-limit'
    await expect(api.askQuestion(request, 'CODE')).rejects.toMatchObject({ code: 'GLOBAL_LIMIT_EXCEEDED' })
    scenario = 'error-agent'
    await expect(api.askQuestion(request, 'CODE')).rejects.toMatchObject({ code: 'AGENT_UNAVAILABLE' })
    scenario = 'answer'
    expect((await api.askQuestion(request, 'CODE')).remaining_today).toBe(4)
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
