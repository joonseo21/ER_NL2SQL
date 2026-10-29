import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, asApiError } from './api'
import type { ConditionsResponse, ErApi, QuestionResponse, QuestionStatus } from './api'
import { LIMIT_EXCEEDED, REJECTED_BEFORE_ACCEPT } from './api/errors'
import { ConditionPanel } from './components/ConditionPanel'
import { ErrorNotice } from './components/ErrorNotice'
import { FeedbackForm } from './components/FeedbackForm'
import { PendingStatus } from './components/PendingStatus'
import { QuestionForm } from './components/QuestionForm'
import { RequestIdBox } from './components/RequestIdBox'
import { RequestLookup } from './components/RequestLookup'
import { ResultView } from './components/ResultView'
import { DEFAULT_SELECTION, toRequestConditions } from './lib/conditions'
import type { ConditionSelection } from './lib/conditions'
import { QUESTION_INPUT_ID } from './lib/dom'
import { clearLastResult, loadLastResult, saveLastResult } from './lib/lastResult'
import { clearPendingQuestion, loadPendingQuestion, savePendingQuestion } from './lib/pendingQuestion'

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
  | {
      status: 'pending'
      progress: QuestionStatus | null
      /** 접수 번호. 접수 응답을 받기 전이면 null */
      requestId: string | null
      /** 새로고침 뒤 이어서 조회하는 중이면 접수 시각. 경과 시간을 여기서부터 센다. */
      resumedFrom?: number
    }
  | {
      status: 'done'
      response: QuestionResponse
      /** 결과의 접수 번호. 모르면 null */
      requestId: string | null
      /** 새로고침 전에 받아 같은 탭에 보관해 둔 결과를 다시 보여 주는 중이면 true */
      restored?: boolean
    }
  | { status: 'error'; error: ApiError }

