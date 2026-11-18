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

// 로그인한 아이디는 화면 상단에 보여 주기 위한 표시용이다. 비밀번호는 어디에도 저장하지 않는다.
const USERNAME_KEY = 'er-analytics.login-username'

export function loadUsername(): string | null {
  try {
    return window.sessionStorage.getItem(USERNAME_KEY)
  } catch {
    return null
  }
}

export function saveUsername(username: string): void {
  try {
    window.sessionStorage.setItem(USERNAME_KEY, username)
  } catch {
    // 저장하지 못하면 새로고침 뒤 아이디 표시만 사라진다.
  }
}

export function clearUsername(): void {
  try {
    window.sessionStorage.removeItem(USERNAME_KEY)
  } catch {
    // 지우지 못해도 계속 진행한다.
  }
}
