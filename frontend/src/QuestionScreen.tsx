import { useEffect, useState } from 'react'
import { ApiError, asApiError } from './api'
import type { ConditionsResponse, ErApi, QuestionResponse } from './api'
import { ConditionPanel } from './components/ConditionPanel'
import { ErrorNotice } from './components/ErrorNotice'
import { PendingStatus } from './components/PendingStatus'
import { QuestionForm } from './components/QuestionForm'
import { ResultView } from './components/ResultView'
import { DEFAULT_SELECTION, toRequestConditions } from './lib/conditions'
import type { ConditionSelection } from './lib/conditions'
import { QUESTION_INPUT_ID } from './lib/dom'

interface Props {
  api: ErApi
  inviteCode: string
  /** 알 수 없으면 null. 첫 답변을 받으면 채워진다. */
  initialRemaining: number | null
  /** 서버가 초대 코드를 거절하면 첫 화면으로 돌아가기 위해 호출한다. 같은 함수를 계속 넘겨야 한다. */
  onInviteInvalid: () => void
}

type OptionsState =
  | { status: 'loading' }
  | { status: 'ready'; options: ConditionsResponse }
  | { status: 'error'; error: ApiError }

type ResultState =
  | { status: 'idle' }
  | { status: 'pending' }
  | { status: 'done'; response: QuestionResponse }
  | { status: 'error'; error: ApiError }

export function QuestionScreen({ api, inviteCode, initialRemaining, onInviteInvalid }: Props) {
  const [optionsState, setOptionsState] = useState<OptionsState>({ status: 'loading' })
  const [reloadCount, setReloadCount] = useState(0)
  const [selection, setSelection] = useState<ConditionSelection>(DEFAULT_SELECTION)
  const [question, setQuestion] = useState('')
  const [result, setResult] = useState<ResultState>({ status: 'idle' })
  const [remaining, setRemaining] = useState<number | null>(initialRemaining)

  useEffect(() => {
    let cancelled = false
    api
      .getConditions(inviteCode)
      .then((options) => {
        if (!cancelled) setOptionsState({ status: 'ready', options })
      })
      .catch((e: unknown) => {
        if (cancelled) return
        const error = asApiError(e)
        if (error.code === 'INVITE_INVALID') onInviteInvalid()
        else setOptionsState({ status: 'error', error })
      })
    return () => {
      cancelled = true
    }
  }, [api, inviteCode, onInviteInvalid, reloadCount])

  function reloadConditions() {
    setOptionsState({ status: 'loading' })
    setReloadCount((n) => n + 1)
  }

  async function handleSubmit(text: string) {
    setResult({ status: 'pending' })
    try {
      const response = await api.askQuestion(
        { question: text, conditions: toRequestConditions(selection) },
        inviteCode,
      )
      setRemaining(response.remaining_today)
      setResult({ status: 'done', response })
    } catch (e) {
      const error = asApiError(e)
      if (error.code === 'INVITE_INVALID') onInviteInvalid()
      else setResult({ status: 'error', error })
    }
  }

  const pending = result.status === 'pending'
  const options = optionsState.status === 'ready' ? optionsState.options : null

  /** 안내의 예시 질문이나 되묻기의 후보를 입력란에 채우고, 바로 수정·전송할 수 있게 포커스를 옮긴다. */
  function handleUseQuestion(text: string) {
    setQuestion(text)
    const input = document.getElementById(QUESTION_INPUT_ID)
    input?.focus()
    input?.scrollIntoView?.({ block: 'center' })
  }

  return (
    <div className="stack">
      {remaining !== null && <p className="muted small right">오늘 남은 질문 {remaining}회</p>}

      {optionsState.status === 'loading' && (
        <p className="muted" role="status">
          조건 목록을 불러오는 중…
        </p>
      )}
      {optionsState.status === 'error' && (
        <ErrorNotice error={optionsState.error} onRetry={reloadConditions} />
      )}
      {optionsState.status === 'ready' && (
        <ConditionPanel
          options={optionsState.options}
          value={selection}
          disabled={pending}
          onChange={setSelection}
        />
      )}

      <QuestionForm value={question} pending={pending} onChange={setQuestion} onSubmit={handleSubmit} />

      {result.status === 'pending' && <PendingStatus />}
      {result.status === 'error' && <ErrorNotice error={result.error} />}
      {result.status === 'done' && (
        <ResultView response={result.response} options={options} onUseQuestion={handleUseQuestion} />
      )}
    </div>
  )
}
