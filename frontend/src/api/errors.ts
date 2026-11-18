export type ApiErrorCode =
  // HTTP 오류로 오는 코드 (웹 API 계약의 "오류 형식")
  | 'INVITE_INVALID'
  | 'INVITE_LIMIT_EXCEEDED'
  | 'GLOBAL_LIMIT_EXCEEDED'
  | 'VALIDATION_FAILED'
  | 'AGENT_UNAVAILABLE'
  | 'QUESTION_NOT_FOUND'
  | 'STORAGE_BUSY'
  | 'COLLECTION_UNAVAILABLE'
  // 다른 질문을 처리 중이라 접수 전에 거절(HTTP 503, spec D-22). 접수되지 않아 횟수를 쓰지 않는다.
  | 'QUESTION_BUSY'
  // 로그인(POST /api/auth/login, M1-045). LOGIN_RATE_LIMITED의 429는 질문 한도의 429와 달리 remaining_today가 없다.
  | 'LOGIN_FAILED'
  | 'LOGIN_RATE_LIMITED'
  | 'LOGIN_DISABLED'
  // 상태 조회 본문(HTTP 200)에서 status=failed일 때 오는 처리 실패 코드 (계약의 "상태 조회와 비동기 실패")
  | 'AGENT_AUTH_FAILED'
  | 'AGENT_REQUEST_REJECTED'
  | 'AGENT_INTERNAL_ERROR'
  | 'AGENT_BUSY'
  | 'AGENT_CONNECTION_FAILED'
  | 'AGENT_TIMEOUT'
  | 'AGENT_HTTP_ERROR'
  | 'AGENT_INVALID_RESPONSE'
  | 'DISPATCH_UNAVAILABLE'
  | 'RESULT_STORAGE_FAILED'
  | 'QUESTION_PROCESSING_FAILED'
  | 'PROCESS_INTERRUPTED'
  // 화면 쪽에서 만드는 코드
  | 'NETWORK_ERROR'
  | 'TIMEOUT'
  | 'PROCESSING_TIMEOUT'
  | 'UNKNOWN'

/** 접수 전에 거절되어 질문 횟수를 쓰지 않는 코드. 오류 본문의 remaining_today를 화면에 반영한다. */
export const REJECTED_BEFORE_ACCEPT: ReadonlySet<ApiErrorCode> = new Set<ApiErrorCode>([
  'INVITE_LIMIT_EXCEEDED',
  'GLOBAL_LIMIT_EXCEEDED',
  'QUESTION_BUSY',
])

/** 한도 초과 코드. 오류 본문에 remaining_today가 없으면 남은 횟수를 0으로 본다. */
export const LIMIT_EXCEEDED: ReadonlySet<ApiErrorCode> = new Set<ApiErrorCode>([
  'INVITE_LIMIT_EXCEEDED',
  'GLOBAL_LIMIT_EXCEEDED',
])

/**
 * 처리 실패(failed)의 코드별 안내 문구다. 웹 API 계약 표의 문장과 같다. 서버는 같은 문구를 message로 보내므로
 * 평소에는 서버 message를 그대로 쓰고, message가 비어 있을 때만 이 표를 쓴다.
 */
const FAILURE_MESSAGES: Partial<Record<ApiErrorCode, string>> = {
  AGENT_AUTH_FAILED: '서비스 설정 문제로 질문을 처리하지 못했습니다. 운영자에게 알려 주세요.',
  AGENT_REQUEST_REJECTED: '질문을 전달하지 못했습니다. 질문과 선택한 조건을 확인하고 다시 시도해 주세요.',
  AGENT_INTERNAL_ERROR: '질문을 처리하는 중 오류가 발생했습니다. 잠시 뒤 다시 질문해 주세요.',
  AGENT_BUSY: '질문 처리 서버가 아직 다른 질문을 처리 중입니다. 1~2분 뒤에 다시 질문해 주세요.',
  AGENT_CONNECTION_FAILED: '질문 처리 서버에 연결하지 못했습니다. 잠시 뒤 다시 질문해 주세요.',
  AGENT_TIMEOUT:
    '질문 처리 서버의 응답을 기다리는 시간이 끝났습니다. 처리가 아직 이어지고 있을 수 있으니 1~2분 뒤에 다시 질문해 주세요.',
  AGENT_HTTP_ERROR:
    '질문 처리 서버의 응답을 확인할 수 없었습니다. 잠시 뒤 다시 질문해 주세요. 계속되면 운영자에게 알려 주세요.',
  AGENT_INVALID_RESPONSE:
    '질문 처리 서버의 답변을 확인할 수 없었습니다. 잠시 뒤 다시 질문해 주세요. 계속되면 운영자에게 알려 주세요.',
  DISPATCH_UNAVAILABLE: '질문을 접수했지만 처리를 시작하지 못했습니다. 잠시 뒤 다시 질문해 주세요.',
  RESULT_STORAGE_FAILED:
    '질문의 답변을 저장하지 못했습니다. 잠시 뒤 새로 질문해 주세요. 계속되면 운영자에게 알려 주세요.',
  QUESTION_PROCESSING_FAILED:
    '질문 처리에 실패했습니다. 잠시 뒤 다시 질문해 주세요. 계속되면 운영자에게 알려 주세요.',
  PROCESS_INTERRUPTED: '서버가 다시 시작되어 질문 처리가 중단됐습니다. 잠시 뒤 새로 질문해 주세요.',
}

const GENERIC_FAILURE_MESSAGE = '질문을 처리하지 못했습니다. 잠시 뒤 다시 시도해 주세요.'

