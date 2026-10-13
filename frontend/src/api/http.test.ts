import { describe, expect, it, vi } from 'vitest'
import { createHttpApi } from './http'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function setup(respond: () => Response | Promise<Response>) {
  const fetchFn = vi.fn<typeof fetch>(async () => respond())
  return { api: createHttpApi({ baseUrl: '/api', fetchFn }), fetchFn }
}

function lastCall(fetchFn: ReturnType<typeof vi.fn<typeof fetch>>) {
  const [url, init] = fetchFn.mock.calls[fetchFn.mock.calls.length - 1]
  return { url: String(url), init: init as RequestInit, headers: init?.headers as Record<string, string> }
}

describe('HTTP 클라이언트', () => {
  it('질문은 /api/questions로 보내고 초대 코드는 헤더에만 싣는다', async () => {
    const { api, fetchFn } = setup(() => jsonResponse({ kind: 'notice', remaining_today: 49 }))
    await api.askQuestion({ question: '수집된 경기는 몇 판이야?' }, 'my-code')

    const { url, init, headers } = lastCall(fetchFn)
    expect(url).toBe('/api/questions')
    expect(url).not.toContain('my-code')
    expect(init.method).toBe('POST')
    expect(headers['X-Invite-Code']).toBe('my-code')
    expect(headers['Content-Type']).toBe('application/json')
    expect(JSON.parse(init.body as string)).toEqual({ question: '수집된 경기는 몇 판이야?' })
  })

  it('조건을 고르지 않으면 conditions 필드를 보내지 않는다(null도 보내지 않는다)', async () => {
    const { api, fetchFn } = setup(() => jsonResponse({}))
    await api.askQuestion({ question: '질문' }, 'code')
    const body = JSON.parse(lastCall(fetchFn).init.body as string) as Record<string, unknown>
    expect('conditions' in body).toBe(false)
  })

  it('초대 코드 확인은 코드를 본문으로 보내고 초대 코드 헤더는 붙이지 않는다', async () => {
    const { api, fetchFn } = setup(() => jsonResponse({ valid: true, remaining_today: 50 }))
    await expect(api.verifyInvite('abc')).resolves.toEqual({ valid: true, remaining_today: 50 })

    const { url, init, headers } = lastCall(fetchFn)
    expect(url).toBe('/api/invite/verify')
    expect(headers['X-Invite-Code']).toBeUndefined()
    expect(JSON.parse(init.body as string)).toEqual({ code: 'abc' })
  })

  it('조건 목록 요청에도 초대 코드 헤더를 붙인다', async () => {
    const { api, fetchFn } = setup(() => jsonResponse({ patches: [], tiers: [], characters: [] }))
    await api.getConditions('abc')
    const { url, init, headers } = lastCall(fetchFn)
    expect(url).toBe('/api/conditions')
    expect(init.method).toBe('GET')
    expect(headers['X-Invite-Code']).toBe('abc')
  })

  it('오류 본문의 code와 message를 ApiError로 옮긴다', async () => {
    const { api } = setup(() =>
      jsonResponse({ code: 'INVITE_LIMIT_EXCEEDED', message: '오늘 질문 횟수를 모두 사용했습니다.' }, 429),
    )
    await expect(api.askQuestion({ question: '질문' }, 'c')).rejects.toMatchObject({
      code: 'INVITE_LIMIT_EXCEEDED',
      message: '오늘 질문 횟수를 모두 사용했습니다.',
      status: 429,
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
