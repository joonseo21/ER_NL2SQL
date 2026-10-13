import { useEffect, useState } from 'react'
import { ApiError, asApiError } from './api'
import type { ConditionsResponse, ErApi, QuestionResponse, QuestionStatus } from './api'
import { ConditionPanel } from './components/ConditionPanel'
import { ErrorNotice } from './components/ErrorNotice'
import { FeedbackForm } from './components/FeedbackForm'
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
  | { status: 'pending'; progress: QuestionStatus | null }
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

  /** 질문이나 결과 확인이 실패했을 때의 공통 처리. 접수된 질문이면 이미 소비된 횟수를 반영한다. */
  function handleFailure(e: unknown) {
    const error = asApiError(e)
    if (error.code === 'INVITE_INVALID') {
      onInviteInvalid()
      return
    }
    if (error.accepted) setRemaining(error.accepted.remainingToday)
    setResult({ status: 'error', error })
  }

  async function handleSubmit(text: string) {
    setResult({ status: 'pending', progress: null })
    try {
      const response = await api.askQuestion(
        { question: text, conditions: toRequestConditions(selection) },
        inviteCode,
        (progress) =>
          // 이미 결과나 오류로 바뀐 뒤에 늦게 도착한 상태는 무시한다.
          setResult((current) => (current.status === 'pending' ? { status: 'pending', progress } : current)),
      )
      setRemaining(response.remaining_today)
      setResult({ status: 'done', response })
    } catch (e) {
      handleFailure(e)
    }
  }

  /** 자동 조회를 멈춘 질문의 결과를 다시 확인한다. 질문을 다시 보내지 않는다. */
  async function handleCheck(requestId: string, remainingToday: number) {
    setResult({ status: 'pending', progress: 'processing' })
    try {
      const response = await api.checkQuestion(requestId, remainingToday, inviteCode)
      setRemaining(response.remaining_today)
      setResult({ status: 'done', response })
    } catch (e) {
      handleFailure(e)
    }
  }

  const pending = result.status === 'pending'
  const options = optionsState.status === 'ready' ? optionsState.options : null
  // 처리가 오래 걸려 자동 조회를 멈춘 경우에만 같은 접수 번호로 결과를 다시 확인할 수 있다.
  const checkTarget =
    result.status === 'error' && result.error.code === 'PROCESSING_TIMEOUT' ? result.error.accepted : null

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

      {result.status === 'pending' && <PendingStatus status={result.progress} />}
      {result.status === 'error' && (
        <ErrorNotice
          error={result.error}
          onRetry={checkTarget ? () => void handleCheck(checkTarget.requestId, checkTarget.remainingToday) : undefined}
          retryLabel="결과 다시 확인"
        />
      )}
      {result.status === 'done' && (
        <>
          <ResultView response={result.response} options={options} onUseQuestion={handleUseQuestion} />
          <FeedbackForm
            key={result.response.log_id}
            api={api}
            inviteCode={inviteCode}
            logId={result.response.log_id}
            onInviteInvalid={onInviteInvalid}
          />
        </>
      )}
    </div>
  )
}
