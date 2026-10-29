import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, beforeEach } from 'vitest'

beforeEach(() => {
  // 초대 코드가 sessionStorage에 남아 다음 테스트의 시작 화면을 바꾸지 않게 한다.
  window.sessionStorage.clear()
  // 가짜 서버가 접수 기록을 localStorage에 두므로 테스트마다 비운다.
  window.localStorage.clear()
})

// 테스트마다 렌더링한 화면을 정리해 다음 테스트에 남지 않게 한다.
afterEach(() => {
  cleanup()
})
