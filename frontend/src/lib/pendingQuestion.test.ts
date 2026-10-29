import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearPendingQuestion, loadPendingQuestion, ownerKey, savePendingQuestion } from './pendingQuestion'
import { normalizeRequestId } from './requestId'

const PENDING = {
  requestId: '3f2b8c1e-5a4d-4c2b-9e1f-0a1b2c3d4e5f',
  remainingToday: 12,
  acceptedAt: 1_000,
  question: '수집된 경기는 몇 판이야?',
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('진행 중인 질문 보관', () => {
  it('같은 초대 코드로 저장한 값을 다시 읽는다', () => {
    savePendingQuestion('friend', PENDING)
    expect(loadPendingQuestion('friend')).toEqual(PENDING)
  })

  it('초대 코드 원문을 저장하지 않는다', () => {
    savePendingQuestion('secret-code', PENDING)
    const raw = JSON.stringify(Object.entries(window.sessionStorage))
    expect(raw).not.toContain('secret-code')
    expect(raw).toContain(ownerKey('secret-code'))
  })

  it('다른 초대 코드로는 읽지 않고 지운다', () => {
    savePendingQuestion('friend', PENDING)
    expect(loadPendingQuestion('other')).toBeNull()
    expect(loadPendingQuestion('friend')).toBeNull()
  })

  it('형식이 깨진 값은 무시하고 지운다', () => {
    window.sessionStorage.setItem('er-analytics.pending-question', '{"requestId": 3}')
    expect(loadPendingQuestion('friend')).toBeNull()
    window.sessionStorage.setItem('er-analytics.pending-question', 'not json')
    expect(loadPendingQuestion('friend')).toBeNull()
  })

  it('번호를 주면 그 번호를 보관하고 있을 때만 지운다', () => {
    savePendingQuestion('friend', PENDING)
    clearPendingQuestion('11111111-1111-4111-8111-111111111111')
    expect(loadPendingQuestion('friend')).not.toBeNull()
    clearPendingQuestion(PENDING.requestId)
    expect(loadPendingQuestion('friend')).toBeNull()
  })

  it('저장소를 쓸 수 없어도 예외를 던지지 않는다', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    expect(() => savePendingQuestion('friend', PENDING)).not.toThrow()
    expect(loadPendingQuestion('friend')).toBeNull()
    expect(() => clearPendingQuestion()).not.toThrow()
    expect(() => clearPendingQuestion(PENDING.requestId)).not.toThrow()
  })
})

describe('접수 번호 형식', () => {
  it('계약의 소문자 UUID 버전 4만 받는다', () => {
    expect(normalizeRequestId(PENDING.requestId)).toBe(PENDING.requestId)
    expect(normalizeRequestId('  3F2B8C1E-5A4D-4C2B-9E1F-0A1B2C3D4E5F ')).toBe(PENDING.requestId)
    expect(normalizeRequestId('3f2b8c1e-5a4d-1c2b-9e1f-0a1b2c3d4e5f')).toBeNull() // 버전 1
    expect(normalizeRequestId('3f2b8c1e-5a4d-4c2b-7e1f-0a1b2c3d4e5f')).toBeNull() // 변형 비트
    expect(normalizeRequestId('3f2b8c1e5a4d4c2b9e1f0a1b2c3d4e5f')).toBeNull() // 하이픈 없음
    expect(normalizeRequestId('mock-request-1')).toBeNull()
    expect(normalizeRequestId('')).toBeNull()
  })
})
