import { act, cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { ApiError } from './api'
import { createHttpApi } from './api/http'
import { createMockApi } from './api/mock'
import type { ErApi } from './api/types'
import {
  loadRecentQuestions, RECENT_QUESTIONS_KEY, RECENT_QUESTION_TTL_MS, recentQuestionsFor,
  rememberRecentQuestion, setRecentQuestionsEnabled,
} from './lib/recentQuestions'
import { saveInviteCode } from './lib/session'

afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers() })

const ID = '3f2b8c1e-5a4d-4c2b-9e1f-0a1b2c3d4e5f'
const OTHER_ID = '00000000-0000-4000-8000-000000000002'
const QUESTION = '최근 질문으로 다시 찾을 내용'
const stored = (code = 'friend', question = QUESTION, requestId = ID) =>
  rememberRecentQuestion(code, { requestId, question, acceptedAt: Date.now() })
const list = (code = 'friend') => recentQuestionsFor(loadRecentQuestions(), code)
const region = () => screen.getByRole('region', { name: '최근 질문' })
const recentButton = () => within(region()).getByRole('button', { name: new RegExp(QUESTION) })

function setup(overrides: Partial<ErApi> = {}) {
  const base = createMockApi({ delayMs: 0 })
  const api = {
    ...base,
    askQuestion: vi.fn<ErApi['askQuestion']>(base.askQuestion),
    checkQuestion: vi.fn<ErApi['checkQuestion']>(base.checkQuestion),
    ...overrides,
  }
  const user = userEvent.setup()
  const view = render(<App api={api} />)
  return { user, api, ...view }
}

async function enter(user: ReturnType<typeof userEvent.setup>, code = 'friend') {
  await user.type(screen.getByLabelText('초대 코드'), code)
  await user.click(screen.getByRole('button', { name: '확인' }))
  await screen.findByRole('heading', { name: '조건' })
}

async function ask(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByRole('textbox', { name: /궁금한 점/ }), QUESTION)
  await user.click(screen.getByRole('button', { name: '질문하기' }))
}

