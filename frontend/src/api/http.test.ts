import { describe, expect, it, vi } from 'vitest'
import { createHttpApi } from './http'
import type { HttpApiOptions } from './http'
import type { AgentResponse, QuestionStatus } from './types'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

type Responder = () => Response | Promise<Response>

/** 호출될 때마다 준비해 둔 응답을 순서대로 돌려주는 가짜 fetch. 준비한 것을 다 쓰면 마지막 응답을 반복한다. */
function setup(responders: Responder | Responder[], options: HttpApiOptions = {}) {
  const queue = Array.isArray(responders) ? responders : [responders]
  let index = 0
  const fetchFn = vi.fn<typeof fetch>(async () => {
    const respond = queue[Math.min(index, queue.length - 1)]
    index += 1
    return respond()
  })
  const sleep = vi.fn(async () => {})
  const api = createHttpApi({ baseUrl: '/api', fetchFn, sleep, pollIntervalMs: 2000, ...options })
  return { api, fetchFn, sleep }
}

type Call = { url: string; init: RequestInit; headers: Record<string, string> }

function callAt(fetchFn: ReturnType<typeof vi.fn<typeof fetch>>, index: number): Call {
  const [url, init] = fetchFn.mock.calls[index]
  return { url: String(url), init: init as RequestInit, headers: init?.headers as Record<string, string> }
}

const NOTICE = { kind: 'notice', question: '질문', log_id: 'agent-log-1', message: '안내입니다.' }

const accepted = (extra: object = {}) => () =>
  jsonResponse({ request_id: 'req-1', status: 'accepted', message: '접수했습니다.', remaining_today: 49, ...extra }, 202)

const state = (status: QuestionStatus, extra: Partial<{ result: unknown; error_code: string; message: string }> = {}) =>
  () =>
    jsonResponse({
      request_id: 'req-1',
      status,
      result: null,
      error_code: null,
      message: null,
      ...extra,
    })

