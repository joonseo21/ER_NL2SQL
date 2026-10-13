export type ApiErrorCode =
  // 서버가 돌려주는 코드 (웹 API 계약·명세)
  | 'INVITE_INVALID'
  | 'INVITE_LIMIT_EXCEEDED'
  | 'GLOBAL_LIMIT_EXCEEDED'
  | 'VALIDATION_FAILED'
  | 'AGENT_UNAVAILABLE'
  | 'QUESTION_NOT_FOUND'
  | 'STORAGE_BUSY'
  // 화면 쪽에서 만드는 코드
  | 'NETWORK_ERROR'
  | 'TIMEOUT'
  | 'UNKNOWN'

const SERVER_CODES: ReadonlySet<string> = new Set<ApiErrorCode>([
  'INVITE_INVALID',
  'INVITE_LIMIT_EXCEEDED',
  'GLOBAL_LIMIT_EXCEEDED',
  'VALIDATION_FAILED',
  'AGENT_UNAVAILABLE',
  'QUESTION_NOT_FOUND',
  'STORAGE_BUSY',
])

/** 서버가 보낸 문자열이 알려진 오류 코드면 그대로, 아니면 UNKNOWN으로 바꾼다. */
export function toApiErrorCode(value: unknown): ApiErrorCode {
  return typeof value === 'string' && SERVER_CODES.has(value) ? (value as ApiErrorCode) : 'UNKNOWN'
}

/** catch로 잡은 값이 ApiError가 아니어도 화면이 같은 방식으로 다룰 수 있게 바꾼다. */
export function asApiError(error: unknown): ApiError {
  return error instanceof ApiError
    ? error
    : new ApiError('UNKNOWN', '알 수 없는 오류가 발생했습니다.')
}

/** 화면은 code로 분기하고 message는 그대로 보여 준다. */
export class ApiError extends Error {
  readonly code: ApiErrorCode
  /** HTTP 상태 코드. 서버 응답이 없었으면 null */
  readonly status: number | null

  constructor(code: ApiErrorCode, message: string, status: number | null = null) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}
