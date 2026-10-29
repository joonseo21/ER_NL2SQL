import { ApiError, failureMessage, httpErrorMessage, toApiErrorCode } from './errors'
import type { AcceptedInfo } from './errors'
import type {
  AcceptedQuestion,
  CollectionSummaryResponse,
  ConditionsResponse,
  ErApi,
  FeedbackRequest,
  FeedbackResponse,
  InviteVerifyResponse,
  PollOptions,
  QuestionAcceptedResponse,
  QuestionRequest,
  QuestionResponse,
  QuestionStatus,
  QuestionStatusResponse,
} from './types'

// 값은 웹 API 계약의 "상태 조회와 비동기 실패"(2026-10-13 확정)를 따른다.

/** 일반 요청(초대 확인·조건 목록·접수·후기·수집 현황)이 응답하기를 기다리는 시간 */
const REQUEST_TIMEOUT_MS = 30_000
/** 상태 조회 요청 하나가 응답하기를 기다리는 시간. 계약의 권장값이다. */
const POLL_REQUEST_TIMEOUT_MS = 10_000
/** 접수 응답 후 첫 조회까지, 그리고 직전 조회가 끝난 뒤 다음 조회까지의 간격. 조회가 겹치지 않는다. */
const POLL_INTERVAL_MS = 2_000
/** 접수 응답 시점부터 이 시간이 지나면 자동 조회를 멈춘다(7분). 서버의 처리 상한이 아니다. */
const QUESTION_TIMEOUT_MS = 420_000
/**
 * 상태 조회 한 번이 네트워크 문제로 실패해도 질문은 서버에서 계속 처리된다. 같은 질문을 다시 보내면 횟수가
 * 또 소비되므로, 연속으로 이 횟수만큼 실패할 때까지는 조회만 다시 시도한다.
 */
const MAX_POLL_FAILURES = 3

const PROCESSING_TIMEOUT_MESSAGE = '처리가 오래 걸리고 있습니다. 나중에 결과를 다시 확인해 주세요.'

export interface HttpApiOptions {
  /** 화면과 API는 같은 주소에서 제공되므로 기본은 상대 경로 /api다. */
  baseUrl?: string
  /** 테스트에서 가짜 fetch를 넣기 위한 자리 */
  fetchFn?: typeof fetch
  pollIntervalMs?: number
  questionTimeoutMs?: number
  /** 테스트에서 실제로 기다리지 않기 위한 자리 */
  sleep?: (ms: number) => Promise<void>
  now?: () => number
}

interface RequestOptions {
  method: 'GET' | 'POST'
  path: string
  body?: unknown
  /** 초대 코드는 URL이 아니라 헤더로만 보낸다. */
  inviteCode?: string
  timeoutMs?: number
}