export function QuestionScreen({ api, inviteCode, initialRemaining, onInviteInvalid }: Props) {
  const [optionsState, setOptionsState] = useState<OptionsState>({ status: 'loading' })
  const [reloadCount, setReloadCount] = useState(0)
  const [selection, setSelection] = useState<ConditionSelection>(DEFAULT_SELECTION)
  // 같은 탭에서 새로고침했으면 진행 중이던 질문을 이어서 조회한다(접수 번호와 접수 시각을 보관해 둠).
  // 진행 중인 질문이 없으면 마지막으로 받은 결과를 다시 보여 준다.
  const [resumeTarget] = useState(() => loadPendingQuestion(inviteCode))
  const [restoredResult] = useState(() => (resumeTarget === null ? loadLastResult(inviteCode) : null))
  const [question, setQuestion] = useState(resumeTarget?.question ?? restoredResult?.response.question ?? '')
  const [result, setResult] = useState<ResultState>(() => {
    if (resumeTarget !== null) {
      return {
        status: 'pending',
        progress: null,
        requestId: resumeTarget.requestId,
        resumedFrom: resumeTarget.acceptedAt,
      }
    }
    if (restoredResult !== null) {
      return {
        status: 'done',
        response: restoredResult.response,
        requestId: restoredResult.requestId,
        restored: true,
      }
    }
    return { status: 'idle' }
  })
  const [remaining, setRemaining] = useState<number | null>(
    initialRemaining ?? resumeTarget?.remainingToday ?? restoredResult?.response.remaining_today ?? null,
  )
  // 화면이 사라지면(초대 코드 변경 등) 진행 중인 조회 반복을 멈춘다.
  const lifetime = useRef<AbortController | null>(null)

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

  /** 결과를 받았다. 보관해 둔 진행 중인 질문을 지우고, 새로고침해도 다시 볼 수 있게 결과를 보관한다. */
  const handleDone = useCallback(
    (response: QuestionResponse, requestId: string | null) => {
      if (requestId !== null) clearPendingQuestion(requestId)
      saveLastResult(inviteCode, { requestId, response })
      if (response.remaining_today !== null) setRemaining(response.remaining_today)
      setResult({ status: 'done', response, requestId })
    },
    [inviteCode],
  )

  /**
   * 질문이나 결과 확인이 실패했을 때의 공통 처리.
   * - 접수된 질문이면 접수 시점의 남은 횟수를 반영한다.
   * - 접수 전 거절(한도 초과·처리 중)은 오류 본문의 remaining_today로 갱신하고, 한도 초과에 값이 없으면 0으로 둔다.
   * - 처리 실패로 끝났거나 번호를 찾지 못하면 보관해 둔 진행 중인 질문을 지운다.
   * 입력한 질문과 고른 조건은 지우지 않으므로 같은 내용을 다시 보낼 수 있다. 자동으로 다시 보내지는 않는다.
   */
  const handleFailure = useCallback(
    (e: unknown, requestId: string | null) => {
      const error = asApiError(e)
      if (error.code === 'INVITE_INVALID') {
        onInviteInvalid()
        return
      }
      if (error.requestFinished || error.code === 'QUESTION_NOT_FOUND') {
        const finishedId = error.accepted?.requestId ?? requestId
        if (finishedId !== null) clearPendingQuestion(finishedId)
      }
      if (error.accepted) {
        if (error.accepted.remainingToday !== null) setRemaining(error.accepted.remainingToday)
      } else if (REJECTED_BEFORE_ACCEPT.has(error.code)) {
        if (error.remainingToday !== null) setRemaining(error.remainingToday)
        else if (LIMIT_EXCEEDED.has(error.code)) setRemaining(0)
      }
      setResult({ status: 'error', error })
    },
    [onInviteInvalid],
  )

  const updateProgress = useCallback((progress: QuestionStatus) => {
    // 이미 결과나 오류로 바뀐 뒤에 늦게 도착한 상태는 무시한다.
    setResult((current) => (current.status === 'pending' ? { ...current, progress } : current))
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    lifetime.current = controller
    return () => controller.abort()
  }, [])

  // 새로고침 뒤 이어 보기. 질문을 다시 보내지 않고 같은 접수 번호를 조회만 한다.
  useEffect(() => {
    if (resumeTarget === null) return
    const controller = new AbortController()
    api
      .resumeQuestion(resumeTarget, inviteCode, { onStatus: updateProgress, signal: controller.signal })
      .then((response) => {
        if (!controller.signal.aborted) handleDone(response, resumeTarget.requestId)
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted) handleFailure(e, resumeTarget.requestId)
      })
    return () => controller.abort()
  }, [api, inviteCode, resumeTarget, handleDone, handleFailure, updateProgress])

  async function handleSubmit(text: string) {
    const signal = lifetime.current?.signal
    let requestId: string | null = null
    setResult({ status: 'pending', progress: null, requestId: null })
    try {
      const response = await api.askQuestion(
        { question: text, conditions: toRequestConditions(selection) },
        inviteCode,
        updateProgress,
        (accepted) => {
          // 접수되면 번호를 보여 주고, 새로고침해도 이어 볼 수 있게 같은 탭에 보관한다.
          requestId = accepted.requestId
          savePendingQuestion(inviteCode, { ...accepted, question: text })
          // 새 질문이 접수되면 이전 결과는 새로고침 뒤 다시 보여 주지 않는다.
          clearLastResult()
          setRemaining(accepted.remainingToday)
          setResult((current) =>
            current.status === 'pending' ? { ...current, requestId: accepted.requestId } : current,
          )
        },
        signal,
      )
      if (!signal?.aborted) handleDone(response, requestId)
    } catch (e) {
      if (!signal?.aborted) handleFailure(e, requestId)
    }
  }

  /** 접수 번호로 결과를 한 번 확인한다. 질문을 다시 보내지 않고 횟수에도 영향이 없다. */
  async function handleCheck(requestId: string, remainingToday: number | null) {
    const signal = lifetime.current?.signal
    clearLastResult()
    setResult({ status: 'pending', progress: 'processing', requestId })
    try {
      const response = await api.checkQuestion(requestId, remainingToday, inviteCode)
      if (!signal?.aborted) handleDone(response, requestId)
    } catch (e) {
      if (!signal?.aborted) handleFailure(e, requestId)
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

      {result.status === 'pending' && (
        <PendingStatus
          key={result.resumedFrom ?? 'new'}
          status={result.progress}
          requestId={result.requestId}
          resumed={result.resumedFrom !== undefined}
          since={result.resumedFrom}
        />
      )}
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
          {(result.restored || result.requestId !== null) && (
            <div className="result-id muted small">
              {result.restored && <p>새로고침 전에 받은 결과입니다.</p>}
              {result.requestId !== null && (
                <>
                  <RequestIdBox requestId={result.requestId} />
                  <p>
                    탭을 닫은 뒤에도 초대 코드를 다시 입력하고 아래 '접수 번호로 결과 확인'에 이 번호를 넣으면 이 결과를
                    다시 볼 수 있습니다.
                  </p>
                </>
              )}
            </div>
          )}
          <FeedbackForm
            key={result.response.log_id}
            api={api}
            inviteCode={inviteCode}
            logId={result.response.log_id}
            onInviteInvalid={onInviteInvalid}
          />
        </>
      )}

      <RequestLookup disabled={pending} onLookup={(requestId) => void handleCheck(requestId, null)} />
    </div>
  )
}
