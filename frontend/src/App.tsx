import { useCallback, useState } from 'react'
import { createApi, isMockMode } from './api'
import type { ErApi } from './api'
import { InviteGate } from './components/InviteGate'
import { MockBar } from './components/MockBar'
import { clearInviteCode, loadInviteCode, saveInviteCode } from './lib/session'
import { QuestionScreen } from './QuestionScreen'
import './App.css'

const defaultApi = createApi()

interface Session {
  code: string
  /** 초대 코드를 막 확인한 경우에만 알 수 있다. 새로고침으로 복원한 경우는 null */
  remaining: number | null
}

interface Props {
  /** 테스트에서 다른 API를 넣기 위한 자리 */
  api?: ErApi
}

export default function App({ api = defaultApi }: Props) {
  const [session, setSession] = useState<Session | null>(() => {
    const code = loadInviteCode()
    return code === null ? null : { code, remaining: null }
  })
  const [rejected, setRejected] = useState(false)

  const handleVerified = useCallback((code: string, remaining: number) => {
    saveInviteCode(code)
    setRejected(false)
    setSession({ code, remaining })
  }, [])

  const handleInviteInvalid = useCallback(() => {
    clearInviteCode()
    setRejected(true)
    setSession(null)
  }, [])

  function handleLogout() {
    clearInviteCode()
    setRejected(false)
    setSession(null)
  }

  return (
    <div className="page">
      <header className="header">
        <div>
          <h1>ER Analytics</h1>
          <p className="muted">이터널 리턴 경기 전적 DB에 한국어로 질문하세요.</p>
        </div>
        {session && (
          <button type="button" className="link" onClick={handleLogout}>
            초대 코드 바꾸기
          </button>
        )}
      </header>

      {isMockMode ? (
        <p className="banner banner-mock" role="note">
          가짜 응답으로 동작 중입니다. 실제 전적 DB를 조회하지 않으며 표시되는 결과는 예시입니다.
        </p>
      ) : (
        <p className="banner" role="note">
          수집한 실제 경기 전적 DB를 조회합니다. 결과에는 실행된 SQL과 표본 수가 함께 표시됩니다.
        </p>
      )}
      {isMockMode && <MockBar />}

      <main>
        {session === null ? (
          <InviteGate
            api={api}
            notice={rejected ? '초대 코드가 유효하지 않습니다. 다시 입력해 주세요.' : undefined}
            onVerified={handleVerified}
          />
        ) : (
          <QuestionScreen
            key={session.code}
            api={api}
            inviteCode={session.code}
            initialRemaining={session.remaining}
            onInviteInvalid={handleInviteInvalid}
          />
        )}
      </main>
    </div>
  )
}