export function createHttpApi(options: HttpApiOptions = {}): ErApi {
  const baseUrl = options.baseUrl ?? '/api'
  const fetchFn = options.fetchFn ?? ((...args) => fetch(...args))
  const pollIntervalMs = options.pollIntervalMs ?? POLL_INTERVAL_MS
  const questionTimeoutMs = options.questionTimeoutMs ?? QUESTION_TIMEOUT_MS
  const sleep = options.sleep ?? ((ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)))
  const now = options.now ?? (() => Date.now())

  async function request<T>(req: RequestOptions): Promise<T> {
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (req.body !== undefined) headers['Content-Type'] = 'application/json'
    if (req.inviteCode !== undefined) headers['X-Invite-Code'] = req.inviteCode

    let response: Response
    try {
      response = await fetchFn(`${baseUrl}${req.path}`, {
        method: req.method,
        headers,
        body: req.body === undefined ? undefined : JSON.stringify(req.body),
        signal: AbortSignal.timeout(req.timeoutMs ?? REQUEST_TIMEOUT_MS),
      })
    } catch (error) {
      if (error instanceof DOMException && error.name === 'TimeoutError') {
        throw new ApiError('TIMEOUT', '응답을 기다리는 시간이 지났습니다. 잠시 뒤 다시 시도해 주세요.')
      }
      throw new ApiError('NETWORK_ERROR', '서버에 연결하지 못했습니다. 네트워크를 확인해 주세요.')
    }

    if (!response.ok) throw await toApiError(response)

    try {
      return (await response.json()) as T
    } catch {
      throw new ApiError('UNKNOWN', '서버 응답을 해석하지 못했습니다.', response.status)
    }
  }

  const fetchStatus = (requestId: string, inviteCode: string) =>
    request<QuestionStatusResponse>({
      method: 'GET',
      path: `/questions/${encodeURIComponent(requestId)}`,
      inviteCode,
      timeoutMs: POLL_REQUEST_TIMEOUT_MS,
    })

  /**
   * 조회 결과를 해석한다. 완료면 응답을, 실패면 ApiError를 던지고, 아직 처리 중이면 null을 돌려준다.
   * 남은 횟수는 접수 응답에 있고 조회 응답에는 없어 접수 정보에서 가져온다.
   */
  function interpret(state: QuestionStatusResponse, info: AcceptedInfo): QuestionResponse | null {
    if (state.status === 'completed') {
      if (state.result === null) {
        throw new ApiError('UNKNOWN', '처리는 끝났지만 결과를 받지 못했습니다.', null, info, {
          requestFinished: true,
        })
      }
      return { ...state.result, remaining_today: info.remainingToday } as QuestionResponse
    }
    if (state.status === 'failed') {
      const code = toApiErrorCode(state.error_code)
      throw new ApiError(code, failureMessage(code, state.message), null, info, { requestFinished: true })
    }
    return null // accepted·processing, 또는 모르는 상태는 계속 기다린다.
  }

  /** 질문을 이미 접수한 뒤의 오류에는 접수 정보를 붙여, 화면이 횟수 소비와 재확인을 안내할 수 있게 한다. */
  function withAccepted(error: unknown, info: AcceptedInfo): unknown {
    return error instanceof ApiError && error.accepted === null
      ? new ApiError(error.code, error.message, error.status, info, {
          remainingToday: error.remainingToday,
          requestFinished: error.requestFinished,
        })
      : error
  }

  /**
   * 접수한 질문의 결과가 나올 때까지 접수 번호로 조회한다. 조회는 겹치지 않고 직전 조회가 끝난 뒤
   * pollIntervalMs를 기다린다. 접수 시각부터 questionTimeoutMs가 지나면 PROCESSING_TIMEOUT을 던진다.
   */
  async function pollUntilDone(
    accepted: AcceptedQuestion,
    inviteCode: string,
    initialStatus: QuestionStatus | null,
    firstWaitMs: number,
    options: PollOptions,
  ): Promise<QuestionResponse> {
    const info: AcceptedInfo = { requestId: accepted.requestId, remainingToday: accepted.remainingToday }
    let lastStatus = initialStatus
    let failures = 0
    let waitMs = firstWaitMs

    for (;;) {
      await sleep(waitMs)
      waitMs = pollIntervalMs
      if (options.signal?.aborted) {
        // 화면이 조회를 멈췄다. 호출한 쪽은 이 오류를 보여 주지 않는다.
        throw new ApiError('UNKNOWN', '결과 조회를 멈췄습니다.', null, info)
      }

      let state: QuestionStatusResponse
      try {
        state = await fetchStatus(info.requestId, inviteCode)
        failures = 0
      } catch (error) {
        const transient =
          error instanceof ApiError && (error.code === 'NETWORK_ERROR' || error.code === 'TIMEOUT')
        failures += 1
        if (transient && failures < MAX_POLL_FAILURES) continue
        throw withAccepted(error, info)
      }

      if (state.status !== lastStatus) {
        lastStatus = state.status
        options.onStatus?.(state.status)
      }

      const response = interpret(state, info)
      if (response !== null) return response

      if (now() - accepted.acceptedAt >= questionTimeoutMs) {
        throw new ApiError('PROCESSING_TIMEOUT', PROCESSING_TIMEOUT_MESSAGE, null, info)
      }
    }
  }

  async function askQuestion(
    body: QuestionRequest,
    inviteCode: string,
    onStatus?: (status: QuestionStatus) => void,
    onAccepted?: (accepted: AcceptedQuestion) => void,
    signal?: AbortSignal,
  ): Promise<QuestionResponse> {
    // 1) 접수. 여기서 초대 코드·한도·처리 중·요청 형식 오류가 나면 바로 던진다.
    const response = await request<QuestionAcceptedResponse>({
      method: 'POST',
      path: '/questions',
      body,
      inviteCode,
    })
    const accepted: AcceptedQuestion = {
      requestId: response.request_id,
      remainingToday: response.remaining_today,
      acceptedAt: now(),
    }
    onStatus?.(response.status)
    onAccepted?.(accepted)

    // 2) 결과가 나올 때까지 접수 번호로 조회한다. 첫 조회는 접수 응답 2초 뒤다.
    return pollUntilDone(accepted, inviteCode, response.status, pollIntervalMs, { onStatus, signal })
  }

  async function resumeQuestion(
    accepted: AcceptedQuestion,
    inviteCode: string,
    options: PollOptions = {},
  ): Promise<QuestionResponse> {
    const info: AcceptedInfo = { requestId: accepted.requestId, remainingToday: accepted.remainingToday }
    // 대기 시간은 새로고침한 시각이 아니라 접수 시각부터 센다.
    if (now() - accepted.acceptedAt >= questionTimeoutMs) {
      throw new ApiError('PROCESSING_TIMEOUT', PROCESSING_TIMEOUT_MESSAGE, null, info)
    }
    // 접수 2초 뒤부터 조회한다는 규칙을 지키고, 이미 지났으면 바로 조회한다.
    const firstWaitMs = Math.max(0, accepted.acceptedAt + pollIntervalMs - now())
    return pollUntilDone(accepted, inviteCode, null, firstWaitMs, options)
  }

  async function checkQuestion(
    requestId: string,
    remainingToday: number | null,
    inviteCode: string,
  ): Promise<QuestionResponse> {
    const info: AcceptedInfo = { requestId, remainingToday }
    let state: QuestionStatusResponse
    try {
      state = await fetchStatus(requestId, inviteCode)
    } catch (error) {
      // 번호를 찾지 못한 404(없는 번호이거나 다른 초대 코드의 번호)는 이 코드의 접수라는 보장이 없어
      // 접수 정보를 붙이지 않는다. 화면은 횟수 포함 안내나 접수 번호를 보여 주지 않는다.
      if (error instanceof ApiError && error.code === 'QUESTION_NOT_FOUND') throw error
      throw withAccepted(error, info)
    }
    const response = interpret(state, info)
    if (response !== null) return response
    throw new ApiError('PROCESSING_TIMEOUT', PROCESSING_TIMEOUT_MESSAGE, null, info)
  }

  return {
    verifyInvite: (code) =>
      request<InviteVerifyResponse>({ method: 'POST', path: '/invite/verify', body: { code } }),

    getConditions: (inviteCode) =>
      request<ConditionsResponse>({ method: 'GET', path: '/conditions', inviteCode }),

    askQuestion,

    resumeQuestion,

    checkQuestion,

    submitFeedback: (logId: string, feedback: FeedbackRequest, inviteCode: string) =>
      request<FeedbackResponse>({
        method: 'POST',
        path: `/questions/${encodeURIComponent(logId)}/feedback`,
        body: feedback,
        inviteCode,
      }),

    getCollectionSummary: (inviteCode) =>
      request<CollectionSummaryResponse>({ method: 'GET', path: '/collection/summary', inviteCode }),
  }
}