/** 처리 중이라 접수 전에 거절됐을 때의 안내 문구(spec D-22). 서버 message가 비어 있을 때만 쓴다. */
export const QUESTION_BUSY_MESSAGE =
  '지금 다른 질문을 처리하고 있습니다. 잠시 뒤 다시 질문해 주세요. 이번 시도는 질문 횟수에 포함되지 않습니다.'

/** HTTP 오류 본문에 message가 없을 때 코드별로 쓰는 문구다. 없는 코드는 상태 코드가 든 기본 문구를 쓴다. */
const HTTP_ERROR_MESSAGES: Partial<Record<ApiErrorCode, string>> = {
  QUESTION_BUSY: QUESTION_BUSY_MESSAGE,
  LOGIN_FAILED: '아이디 또는 비밀번호가 올바르지 않습니다.',
  LOGIN_RATE_LIMITED: '로그인 시도가 너무 많습니다. 10분 뒤 다시 시도해 주세요.',
  LOGIN_DISABLED: '지금은 로그인을 사용할 수 없습니다. 초대 코드로 입장해 주세요.',
}

export function httpErrorMessage(code: ApiErrorCode, status: number): string {
  return HTTP_ERROR_MESSAGES[code] ?? `요청을 처리하지 못했습니다. (HTTP ${status})`
}

/** 서버가 보낸 문자열이 알려진 오류 코드면 그대로, 아니면 UNKNOWN으로 바꾼다. */
export function toApiErrorCode(value: unknown): ApiErrorCode {
  if (typeof value !== 'string') return 'UNKNOWN'
  return Object.hasOwn(SERVER_CODE_SET, value) ? (value as ApiErrorCode) : 'UNKNOWN'
}

// 객체 키로 만들어 두면 화면이 쓰는 코드(NETWORK_ERROR 등)를 서버 값으로 오해하지 않는다.
const SERVER_CODE_SET: Record<string, true> = {
  INVITE_INVALID: true,
  INVITE_LIMIT_EXCEEDED: true,
  GLOBAL_LIMIT_EXCEEDED: true,
  VALIDATION_FAILED: true,
  AGENT_UNAVAILABLE: true,
  QUESTION_NOT_FOUND: true,
  STORAGE_BUSY: true,
  COLLECTION_UNAVAILABLE: true,
  QUESTION_BUSY: true,
  LOGIN_FAILED: true,
  LOGIN_RATE_LIMITED: true,
  LOGIN_DISABLED: true,
  AGENT_AUTH_FAILED: true,
  AGENT_REQUEST_REJECTED: true,
  AGENT_INTERNAL_ERROR: true,
  AGENT_BUSY: true,
  AGENT_CONNECTION_FAILED: true,
  AGENT_TIMEOUT: true,
  AGENT_HTTP_ERROR: true,
  AGENT_INVALID_RESPONSE: true,
  DISPATCH_UNAVAILABLE: true,
  RESULT_STORAGE_FAILED: true,
  QUESTION_PROCESSING_FAILED: true,
  PROCESS_INTERRUPTED: true,
}

/**
 * 처리 실패의 안내 문구. 서버 message가 있으면 그대로 쓰고, 없으면 코드별 문구, 그것도 없으면 일반 문구다.
 * 코드가 새로 추가되어도 서버 message를 그대로 보여 주면 된다.
 */
export function failureMessage(code: ApiErrorCode, serverMessage: string | null): string {
  if (serverMessage !== null && serverMessage.trim() !== '') return serverMessage
  return FAILURE_MESSAGES[code] ?? GENERIC_FAILURE_MESSAGE
}

/** 서버가 질문을 이미 접수한 뒤에 난 오류에 붙는 정보다. 접수된 질문은 오늘 횟수에 포함된다. */
export interface AcceptedInfo {
  /** 접수 번호. 결과를 다시 확인할 때 쓴다. */
  requestId: string
  /** 접수 시점의 오늘 남은 횟수. 접수 번호를 직접 입력해 조회한 경우처럼 알 수 없으면 null */
  remainingToday: number | null
}

/** catch로 잡은 값이 ApiError가 아니어도 화면이 같은 방식으로 다룰 수 있게 바꾼다. */
export function asApiError(error: unknown): ApiError {
  return error instanceof ApiError
    ? error
    : new ApiError('UNKNOWN', '알 수 없는 오류가 발생했습니다.')
}

export interface ApiErrorDetails {
  /** 접수 전 오류 본문에 서버가 오늘 남은 횟수(remaining_today)를 담아 보냈으면 그 값 */
  remainingToday?: number | null
  /** 접수된 질문의 처리가 실패로 끝났으면 true. 결과가 정해졌으므로 다시 조회할 필요가 없다. */
  requestFinished?: boolean
}

/** 화면은 code로 분기하고 message는 그대로 보여 준다. */
export class ApiError extends Error {
  readonly code: ApiErrorCode
  /** HTTP 상태 코드. 서버 응답이 없었거나 HTTP 200 본문의 실패면 null */
  readonly status: number | null
  /** 질문을 접수한 뒤에 난 오류면 접수 정보. 접수 전 오류면 null */
  readonly accepted: AcceptedInfo | null
  /** 접수 전 오류 본문에 서버가 오늘 남은 횟수(remaining_today)를 담아 보냈으면 그 값. 없으면 null */
  readonly remainingToday: number | null
  /** 접수된 질문의 처리가 실패(failed)로 끝났으면 true. 조회가 잠시 실패했거나 아직 처리 중이면 false */
  readonly requestFinished: boolean

  constructor(
    code: ApiErrorCode,
    message: string,
    status: number | null = null,
    accepted: AcceptedInfo | null = null,
    details: ApiErrorDetails = {},
  ) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.accepted = accepted
    this.remainingToday = details.remainingToday ?? null
    this.requestFinished = details.requestFinished ?? false
  }
}
