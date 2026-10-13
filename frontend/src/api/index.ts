import { createHttpApi } from './http'
import { createMockApi } from './mock'
import type { MockApiOptions } from './mock'
import type { ErApi } from './types'

export { ApiError, asApiError } from './errors'
export type { ApiErrorCode } from './errors'
export { MOCK_SCENARIOS, mockControl } from './mock'
export type { MockScenarioId } from './mock'
export * from './types'

/** 서버가 아직 없으므로 기본은 가짜 응답이다. 실제 서버에 붙일 때 VITE_API_MODE=http로 바꾼다. */
export const isMockMode = import.meta.env.VITE_API_MODE !== 'http'

export function createApi(mockOptions?: MockApiOptions): ErApi {
  return isMockMode ? createMockApi(mockOptions) : createHttpApi()
}