/**
 * 오류 본문은 { code, message }다. 형식이 다르면 UNKNOWN으로 두고 상태 코드만 남긴다.
 * message가 비어 있으면 코드별 문구(없으면 상태 코드가 든 기본 문구)를 쓴다.
 * 본문에 remaining_today가 0 이상의 정수로 있으면 함께 옮긴다(계약: 429 한도 초과와 503 QUESTION_BUSY).
 */
async function toApiError(response: Response): Promise<ApiError> {
  try {
    const body: unknown = await response.json()
    if (typeof body === 'object' && body !== null) {
      const { code, message, remaining_today } = body as {
        code?: unknown
        message?: unknown
        remaining_today?: unknown
      }
      const apiCode = toApiErrorCode(code)
      return new ApiError(
        apiCode,
        typeof message === 'string' && message.trim() !== ''
          ? message
          : httpErrorMessage(apiCode, response.status),
        response.status,
        null,
        {
          remainingToday:
            typeof remaining_today === 'number' && Number.isInteger(remaining_today) && remaining_today >= 0
              ? remaining_today
              : null,
        },
      )
    }
  } catch {
    // 본문이 JSON이 아니면 아래의 기본 메시지를 쓴다.
  }
  return new ApiError('UNKNOWN', httpErrorMessage('UNKNOWN', response.status), response.status)
}
