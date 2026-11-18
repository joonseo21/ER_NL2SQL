import { useCallback, useEffect, useRef, useState } from 'react'
import { createApi, isMockMode } from './api'
import type { ErApi } from './api'
import { CollectionStatus } from './components/CollectionStatus'
import { InviteGate } from './components/InviteGate'
import { LoginGate } from './components/LoginGate'
import { MockBar } from './components/MockBar'
import { SERVICE_GUIDE_ID, ServiceGuide } from './components/ServiceGuide'
import { clearLastResult } from './lib/lastResult'
import { clearPendingQuestion } from './lib/pendingQuestion'
import { loadRecentQuestions } from './lib/recentQuestions'
import {
  clearInviteCode,
  clearUsername,
  loadInviteCode,
  loadUsername,
  saveInviteCode,
  saveUsername,
} from './lib/session'
import { QuestionScreen } from './QuestionScreen'
import './App.css'

const defaultApi = createApi()

interface Session {
  code: string
  /** 초대 코드를 막 확인한 경우에만 알 수 있다. 새로고침으로 복원한 경우는 null */
  remaining: number | null
  /** 아이디·비밀번호로 로그인해 들어온 경우의 아이디(표시용). 초대 코드로 직접 입장했으면 null */
  username: string | null
}

/** 첫 화면에서 보여 줄 입구. 로그인이 기본이고, 초대 코드 입장은 전환하거나 로그인을 쓸 수 없을 때 보인다. */
type Entry = 'login' | 'invite'

const INVITE_REJECTED_NOTICE = '초대 코드가 유효하지 않습니다. 다시 입력해 주세요.'
const LOGIN_INVITE_REJECTED_NOTICE = '연결된 초대 코드가 유효하지 않습니다. 운영자에게 문의해 주세요.'

type View = 'question' | 'status'

interface Props {
  /** 테스트에서 다른 API를 넣기 위한 자리 */
  api?: ErApi
}

export default function App({ api = defaultApi }: Props) {
  // 초대 코드를 입력하기 전에도 앱을 열면 모든 코드의 만료 기록을 정리한다.
  useEffect(() => { loadRecentQuestions() }, [])
  const [session, setSession] = useState<Session | null>(() => {
    const code = loadInviteCode()
    return code === null ? null : { code, remaining: null, username: loadUsername() }
  })
  const [entry, setEntry] = useState<Entry>('login')
  // 서버가 로그인을 꺼 둔 경우(LOGIN_DISABLED)에는 로그인으로 돌아가는 전환을 숨긴다.
  const [loginDisabled, setLoginDisabled] = useState(false)
  // 첫 화면에 보여 줄 안내(거절된 코드, 로그인을 쓸 수 없다는 서버 안내). 입구 화면이 바뀌어도 한 번만 쓴다.
  const [entryNotice, setEntryNotice] = useState<string | undefined>(undefined)
  // 콜백이 오래 머물러도 지금 어떤 방식으로 들어왔는지 알 수 있게 둔다.
  const usernameRef = useRef<string | null>(session?.username ?? null)
  const [view, setView] = useState<View>('question')
  // 수집 현황은 처음 열 때 한 번 불러오고, 이후에는 화면만 가렸다 보여 준다.
  const [statusOpened, setStatusOpened] = useState(false)
  // 서비스 안내는 화면 위에 펼쳐지는 패널이라 열고 닫아도 아래 화면의 입력과 진행 상태가 그대로 남는다.
  const [guideOpen, setGuideOpen] = useState(false)
  const guideButton = useRef<HTMLButtonElement>(null)

  const closeGuide = useCallback(() => {
    setGuideOpen(false)
    // 닫으면 안내를 연 버튼으로 초점을 되돌린다.
    guideButton.current?.focus()
  }, [])

  /**
   * 초대 코드 확인 성공과 로그인 성공이 같은 경로다. 로그인이면 서버가 돌려준 초대 코드와 아이디가 넘어온다.
   * 비밀번호는 여기까지 오지 않는다.
   */
  const handleVerified = useCallback((code: string, remaining: number, username: string | null = null) => {
    saveInviteCode(code)
    if (username === null) clearUsername()
    else saveUsername(username)
    usernameRef.current = username
    setEntryNotice(undefined)
    setSession({ code, remaining, username })
  }, [])

  const handleLoginDisabled = useCallback((message: string) => {
    setLoginDisabled(true)
    setEntry('invite')
    setEntryNotice(message)
  }, [])

  const handleInviteInvalid = useCallback(() => {
    const viaLogin = usernameRef.current !== null
    clearInviteCode()
    clearUsername()
    usernameRef.current = null
    setEntry(viaLogin && !loginDisabled ? 'login' : 'invite')
    setEntryNotice(viaLogin ? LOGIN_INVITE_REJECTED_NOTICE : INVITE_REJECTED_NOTICE)
    setSession(null)
  }, [loginDisabled])

  /** 로그아웃과 "초대 코드 바꾸기"는 같은 동작이다. 들어온 방식과 같은 입구 화면으로 돌아간다. */
  function handleLogout() {
    const viaLogin = session?.username != null
    clearInviteCode()
    clearUsername()
    usernameRef.current = null
    // 다른 계정·초대 코드로 들어오면 이전 코드의 진행 중인 질문과 마지막 결과를 보여 주지 않는다.
    clearPendingQuestion()
    clearLastResult()
    setEntry(viaLogin && !loginDisabled ? 'login' : 'invite')
    setEntryNotice(undefined)
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
        <div className="header-actions">
          <button
            ref={guideButton}
            type="button"
            className="link"
            aria-expanded={guideOpen}
            aria-controls={SERVICE_GUIDE_ID}
            onClick={() => (guideOpen ? closeGuide() : setGuideOpen(true))}
          >
            서비스 안내
          </button>
          {session && session.username !== null && (
            <span className="login-user">
              로그인: <strong>{session.username}</strong>
            </span>
          )}
          {session && (
            <button type="button" className="link" onClick={handleLogout}>
              {session.username !== null ? '로그아웃' : '초대 코드 바꾸기'}
            </button>
          )}
        </div>
      </header>

      {guideOpen && <ServiceGuide onClose={closeGuide} />}

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
          entry === 'login' ? (
            <LoginGate
              api={api}
              notice={entryNotice}
              onLoggedIn={(username, code, remaining) => handleVerified(code, remaining, username)}
              onLoginDisabled={handleLoginDisabled}
              onUseInviteCode={() => {
                setEntryNotice(undefined)
                setEntry('invite')
              }}
            />
          ) : (
            <InviteGate
              api={api}
              notice={entryNotice}
              onVerified={(code, remaining) => handleVerified(code, remaining)}
              onBackToLogin={
                loginDisabled
                  ? undefined
                  : () => {
                      setEntryNotice(undefined)
                      setEntry('login')
                    }
              }
            />
          )
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