describe('최근 질문 화면', () => {
  it('결과가 오기 전 접수 콜백 시점에 기록하고 실패해도 기록은 유지한다', async () => {
    let reject!: (e: unknown) => void
    const { user } = setup({ askQuestion: async (_request, _code, _status, accepted) => {
      accepted?.({ requestId: ID, remainingToday: 49, acceptedAt: Date.now() })
      return new Promise((_resolve, fail) => { reject = fail })
    } })
    await enter(user)
    await ask(user)
    expect(list()).toMatchObject([{ requestId: ID, question: QUESTION }])
    expect(recentButton()).toBeDisabled()
    await act(async () => reject(new ApiError('AGENT_TIMEOUT', '처리 실패', null, { requestId: ID, remainingToday: 49 })))
    expect(recentButton()).toBeEnabled()
    expect(list()).toHaveLength(1)
  })

  it.each(['INVITE_LIMIT_EXCEEDED', 'GLOBAL_LIMIT_EXCEEDED', 'QUESTION_BUSY'] as const)('접수 전 거절 %s는 기록하지 않는다', async (code) => {
    const { user } = setup({ askQuestion: async () => { throw new ApiError(code, '접수 전 거절', code === 'QUESTION_BUSY' ? 503 : 429) } })
    await enter(user)
    await ask(user)
    expect(await screen.findByRole('alert')).toHaveTextContent('접수 전 거절')
    expect(list()).toEqual([])
    expect(within(region()).queryByRole('list')).not.toBeInTheDocument()
  })

  it('새 탭처럼 다시 입력한 코드의 목록에서 키보드로 고르면 HTTP GET 한 번만 하고 후기를 보여 준다', async () => {
    const first = setup()
    await enter(first.user)
    await ask(first.user)
    await screen.findByLabelText('답변')
    const requestId = list()[0].requestId
    first.unmount()
    sessionStorage.clear()
    const mockServer = createMockApi({ delayMs: 0 })
    const result = await mockServer.checkQuestion(requestId, null, 'friend')
    const fetchFn = vi.fn<typeof fetch>(async () => new Response(JSON.stringify({
      request_id: requestId, status: 'completed', result, error_code: null, message: null,
    }), { status: 200 }))
    const http = createHttpApi({ fetchFn })
    const second = setup({ checkQuestion: vi.fn(http.checkQuestion) })
    await enter(second.user)
    recentButton().focus()
    await second.user.keyboard('{Enter}')
    await screen.findByLabelText('답변')
    expect(fetchFn).toHaveBeenCalledTimes(1)
    const [url, init] = fetchFn.mock.calls[0]
    expect(url).toBe(`/api/questions/${requestId}`)
    expect(init?.method).toBe('GET')
    expect(init?.headers).toMatchObject({ 'X-Invite-Code': 'friend' })
    expect(second.api.askQuestion).not.toHaveBeenCalled()
    expect(second.api.checkQuestion).toHaveBeenCalledWith(requestId, null, 'friend')
    expect(screen.getByText('오늘 남은 질문 50회')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '후기 보내기' })).toBeInTheDocument()
    expect(within(region()).getByText(/질문 횟수에 영향이 없습니다/)).toBeInTheDocument()
  })

  it('아직 처리 중이면 자동 조회 없이 기존 결과 다시 확인 버튼을 쓴다', async () => {
    stored()
    const check = vi.fn<ErApi['checkQuestion']>(async () => {
      throw new ApiError('PROCESSING_TIMEOUT', '처리가 오래 걸리고 있습니다.', null, { requestId: ID, remainingToday: null })
    })
    const { user, api } = setup({ checkQuestion: check })
    await enter(user)
    await user.click(recentButton())
    expect(await screen.findByRole('button', { name: '결과 다시 확인' })).toBeInTheDocument()
    expect(list()).toHaveLength(1)
    expect(check).toHaveBeenCalledTimes(1)
    await user.click(screen.getByRole('button', { name: '결과 다시 확인' }))
    expect(check).toHaveBeenCalledTimes(2)
    expect(api.askQuestion).not.toHaveBeenCalled()
    expect(screen.getByText('오늘 남은 질문 50회')).toBeInTheDocument()
  })

  it('404면 기존 공통 안내를 보여 주고 선택한 항목만 목록에서 지운다', async () => {
    stored()
    stored('friend', '남겨 둘 질문', OTHER_ID)
    const { user } = setup({ checkQuestion: async () => { throw new ApiError('QUESTION_NOT_FOUND', '찾지 못했습니다.', 404) } })
    await enter(user)
    await user.click(recentButton())
    expect(await screen.findByRole('alert')).toHaveTextContent('번호와 초대 코드를 확인해 주세요')
    expect(list().map((e) => e.requestId)).toEqual([OTHER_ID])
    expect(within(region()).queryByRole('button', { name: new RegExp(QUESTION) })).not.toBeInTheDocument()
  })

  it('통신 오류면 목록을 유지한다', async () => {
    stored()
    const { user } = setup({ checkQuestion: async () => { throw new ApiError('NETWORK_ERROR', '연결 실패') } })
    await enter(user)
    await user.click(recentButton())
    expect(await screen.findByRole('alert')).toHaveTextContent('연결 실패')
    expect(list()).toHaveLength(1)
  })

  it('초대 코드를 바꿔도 목록은 유지하고 다른 코드의 질문은 보여 주지 않는다', async () => {
    stored()
    stored('other', '다른 코드의 질문', OTHER_ID)
    const { user } = setup()
    await enter(user)
    expect(recentButton()).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '초대 코드 바꾸기' }))
    await enter(user, 'other')
    expect(within(region()).queryByText(QUESTION)).not.toBeInTheDocument()
    expect(within(region()).getByText('다른 코드의 질문')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '초대 코드 바꾸기' }))
    await enter(user)
    expect(recentButton()).toBeInTheDocument()
    expect(list('other')).toHaveLength(1)
  })

  it('기록 지우기는 모든 코드에 적용하고 다음 질문은 다시 남긴다', async () => {
    stored()
    stored('other', '다른 질문', OTHER_ID)
    const { user } = setup()
    await enter(user)
    await user.click(screen.getByRole('button', { name: '이 기기에 남은 질문 기록 지우기' }))
    expect(list()).toEqual([])
    expect(list('other')).toEqual([])
    await ask(user)
    await screen.findByLabelText('답변')
    expect(list()).toHaveLength(1)
  })

  it('기록 끄기는 기존 목록도 지우고 재열기 뒤 새 접수도 남기지 않는다', async () => {
    stored()
    const first = setup()
    await enter(first.user)
    await first.user.click(screen.getByRole('checkbox', { name: '이 기기에 질문 기록 남기기' }))
    expect(list()).toEqual([])
    first.unmount()
    sessionStorage.clear()
    const next = setup()
    await enter(next.user)
    expect(screen.getByRole('checkbox')).not.toBeChecked()
    await ask(next.user)
    await screen.findByLabelText('답변')
    expect(list()).toEqual([])
  })

  it('접수 응답을 기다리는 중 끄면 나중에 도착한 접수도 기록하지 않는다', async () => {
    let accept!: () => void
    const { user } = setup({ askQuestion: async (_request, _code, _status, onAccepted) => {
      accept = () => onAccepted?.({ requestId: ID, acceptedAt: Date.now(), remainingToday: 49 })
      return new Promise(() => {})
    } })
    await enter(user)
    await ask(user)
    await user.click(screen.getByRole('checkbox'))
    await act(async () => accept())
    expect(list()).toEqual([])
  })

  it('다른 탭의 끄기·삭제를 반영하고 자동 조회하지 않는다', async () => {
    stored()
    const { user, api } = setup()
    await enter(user)
    act(() => {
      setRecentQuestionsEnabled(false)
      window.dispatchEvent(new StorageEvent('storage', { key: RECENT_QUESTIONS_KEY }))
    })
    expect(screen.getByRole('checkbox')).not.toBeChecked()
    expect(within(region()).queryByRole('list')).not.toBeInTheDocument()
    expect(api.checkQuestion).not.toHaveBeenCalled()
  })

  it('열린 화면에서도 7일이 지난 목록을 정리한다', async () => {
    stored()
    vi.useFakeTimers()
    saveInviteCode('friend')
    const { api } = setup()
    vi.setSystemTime(Date.now() + RECENT_QUESTION_TTL_MS)
    await act(async () => vi.advanceTimersByTime(60_000))
    expect(within(region()).queryByRole('list')).not.toBeInTheDocument()
    expect(api.checkQuestion).not.toHaveBeenCalled()
  })

  it('초대 코드 입력 전에도 앱을 열면 만료된 기록을 지운다', () => {
    stored()
    vi.spyOn(Date, 'now').mockReturnValue(Date.now() + RECENT_QUESTION_TTL_MS)
    setup()
    expect(JSON.parse(localStorage.getItem(RECENT_QUESTIONS_KEY)!).entries).toEqual([])
  })

  it('끄기 설정 저장에 실패해도 현재 탭에서는 새 질문을 기록하지 않는다', async () => {
    const { user } = setup()
    await enter(user)
    const original = Storage.prototype.setItem
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (this: Storage, key, value) {
      if (key === RECENT_QUESTIONS_KEY) throw new Error('blocked')
      original.call(this, key, value)
    })
    await user.click(screen.getByRole('checkbox'))
    act(() => window.dispatchEvent(new Event('focus')))
    expect(screen.getByRole('checkbox')).not.toBeChecked()
    await ask(user)
    await screen.findByLabelText('답변')
    expect(list()).toEqual([])
    expect(within(region()).getByRole('status')).toHaveTextContent('저장·삭제하지 못했습니다')
  })

  it('깨진 저장값이나 저장소 차단에도 화면과 질문은 동작하고 실패를 알린다', async () => {
    localStorage.setItem(RECENT_QUESTIONS_KEY, '{broken')
    const first = setup()
    await enter(first.user)
    expect(within(region()).queryByRole('list')).not.toBeInTheDocument()
    cleanup()
    sessionStorage.clear()
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
    const next = setup()
    await enter(next.user)
    await ask(next.user)
    await screen.findByLabelText('답변')
    expect(within(region()).getByRole('status')).toHaveTextContent('저장·삭제하지 못했습니다')
  })
})
