import { createHttpApi } from './http'
import { createMockApi } from './mock'
import type { MockApiOptions } from './mock'
import type { ErApi } from './types'

export { ApiError, asApiError } from './errors'
export type { ApiErrorCode } from './errors'
export { MOCK_SCENARIOS, mockControl } from './mock'
export type { MockScenarioId } from './mock'
export * from './types'

/**
 * 가짜 응답을 쓸지 실제 /api를 부를지 정한다.
 * - VITE_API_MODE를 mock 또는 http로 명시하면 그 값이 우선한다.
 * - 명시하지 않으면 개발 서버(npm run dev)와 테스트는 가짜 응답, 프로덕션 빌드(npm run build)는 실제 호출이다.
 *
 * 빌드 결과를 Spring이 제공하므로 빌드물이 실수로 가짜 응답으로 동작해 실제 DB를 조회하는 것처럼 보이는
 * 일이 없도록 빌드의 기본을 http로 둔다. 번들러가 빌드 시점에 값을 알 수 있도록 함수로 묶지 않고 한 식으로
 * 쓴다. 그래야 빌드에서 가짜 응답 코드와 샘플 데이터가 번들에서 빠진다.
 */
export const isMockMode =
  import.meta.env.VITE_API_MODE === 'mock' ||
  (import.meta.env.VITE_API_MODE !== 'http' && !import.meta.env.PROD)

export function createApi(mockOptions?: MockApiOptions): ErApi {
  return isMockMode ? createMockApi(mockOptions) : createHttpApi()
}
