const KEY = 'er-analytics.invite-code'

// 저장소는 사생활 보호 모드 등에서 막히거나 예외를 던질 수 있으므로 모든 접근을 감싼다.
// 저장은 새로고침 편의를 위한 것이며, 실패해도 화면은 정상 동작해야 한다.

export function loadInviteCode(): string | null {
  try {
    return window.sessionStorage.getItem(KEY)
  } catch {
    return null
  }
}

export function saveInviteCode(code: string): void {
  try {
    window.sessionStorage.setItem(KEY, code)
  } catch {
    // 저장하지 못해도 계속 진행한다.
  }
}

export function clearInviteCode(): void {
  try {
    window.sessionStorage.removeItem(KEY)
  } catch {
    // 지우지 못해도 계속 진행한다.
  }
}
