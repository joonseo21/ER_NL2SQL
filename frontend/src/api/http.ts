import { ApiError, toApiErrorCode } from './errors'
import type {
  ConditionsResponse,
  ErApi,
  InviteVerifyResponse,
  QuestionRequest,
  QuestionResponse,
} from './types'

/**
 * 질문 하나를 기다리는 최대 시간. 에이전트는 Gemini를 2~3번 부르고 SQL을 실행하며
 * 서버(Spring)의 대기 권고가 330초다. 화면이 먼저 포기하지 않도록 그보다 길게 잡는다.
 */
const QUESTION_TIMEOUT_MS = 360_000
const DEFAULT_TIMEOUT_MS = 30_000

export interface HttpApiOptions {
  /** 화면과 API는 같은 주소에서 제공되므로 기본은 상대 경로 /api다. */
  baseUrl?: string
  /** 테스트에서 가짜 fetch를 넣기 위한 자리 */
  fetchFn?: typeof fetch
}

interface RequestOptions {
  method: 'GET' | 'POST'
  path: string
  body?: unknown
  /** 초대 코드는 URL이 아니라 헤더로만 보낸다. */
  inviteCode?: string
  timeoutMs: number
}

export function createHttpApi(options: HttpApiOptions = {}): ErApi {
  const baseUrl = options.baseUrl ?? '/api'
  const fetchFn = options.fetchFn ?? ((...args) => fetch(...args))

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
        signal: AbortSignal.timeout(req.timeoutMs),
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

  return {
    verifyInvite: (code) =>
      request<InviteVerifyResponse>({
        method: 'POST',
        path: '/invite/verify',
        body: { code },
        timeoutMs: DEFAULT_TIMEOUT_MS,
      }),

    getConditions: (inviteCode) =>
      request<ConditionsResponse>({
        method: 'GET',
        path: '/conditions',
        inviteCode,
        timeoutMs: DEFAULT_TIMEOUT_MS,
      }),

    askQuestion: (body: QuestionRequest, inviteCode) =>
      request<QuestionResponse>({
        method: 'POST',
        path: '/questions',
        body,
        inviteCode,
        timeoutMs: QUESTION_TIMEOUT_MS,
      }),
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