describe('질문: 접수 후 결과 조회', () => {
  it('접수한 뒤 접수 번호로 조회하고 결과에 접수 시점의 남은 횟수를 붙인다', async () => {
    const { api, fetchFn } = setup([accepted(), state('processing'), state('completed', { result: NOTICE })])

    const response = await api.askQuestion({ question: '수집된 경기는 몇 판이야?' }, 'my-code')

    expect(response).toMatchObject({ kind: 'notice', log_id: 'agent-log-1', remaining_today: 49 })
    expect(fetchFn).toHaveBeenCalledTimes(3)

    const post = callAt(fetchFn, 0)
    expect(post.url).toBe('/api/questions')
    expect(post.init.method).toBe('POST')
    expect(JSON.parse(post.init.body as string)).toEqual({ question: '수집된 경기는 몇 판이야?' })

    const poll = callAt(fetchFn, 1)
    expect(poll.url).toBe('/api/questions/req-1')
    expect(poll.init.method).toBe('GET')
  })

  it('초대 코드는 접수와 조회 모두 헤더로만 보내고 URL에 넣지 않는다', async () => {
    const { api, fetchFn } = setup([accepted(), state('completed', { result: NOTICE })])
    await api.askQuestion({ question: '질문' }, 'my-code')

    for (const i of [0, 1]) {
      const call = callAt(fetchFn, i)
      expect(call.headers['X-Invite-Code']).toBe('my-code')
      expect(call.url).not.toContain('my-code')
    }
  })

  it('조건을 고르지 않으면 conditions 필드를 보내지 않는다(null도 보내지 않는다)', async () => {
    const { api, fetchFn } = setup([accepted(), state('completed', { result: NOTICE })])
    await api.askQuestion({ question: '질문' }, 'code')
    const body = JSON.parse(callAt(fetchFn, 0).init.body as string) as Record<string, unknown>
    expect('conditions' in body).toBe(false)
  })

  it('조회 사이에 간격을 두고 기다린다', async () => {
    const { api, sleep } = setup([accepted(), state('accepted'), state('processing'), state('completed', { result: NOTICE })])
    await api.askQuestion({ question: '질문' }, 'code')
    expect(sleep).toHaveBeenCalledTimes(3)
    expect(sleep).toHaveBeenCalledWith(2000)
  })

  it('진행 상태가 바뀔 때마다 알린다', async () => {
    const seen: QuestionStatus[] = []
    const { api } = setup([
      accepted(),
      state('accepted'),
      state('processing'),
      state('processing'),
      state('completed', { result: NOTICE }),
    ])
    await api.askQuestion({ question: '질문' }, 'code', (s) => seen.push(s))
    expect(seen).toEqual(['accepted', 'processing', 'completed'])
  })

  it('안내·되묻기도 completed이며 그대로 돌려준다', async () => {
    const clarification: AgentResponse = {
      ...(NOTICE as unknown as AgentResponse),
      kind: 'clarification',
      clarifications: [{ term: '아드리나', candidates: [] }],
    } as AgentResponse
    const { api } = setup([accepted(), state('completed', { result: clarification })])
    const response = await api.askQuestion({ question: '질문' }, 'code')
    expect(response.kind).toBe('clarification')
  })

  it('failed는 error_code와 message로 ApiError를 던진다', async () => {
    const { api } = setup([
      accepted(),
      state('failed', { error_code: 'AGENT_UNAVAILABLE', message: '에이전트에 연결하지 못했습니다.' }),
    ])
    await expect(api.askQuestion({ question: '질문' }, 'code')).rejects.toMatchObject({
      code: 'AGENT_UNAVAILABLE',
      message: '에이전트에 연결하지 못했습니다.',
    })
  })

  it('failed의 모르는 error_code는 UNKNOWN으로 두고 message가 없으면 기본 문구를 쓴다', async () => {
    const { api } = setup([accepted(), state('failed', { error_code: 'SOMETHING_NEW' })])
    await expect(api.askQuestion({ question: '질문' }, 'code')).rejects.toMatchObject({
      code: 'UNKNOWN',
      message: '질문을 처리하지 못했습니다. 잠시 뒤 다시 시도해 주세요.',
    })
  })

  it('completed인데 result가 없으면 UNKNOWN 오류다', async () => {
    const { api } = setup([accepted(), state('completed')])
    await expect(api.askQuestion({ question: '질문' }, 'code')).rejects.toMatchObject({ code: 'UNKNOWN' })
  })

  it('접수 단계의 오류(한도 초과)는 조회 없이 바로 던진다', async () => {
    const { api, fetchFn } = setup(() =>
      jsonResponse({ code: 'INVITE_LIMIT_EXCEEDED', message: '오늘 질문 횟수를 모두 사용했습니다.' }, 429),
    )
    await expect(api.askQuestion({ question: '질문' }, 'c')).rejects.toMatchObject({
      code: 'INVITE_LIMIT_EXCEEDED',
      status: 429,
    })
    expect(fetchFn).toHaveBeenCalledTimes(1)
  })

  it('조회 중 404는 다시 시도하지 않고 던진다', async () => {
    const { api, fetchFn } = setup([
      accepted(),
      () => jsonResponse({ code: 'QUESTION_NOT_FOUND', message: '질문을 찾을 수 없습니다.' }, 404),
    ])
    await expect(api.askQuestion({ question: '질문' }, 'c')).rejects.toMatchObject({
      code: 'QUESTION_NOT_FOUND',
    })
    expect(fetchFn).toHaveBeenCalledTimes(2)
  })

  it('조회가 일시적으로 실패해도 질문을 다시 보내지 않고 조회만 다시 시도한다', async () => {
    const fail: Responder = () => {
      throw new TypeError('Failed to fetch')
    }
    const { api, fetchFn } = setup([accepted(), fail, fail, state('completed', { result: NOTICE })])

    await expect(api.askQuestion({ question: '질문' }, 'c')).resolves.toMatchObject({ kind: 'notice' })

    const methods = fetchFn.mock.calls.map(([, init]) => init?.method)
    expect(methods).toEqual(['POST', 'GET', 'GET', 'GET']) // POST는 한 번뿐
  })

  it('조회가 연속으로 계속 실패하면 NETWORK_ERROR로 멈춘다', async () => {
    const fail: Responder = () => {
      throw new TypeError('Failed to fetch')
    }
    const { api, fetchFn } = setup([accepted(), fail])
    await expect(api.askQuestion({ question: '질문' }, 'c')).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
    expect(fetchFn).toHaveBeenCalledTimes(1 + 3)
  })

  it('접수 응답부터 420초가 지나면 자동 조회를 멈추고 접수 번호를 담은 오류를 던진다', async () => {
    let clock = 0
    // 기본값(420초)을 그대로 쓴다. 대기 함수가 시계를 앞으로 돌린다.
    const { api, fetchFn } = setup([accepted(), state('processing')], {
      now: () => clock,
      sleep: async (ms) => {
        clock += ms
      },
    })

    const error = await api.askQuestion({ question: '질문' }, 'c').catch((e: unknown) => e)

    expect(error).toMatchObject({
      code: 'PROCESSING_TIMEOUT',
      message: '처리가 오래 걸리고 있습니다. 나중에 결과를 다시 확인해 주세요.',
      accepted: { requestId: 'req-1', remainingToday: 49 },
    })
    expect(clock).toBeGreaterThanOrEqual(420_000)
    expect(clock).toBeLessThan(430_000)
    // 질문은 한 번만 보냈다. 자동 재전송은 없다.
    expect(fetchFn.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
  })

  it('조회는 겹치지 않고 직전 조회가 끝난 뒤 2초 간격으로 한다', async () => {
    const order: string[] = []
    const fetchFn = vi.fn<typeof fetch>(async (_url, init) => {
      order.push(`fetch:${init?.method}`)
      return init?.method === 'POST' ? accepted()() : state('completed', { result: NOTICE })()
    })
    const sleep = vi.fn(async (ms: number) => {
      order.push(`sleep:${ms}`)
    })
    const api = createHttpApi({ baseUrl: '/api', fetchFn, sleep })
    await api.askQuestion({ question: '질문' }, 'c')
    expect(order).toEqual(['fetch:POST', 'sleep:2000', 'fetch:GET'])
  })

  it('접수 뒤에 난 오류에는 접수 정보가 붙고 접수 전 오류에는 붙지 않는다', async () => {
    const polled = setup([
      accepted(),
      () => jsonResponse({ code: 'QUESTION_NOT_FOUND', message: '질문을 찾을 수 없습니다.' }, 404),
    ])
    await expect(polled.api.askQuestion({ question: '질문' }, 'c')).rejects.toMatchObject({
      code: 'QUESTION_NOT_FOUND',
      accepted: { requestId: 'req-1', remainingToday: 49 },
    })

    const rejected = setup(() => jsonResponse({ code: 'VALIDATION_FAILED', message: '형식 오류' }, 400))
    await expect(rejected.api.askQuestion({ question: '질문' }, 'c')).rejects.toMatchObject({
      code: 'VALIDATION_FAILED',
      accepted: null,
    })
  })

  it('failed의 알려진 error_code는 message가 비어 있으면 코드별 안내 문구를 쓴다', async () => {
    const { api } = setup([accepted(), state('failed', { error_code: 'AGENT_BUSY' })])
    await expect(api.askQuestion({ question: '질문' }, 'c')).rejects.toMatchObject({
      code: 'AGENT_BUSY',
      message: '다른 질문을 처리 중입니다. 잠시 뒤 다시 질문해 주세요.',
      accepted: { requestId: 'req-1', remainingToday: 49 },
    })
  })

  it('failed의 message가 있으면 코드별 문구보다 서버 message를 그대로 쓴다', async () => {
    const { api } = setup([
      accepted(),
      state('failed', { error_code: 'AGENT_TIMEOUT', message: '서버가 보낸 새 문구입니다.' }),
    ])
    await expect(api.askQuestion({ question: '질문' }, 'c')).rejects.toMatchObject({
      code: 'AGENT_TIMEOUT',
      message: '서버가 보낸 새 문구입니다.',
    })
  })

  it('접수 번호는 주소에 쓸 수 있게 인코딩한다', async () => {
    const { api, fetchFn } = setup([
      () => jsonResponse({ request_id: 'a/b c', status: 'accepted', message: '', remaining_today: 1 }, 202),
      state('completed', { result: NOTICE }),
    ])
    await api.askQuestion({ question: '질문' }, 'c')
    expect(callAt(fetchFn, 1).url).toBe('/api/questions/a%2Fb%20c')
  })
})

describe('결과 다시 확인(checkQuestion)', () => {
  it('접수 번호로 한 번 조회하고 완료되었으면 결과를 돌려준다. 질문을 다시 보내지 않는다', async () => {
    const { api, fetchFn } = setup(state('completed', { result: NOTICE }))
    const response = await api.checkQuestion('req-9', 41, 'my-code')

    expect(response).toMatchObject({ kind: 'notice', remaining_today: 41 })
    expect(fetchFn).toHaveBeenCalledTimes(1)
    const call = callAt(fetchFn, 0)
    expect(call.url).toBe('/api/questions/req-9')
    expect(call.init.method).toBe('GET')
    expect(call.headers['X-Invite-Code']).toBe('my-code')
  })

  it('아직 처리 중이면 다시 PROCESSING_TIMEOUT을 던진다', async () => {
    const { api } = setup(state('processing'))
    await expect(api.checkQuestion('req-9', 41, 'c')).rejects.toMatchObject({
      code: 'PROCESSING_TIMEOUT',
      accepted: { requestId: 'req-9', remainingToday: 41 },
    })
  })

  it('실패했으면 코드별 오류를 던진다', async () => {
    const { api } = setup(state('failed', { error_code: 'RESULT_STORAGE_FAILED' }))
    await expect(api.checkQuestion('req-9', 41, 'c')).rejects.toMatchObject({
      code: 'RESULT_STORAGE_FAILED',
      accepted: { requestId: 'req-9', remainingToday: 41 },
    })
  })
})

describe('후기와 수집 현황 요청', () => {
  it('후기는 log_id 경로로 평가와 의견을 보내고 초대 코드는 헤더에만 싣는다', async () => {
    const { api, fetchFn } = setup(() => jsonResponse({ saved: true }))
    await expect(
      api.submitFeedback('agent-log-1', { rating: 'like', comment: '유용했어요.' }, 'my-code'),
    ).resolves.toEqual({ saved: true })

    const { url, init, headers } = callAt(fetchFn, 0)
    expect(url).toBe('/api/questions/agent-log-1/feedback')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body as string)).toEqual({ rating: 'like', comment: '유용했어요.' })
    expect(headers['X-Invite-Code']).toBe('my-code')
  })

  it('log_id는 주소에 쓸 수 있게 인코딩한다', async () => {
    const { api, fetchFn } = setup(() => jsonResponse({ saved: true }))
    await api.submitFeedback('a/b', { rating: 'dislike' }, 'c')
    expect(callAt(fetchFn, 0).url).toBe('/api/questions/a%2Fb/feedback')
  })

  it('수집 현황은 /api/collection/summary를 초대 코드 헤더와 함께 조회하고 확정 형식 그대로 돌려준다', async () => {
    // 웹 API 계약 "이용자용 수집 현황(2026-10-13)"의 확정 형식이다.
    const summary = {
      total_games: 72_140,
      by_tier: [
        { tier: '다이아몬드', users: 3_412, participant_appearances: 436_118 },
        { tier: '플래티넘 미만', users: 0, participant_appearances: 0 },
        { tier: null, users: 12, participant_appearances: 1_840 },
      ],
      patches: [
        { major: 5, minor: 0, first_match: '2026-10-01T00:10:00+09:00', last_match: '2026-10-13T11:30:00+09:00' },
        { major: 4, minor: 0, first_match: null, last_match: null },
      ],
      latest_collected_at: '2026-10-13T11:42:00+09:00',
      snapshot_at: '2026-10-13T11:45:10+09:00',
      cached_until: '2026-10-13T11:50:10+09:00',
    }
    const { api, fetchFn } = setup(() => jsonResponse(summary))
    await expect(api.getCollectionSummary('my-code')).resolves.toEqual(summary)

    const { url, init, headers } = callAt(fetchFn, 0)
    expect(url).toBe('/api/collection/summary')
    expect(init.method).toBe('GET')
    expect(headers['X-Invite-Code']).toBe('my-code')
  })

  it('수집 현황 집계 실패(503 COLLECTION_UNAVAILABLE)는 서버 message를 그대로 담은 오류가 된다', async () => {
    const { api } = setup(() =>
      jsonResponse(
        { code: 'COLLECTION_UNAVAILABLE', message: '수집 현황을 가져오지 못했습니다. 잠시 뒤 다시 확인해 주세요.' },
        503,
      ),
    )
    await expect(api.getCollectionSummary('c')).rejects.toMatchObject({
      code: 'COLLECTION_UNAVAILABLE',
      message: '수집 현황을 가져오지 못했습니다. 잠시 뒤 다시 확인해 주세요.',
      status: 503,
    })
  })
})

