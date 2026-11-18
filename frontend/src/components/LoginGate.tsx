import { useState } from 'react'
import type { FormEvent } from 'react'
import { asApiError, isMockMode, MOCK_LOGIN } from '../api'
import type { ErApi } from '../api'

interface Props {
  api: ErApi
  /** 서버가 보낸 invite_code가 확인되어 입장하는 경우. 아이디는 화면 상단 표시용이다. */
  onLoggedIn: (username: string, inviteCode: string, remainingToday: number) => void
  /** 서버가 로그인을 꺼 둔 경우(LOGIN_DISABLED). 서버 message를 안내로 넘기고 초대 코드 입장을 보여 준다. */
  onLoginDisabled: (message: string) => void
  /** "초대 코드로 입장" 전환 */
  onUseInviteCode: () => void
  /** 이전에 연결된 초대 코드가 거절되어 돌아온 경우 보여 줄 안내 */
  notice?: string
}

/**
 * 아이디·비밀번호 로그인 첫 화면이다. 계정은 운영자가 발급하며 가입 기능은 없다.
 * 비밀번호는 이 컴포넌트의 입력 상태로만 존재하고, 성공·실패 어느 쪽이든 응답을 받으면 입력란을 비운다.
 * 어디에도(저장소·로그·URL) 남기지 않는다.
 */
export function LoginGate({ api, onLoggedIn, onLoginDisabled, onUseInviteCode, notice }: Props) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<{ code: string; message: string } | null>(null)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const name = username.trim()
    if (name === '' || password === '' || pending) return

    setPending(true)
    setError(null)
    try {
      const result = await api.login(name, password)
      setPassword('')
      onLoggedIn(name, result.invite_code, result.remaining_today)
      return
    } catch (e) {
      const apiError = asApiError(e)
      setPassword('')
      if (apiError.code === 'LOGIN_DISABLED') {
        onLoginDisabled(apiError.message)
        return
      }
      setError({ code: apiError.code, message: apiError.message })
    }
    setPending(false)
  }

  const message = error?.message ?? notice

  return (
    <section className="card" aria-labelledby="login-title">
      <h2 id="login-title">로그인</h2>
      <p className="muted">아이디·비밀번호는 운영자가 발급합니다.</p>
      {isMockMode && (
        <p className="muted small" data-testid="mock-login-hint">
          가짜 응답 모드: 아이디 {MOCK_LOGIN.username}, 비밀번호 {MOCK_LOGIN.password}로 로그인됩니다.
          locked는 시도 횟수 초과, disabled는 로그인 사용 불가를 흉내 냅니다.
        </p>
      )}
      <form onSubmit={handleSubmit} className="stack">
        <label className="field">
          <span>아이디</span>
          <input
            type="text"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
            disabled={pending}
          />
        </label>
        <label className="field">
          <span>비밀번호</span>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            disabled={pending}
          />
        </label>
        {message && (
          <p role="alert" className="error-text">
            {message}
          </p>
        )}
        {error?.code === 'VALIDATION_FAILED' && (
          <p className="muted small">아이디는 소문자·숫자·_·- 3~32자, 비밀번호는 1~200자입니다.</p>
        )}
        <button type="submit" disabled={pending || username.trim() === '' || password === ''}>
          {pending ? '로그인 중…' : '로그인'}
        </button>
      </form>
      <p className="login-alt">
        <button type="button" className="link" onClick={onUseInviteCode} disabled={pending}>
          초대 코드로 입장
        </button>
      </p>
    </section>
  )
}
