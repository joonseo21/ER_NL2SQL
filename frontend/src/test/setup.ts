import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, beforeEach } from 'vitest'

beforeEach(() => {
  // 초대 코드가 sessionStorage에 남아 다음 테스트의 시작 화면을 바꾸지 않게 한다.
  window.sessionStorage.clear()
})

// 테스트마다 렌더링한 화면을 정리해 다음 테스트에 남지 않게 한다.
afterEach(() => {
  cleanup()
})
