import { afterEach, describe, expect, it, vi } from 'vitest'

/** 환경 값을 바꿔 가며 index 모듈을 새로 불러와 어떤 모드가 선택되는지 본다. */
async function loadIsMockMode(env: { VITE_API_MODE?: string; PROD: boolean }) {
  vi.resetModules()
  vi.stubEnv('VITE_API_MODE', env.VITE_API_MODE ?? '')
  vi.stubEnv('PROD', env.PROD)
  const module = await import('./index')
  return module.isMockMode
}

afterEach(() => {
  vi.unstubAllEnvs()
  vi.resetModules()
})

describe('가짜 응답 / 실제 호출 선택', () => {
  it('명시하지 않으면 개발·테스트는 가짜 응답, 프로덕션 빌드는 실제 호출이다', async () => {
    expect(await loadIsMockMode({ PROD: false })).toBe(true)
    expect(await loadIsMockMode({ PROD: true })).toBe(false)
  })

  it('VITE_API_MODE를 명시하면 빌드 종류보다 우선한다', async () => {
    expect(await loadIsMockMode({ VITE_API_MODE: 'mock', PROD: true })).toBe(true)
    expect(await loadIsMockMode({ VITE_API_MODE: 'http', PROD: false })).toBe(false)
  })

  it('알 수 없는 값은 명시하지 않은 것으로 본다', async () => {
    expect(await loadIsMockMode({ VITE_API_MODE: 'live', PROD: true })).toBe(false)
    expect(await loadIsMockMode({ VITE_API_MODE: 'live', PROD: false })).toBe(true)
  })
})
