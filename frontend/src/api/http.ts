import { ApiError, failureMessage, toApiErrorCode } from './errors'
import type { AcceptedInfo } from './errors'
import type {
  CollectionSummaryResponse,
  ConditionsResponse,
  ErApi,
  FeedbackRequest,
  FeedbackResponse,
  InviteVerifyResponse,
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
        throw new ApiError('UNKNOWN', '처리는 끝났지만 결과를 받지 못했습니다.', null, info)
      }
      return { ...state.result, remaining_today: info.remainingToday } as QuestionResponse
    }
    if (state.status === 'failed') {
      const code = toApiErrorCode(state.error_code)
      throw new ApiError(code, failureMessage(code, state.message), null, info)
    }
    return null // accepted·processing, 또는 모르는 상태는 계속 기다린다.
  }

  /** 질문을 이미 접수한 뒤의 오류에는 접수 정보를 붙여, 화면이 횟수 소비와 재확인을 안내할 수 있게 한다. */
  function withAccepted(error: unknown, info: AcceptedInfo): unknown {
    return error instanceof ApiError && error.accepted === null
      ? new ApiError(error.code, error.message, error.status, info)
      : error
  }

  async function askQuestion(
    body: QuestionRequest,
    inviteCode: string,
    onStatus?: (status: QuestionStatus) => void,
  ): Promise<QuestionResponse> {
    // 1) 접수. 여기서 초대 코드·한도·요청 형식 오류가 나면 바로 던진다.
    const accepted = await request<QuestionAcceptedResponse>({
      method: 'POST',
      path: '/questions',
      body,
      inviteCode,
    })
    const info: AcceptedInfo = { requestId: accepted.request_id, remainingToday: accepted.remaining_today }
    const acceptedAt = now()
    onStatus?.(accepted.status)

    // 2) 결과가 나올 때까지 접수 번호로 조회한다.
    let lastStatus: QuestionStatus = accepted.status
    let failures = 0

    for (;;) {
      await sleep(pollIntervalMs)

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
        onStatus?.(state.status)
      }

      const response = interpret(state, info)
      if (response !== null) return response

      if (now() - acceptedAt >= questionTimeoutMs) {
        throw new ApiError('PROCESSING_TIMEOUT', PROCESSING_TIMEOUT_MESSAGE, null, info)
      }
    }
  }

  async function checkQuestion(
    requestId: string,
    remainingToday: number,
    inviteCode: string,
  ): Promise<QuestionResponse> {
    const info: AcceptedInfo = { requestId, remainingToday }
    let state: QuestionStatusResponse
    try {
      state = await fetchStatus(requestId, inviteCode)
    } catch (error) {
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

/** 오류 본문은 { code, message }다. 형식이 다르면 UNKNOWN으로 두고 상태 코드만 남긴다. */
async function toApiError(response: Response): Promise<ApiError> {
  try {
    const body: unknown = await response.json()
    if (typeof body === 'object' && body !== null) {
      const { code, message } = body as { code?: unknown; message?: unknown }
      return new ApiError(
        toApiErrorCode(code),
        typeof message === 'string' && message !== '' ? message : fallbackMessage(response.status),
        response.status,
      )
    }
  } catch {
    // 본문이 JSON이 아니면 아래의 기본 메시지를 쓴다.
  }
  return new ApiError('UNKNOWN', fallbackMessage(response.status), response.status)
}

function fallbackMessage(status: number): string {
  return `요청을 처리하지 못했습니다. (HTTP ${status})`
}
