import { afterEach, describe, expect, it, vi } from 'vitest'
import { ownerKey } from './pendingQuestion'
import {
  clearRecentQuestions, forgetRecentQuestion, loadRecentQuestions, RECENT_QUESTIONS_KEY,
  RECENT_QUESTION_TTL_MS, recentQuestionsFor, rememberRecentQuestion, setRecentQuestionsEnabled,
} from './recentQuestions'

afterEach(() => vi.restoreAllMocks())

const id = (i: number) => `00000000-0000-4000-8000-${String(i).padStart(12, '0')}`
const entry = (i = 1, acceptedAt = Date.now()) => ({ requestId: id(i), question: `질문 ${i}`, acceptedAt })
const list = (code = 'friend') => recentQuestionsFor(loadRecentQuestions(), code)
const store = (value: unknown) => localStorage.setItem(RECENT_QUESTIONS_KEY, JSON.stringify(value))

describe('최근 질문 보관 정책', () => {
  it('기본은 켬이며 허용한 값과 코드 해시만 저장하고 탭 저장소와 독립적이다', () => {
    const question = { ...entry(), sql: 'private sql', result: { rows: ['private rows'] } }
    rememberRecentQuestion('secret-invite', question)
    sessionStorage.clear()
    expect(list('secret-invite')).toEqual([entry(1, question.acceptedAt)])
    const stored = JSON.parse(localStorage.getItem(RECENT_QUESTIONS_KEY)!)
    expect(stored).toEqual({ version: 1, enabled: true, entries: [{ owner: ownerKey('secret-invite'), ...entry(1, question.acceptedAt) }] })
    expect(JSON.stringify(stored)).not.toMatch(/secret-invite|private sql|private rows/)
  })

  it('각 코드에 최신 10건만 남기고 중복 번호는 한 번만 보인다', () => {
    const now = Date.now()
    for (let i = 1; i <= 13; i += 1) rememberRecentQuestion('friend', entry(i, now - 14 + i))
    rememberRecentQuestion('other', entry(30))
    rememberRecentQuestion('friend', entry(13, now - 1))
    expect(list().map((e) => e.requestId)).toEqual(Array.from({ length: 10 }, (_, i) => id(13 - i)))
    expect(list('other')).toHaveLength(1)
  })

  it('7일 경계에서 만료되고 다른 코드의 만료 항목도 저장소에서 정리한다', () => {
    const now = Date.now()
    store({ version: 1, enabled: true, entries: [
      { owner: ownerKey('friend'), ...entry(1, now - RECENT_QUESTION_TTL_MS + 1) },
      { owner: ownerKey('other'), ...entry(2, now - RECENT_QUESTION_TTL_MS) },
    ] })
    const state = loadRecentQuestions(now)
    expect(recentQuestionsFor(state, 'friend')).toHaveLength(1)
    expect(recentQuestionsFor(state, 'other')).toHaveLength(0)
    expect(JSON.parse(localStorage.getItem(RECENT_QUESTIONS_KEY)!).entries).toHaveLength(1)
  })

  it('모든 코드의 목록을 지우되 저장 설정·가짜 서버·기존 탭 복구는 유지한다', () => {
    rememberRecentQuestion('friend', entry())
    rememberRecentQuestion('other', entry(2))
    sessionStorage.setItem('er-analytics.last-result', 'existing')
    localStorage.setItem('er-analytics.mock-server-requests', 'server')
    clearRecentQuestions()
    expect(list()).toEqual([])
    expect(list('other')).toEqual([])
    expect(loadRecentQuestions().enabled).toBe(true)
    expect(sessionStorage.getItem('er-analytics.last-result')).toBe('existing')
    expect(localStorage.getItem('er-analytics.mock-server-requests')).toBe('server')
  })

  it('끄기는 모든 목록을 지우고 재열기·다른 코드에도 유지되며 켠 뒤 새 접수만 저장한다', () => {
    rememberRecentQuestion('friend', entry())
    rememberRecentQuestion('other', entry(2))
    setRecentQuestionsEnabled(false)
    rememberRecentQuestion('other', entry(3))
    expect(loadRecentQuestions()).toMatchObject({ enabled: false, entries: [] })
    setRecentQuestionsEnabled(true)
    expect(list()).toEqual([])
    rememberRecentQuestion('other', entry(4))
    expect(list('other').map((e) => e.requestId)).toEqual([id(4)])
  })

  it('404 항목만 해당 코드에서 지우고 다른 코드의 같은 번호는 유지한다', () => {
    rememberRecentQuestion('friend', entry())
    rememberRecentQuestion('friend', entry(2))
    rememberRecentQuestion('other', entry())
    forgetRecentQuestion('friend', id(1))
    expect(list().map((e) => e.requestId)).toEqual([id(2)])
    expect(list('other')).toHaveLength(1)
  })
})

describe('저장소 오류와 형식 검사', () => {
  it.each(['not json', 'null', '[]', '{"version":2,"enabled":true,"entries":[]}', '{"version":1,"enabled":"yes","entries":[]}'])('깨진 문서 %s를 빈 목록으로 복구한다', (raw) => {
    localStorage.setItem(RECENT_QUESTIONS_KEY, raw)
    expect(list()).toEqual([])
    expect(loadRecentQuestions().available).toBe(true)
  })

  it.each([
    { requestId: 'bad-id' }, { requestId: id(1).toUpperCase().replace('4000', '5000') },
    { question: '' }, { question: '   ' }, { question: null }, { owner: 'raw-code' },
    { acceptedAt: '2026-10-29' }, { acceptedAt: -1 }, { acceptedAt: 1.5 },
    { acceptedAt: 99_999_999_999_999 },
  ])('잘못된 항목 %j를 버리고 유효한 항목은 보존한다', (bad) => {
    store({ version: 1, enabled: true, entries: [
      { owner: ownerKey('friend'), ...entry(), ...bad },
      { owner: ownerKey('friend'), ...entry(2) }, null,
    ] })
    expect(list().map((e) => e.requestId)).toEqual([id(2)])
  })

  it('끄기 문서에 끼워 넣은 목록도 제거한다', () => {
    store({ version: 1, enabled: false, entries: [{ owner: ownerKey('friend'), ...entry() }] })
    expect(loadRecentQuestions()).toMatchObject({ enabled: false, entries: [] })
  })

  it('저장할 때도 접수 번호·시각·질문을 검사한다', () => {
    rememberRecentQuestion('friend', { ...entry(), requestId: 'bad' })
    rememberRecentQuestion('friend', { ...entry(), acceptedAt: Number.NaN })
    rememberRecentQuestion('friend', { ...entry(), question: '' })
    expect(list()).toEqual([])
  })

  it('읽기가 차단되면 저장으로 덮어쓰지 않고 실패 상태를 반환한다', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    const write = vi.spyOn(Storage.prototype, 'setItem')
    expect(rememberRecentQuestion('friend', entry()).available).toBe(false)
    expect(write).not.toHaveBeenCalled()
    expect(() => forgetRecentQuestion('friend', id(1))).not.toThrow()
  })

  it('용량 초과·삭제 실패도 던지지 않고 실패를 반환한다', () => {
    rememberRecentQuestion('friend', entry())
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
    expect(rememberRecentQuestion('friend', entry(2)).available).toBe(false)
    expect(clearRecentQuestions().available).toBe(false)
    expect(setRecentQuestionsEnabled(false)).toMatchObject({ enabled: false, available: false })
  })
})
