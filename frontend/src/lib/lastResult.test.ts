import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMockApi } from '../api/mock'
import { clearLastResult, loadLastResult, saveLastResult } from './lastResult'

afterEach(() => {
  vi.restoreAllMocks()
})

async function sampleResponse() {
  return createMockApi({ delayMs: 0, scenario: () => 'answer' }).askQuestion({ question: '질문' }, 'friend')
}

describe('마지막 결과 보관', () => {
  it('같은 초대 코드로 저장한 결과를 다시 읽는다', async () => {
    const response = await sampleResponse()
    saveLastResult('friend', { requestId: '3f2b8c1e-5a4d-4c2b-9e1f-0a1b2c3d4e5f', response })
    expect(loadLastResult('friend')).toEqual({ requestId: '3f2b8c1e-5a4d-4c2b-9e1f-0a1b2c3d4e5f', response })
  })

  it('접수 번호를 모르는 결과도 보관한다', async () => {
    const response = await sampleResponse()
    saveLastResult('friend', { requestId: null, response })
    expect(loadLastResult('friend')?.requestId).toBeNull()
  })

  it('초대 코드 원문을 저장하지 않고, 다른 초대 코드로는 읽지 않고 지운다', async () => {
    saveLastResult('secret-code', { requestId: null, response: await sampleResponse() })
    expect(JSON.stringify(Object.entries(window.sessionStorage))).not.toContain('secret-code')
    expect(loadLastResult('other')).toBeNull()
    expect(loadLastResult('secret-code')).toBeNull()
  })

  it('형식이 깨진 값은 무시하고 지운다', () => {
    window.sessionStorage.setItem('er-analytics.last-result', '{"owner":"x","requestId":null,"response":{"kind":"x"}}')
    expect(loadLastResult('friend')).toBeNull()
    window.sessionStorage.setItem('er-analytics.last-result', 'not json')
    expect(loadLastResult('friend')).toBeNull()
  })

  it('지우면 다시 읽지 못한다', async () => {
    saveLastResult('friend', { requestId: null, response: await sampleResponse() })
    clearLastResult()
    expect(loadLastResult('friend')).toBeNull()
  })

  it('저장소를 쓸 수 없어도 예외를 던지지 않는다', async () => {
    const response = await sampleResponse()
    for (const method of ['setItem', 'getItem', 'removeItem'] as const) {
      vi.spyOn(Storage.prototype, method).mockImplementation(() => {
        throw new Error('blocked')
      })
    }
    expect(() => saveLastResult('friend', { requestId: null, response })).not.toThrow()
    expect(loadLastResult('friend')).toBeNull()
    expect(() => clearLastResult()).not.toThrow()
  })
})
