import { useState } from 'react'
import type { FormEvent } from 'react'
import { asApiError } from '../api'
import type { ErApi } from '../api'

interface Props {
  api: ErApi
  /** 이전에 입력한 코드가 거절되어 돌아온 경우 보여 줄 안내 */
  notice?: string
  onVerified: (code: string, remainingToday: number) => void
  /** 로그인 화면으로 돌아가는 전환. 로그인을 쓸 수 없을 때(LOGIN_DISABLED)는 넘기지 않아 버튼을 숨긴다. */
  onBackToLogin?: () => void
}

/** 초대 코드를 입력받는 화면이다. 로그인 화면의 "초대 코드로 입장"으로 들어오며, 로그인을 쓸 수 없을 때의 대체 입구다. */
export function InviteGate({ api, notice, onVerified, onBackToLogin }: Props) {
  const [code, setCode] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const trimmed = code.trim()
    if (trimmed === '' || pending) return

    setPending(true)
    setError(null)
    try {
      const result = await api.verifyInvite(trimmed)
      if (result.valid) {
        onVerified(trimmed, result.remaining_today)
        return
      }
      setError('초대 코드를 확인해 주세요.')
    } catch (e) {
      setError(asApiError(e).message)
    }
    setPending(false)
  }

  const message = error ?? notice

  return (
    <section className="card" aria-labelledby="invite-title">
      <h2 id="invite-title">초대 코드 입력</h2>
      <p className="muted">지인에게 받은 초대 코드를 입력하면 질문할 수 있습니다.</p>
      <form onSubmit={handleSubmit} className="stack">
        <label className="field">
          <span>초대 코드</span>
          <input
            type="text"
            value={code}
            onChange={(e) => setCode(e.target.value)}
            autoComplete="off"
            spellCheck={false}
            disabled={pending}
          />
        </label>
        {message && (
          <p role="alert" className="error-text">
            {message}
          </p>
        )}
        <button type="submit" disabled={pending || code.trim() === ''}>
          {pending ? '확인 중…' : '확인'}
        </button>
      </form>
      {onBackToLogin && (
        <p className="login-alt">
          <button type="button" className="link" onClick={onBackToLogin} disabled={pending}>
            아이디·비밀번호로 로그인
          </button>
        </p>
      )}
    </section>
  )
}
