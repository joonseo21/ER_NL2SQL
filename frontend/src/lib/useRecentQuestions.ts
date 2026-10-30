import { useCallback, useEffect, useRef, useState } from 'react'
import {
  clearRecentQuestions, forgetRecentQuestion, loadRecentQuestions, RECENT_QUESTIONS_KEY,
  recentQuestionsFor, rememberRecentQuestion, setRecentQuestionsEnabled,
} from './recentQuestions'
import type { RecentQuestion } from './recentQuestions'

export function useRecentQuestions(inviteCode: string) {
  const [state, setState] = useState(() => loadRecentQuestions())
  const enabled = useRef(state.enabled)
  const unsavedOptOut = useRef(false)
  const update = useCallback((next: ReturnType<typeof loadRecentQuestions>) => {
    enabled.current = next.enabled
    setState(next)
  }, [])

  useEffect(() => {
    const refresh = () => {
      if (!unsavedOptOut.current) update(loadRecentQuestions())
    }
    const onStorage = (event: StorageEvent) => {
      if (event.key === null || event.key === RECENT_QUESTIONS_KEY) refresh()
    }
    // 열린 화면도 만료·다른 탭의 삭제/끄기를 반영한다. 서버 조회는 발생하지 않는다.
    const timer = window.setInterval(refresh, 60_000)
    window.addEventListener('storage', onStorage)
    window.addEventListener('focus', refresh)
    document.addEventListener('visibilitychange', refresh)
    return () => {
      window.clearInterval(timer)
      window.removeEventListener('storage', onStorage)
      window.removeEventListener('focus', refresh)
      document.removeEventListener('visibilitychange', refresh)
    }
  }, [update])

  const forget = useCallback((requestId: string) => {
    const next = forgetRecentQuestion(inviteCode, requestId)
    if (!unsavedOptOut.current) update(next)
  }, [inviteCode, update])

  return {
    entries: recentQuestionsFor(state, inviteCode),
    enabled: state.enabled,
    available: state.available,
    remember: (question: RecentQuestion) => {
      // 저장 실패 뒤에도 현재 탭에서 끈 선택은 접수 콜백에 즉시 적용한다.
      if (enabled.current) update(rememberRecentQuestion(inviteCode, question))
    },
    forget,
    clear: () => {
      const next = clearRecentQuestions()
      update(unsavedOptOut.current ? { ...next, enabled: false, available: false } : next)
    },
    setEnabled: (value: boolean) => {
      const next = setRecentQuestionsEnabled(value)
      unsavedOptOut.current = !value && !next.available
      update(next)
    },
  }
}
