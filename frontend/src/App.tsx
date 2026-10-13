import { useCallback, useState } from 'react'
import { createApi, isMockMode } from './api'
import type { ErApi } from './api'
import { CollectionStatus } from './components/CollectionStatus'
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

type View = 'question' | 'status'

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
  const [view, setView] = useState<View>('question')
  // 수집 현황은 처음 열 때 한 번 불러오고, 이후에는 화면만 가렸다 보여 준다.
  const [statusOpened, setStatusOpened] = useState(false)

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

  function showView(next: View) {
    setView(next)
    if (next === 'status') setStatusOpened(true)
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

      {session && (
        <nav className="tabs" aria-label="화면 전환">
          <button
            type="button"
            className={`tab${view === 'question' ? ' active' : ''}`}
            aria-current={view === 'question' ? 'page' : undefined}
            onClick={() => showView('question')}
          >
            질문
          </button>
          <button
            type="button"
            className={`tab${view === 'status' ? ' active' : ''}`}
            aria-current={view === 'status' ? 'page' : undefined}
            onClick={() => showView('status')}
          >
            수집 현황
          </button>
        </nav>
      )}

      <main>
        {session === null ? (
          <InviteGate
            api={api}
            notice={rejected ? '초대 코드가 유효하지 않습니다. 다시 입력해 주세요.' : undefined}
            onVerified={handleVerified}
          />
        ) : (
          <>
            <div hidden={view !== 'question'}>
              <QuestionScreen
                key={session.code}
                api={api}
                inviteCode={session.code}
                initialRemaining={session.remaining}
                onInviteInvalid={handleInviteInvalid}
              />
            </div>
            {statusOpened && (
              <div hidden={view !== 'status'}>
                <CollectionStatus
                  key={session.code}
                  api={api}
                  inviteCode={session.code}
                  onInviteInvalid={handleInviteInvalid}
                />
              </div>
            )}
          </>
        )}
      </main>
    </div>
  )
}