describe('그 밖의 요청', () => {
  it('초대 코드 확인은 코드를 본문으로 보내고 초대 코드 헤더는 붙이지 않는다', async () => {
    const { api, fetchFn } = setup(() => jsonResponse({ valid: true, remaining_today: 50 }))
    await expect(api.verifyInvite('abc')).resolves.toEqual({ valid: true, remaining_today: 50 })

    const { url, init, headers } = callAt(fetchFn, 0)
    expect(url).toBe('/api/invite/verify')
    expect(headers['X-Invite-Code']).toBeUndefined()
    expect(JSON.parse(init.body as string)).toEqual({ code: 'abc' })
  })

  it('조건 목록 요청에도 초대 코드 헤더를 붙인다', async () => {
    const { api, fetchFn } = setup(() => jsonResponse({ patches: [], tiers: [], characters: [] }))
    await api.getConditions('abc')
    const { url, init, headers } = callAt(fetchFn, 0)
    expect(url).toBe('/api/conditions')
    expect(init.method).toBe('GET')
    expect(headers['X-Invite-Code']).toBe('abc')
  })

  it('오류 본문의 code와 message를 ApiError로 옮긴다', async () => {
    const { api } = setup(() =>
      jsonResponse({ code: 'INVITE_INVALID', message: '초대 코드를 확인해 주세요.' }, 401),
    )
    await expect(api.getConditions('c')).rejects.toMatchObject({
      code: 'INVITE_INVALID',
      message: '초대 코드를 확인해 주세요.',
      status: 401,
    })
  })

  it('모르는 오류 code는 UNKNOWN으로 두고 서버 message는 그대로 보여 준다', async () => {
    const { api } = setup(() => jsonResponse({ code: 'SOMETHING_NEW', message: '새 오류입니다.' }, 500))
    await expect(api.getConditions('c')).rejects.toMatchObject({
      code: 'UNKNOWN',
      message: '새 오류입니다.',
      status: 500,
    })
  })

  it('JSON이 아닌 오류 본문은 상태 코드가 든 기본 메시지로 바꾼다', async () => {
    const { api } = setup(() => new Response('<html>Bad Gateway</html>', { status: 502 }))
    await expect(api.getConditions('c')).rejects.toMatchObject({ code: 'UNKNOWN', status: 502 })
  })

  it('서버에 연결하지 못하면 NETWORK_ERROR다', async () => {
    const { api } = setup(() => {
      throw new TypeError('Failed to fetch')
    })
    await expect(api.verifyInvite('c')).rejects.toMatchObject({ code: 'NETWORK_ERROR', status: null })
  })

  it('대기 시간이 지나면 TIMEOUT이다', async () => {
    const { api } = setup(() => {
      throw new DOMException('timed out', 'TimeoutError')
    })
    await expect(api.verifyInvite('c')).rejects.toMatchObject({ code: 'TIMEOUT' })
  })
})
