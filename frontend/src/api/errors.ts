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
  // 화면 쪽에서 만드는 코드
  | 'NETWORK_ERROR'
  | 'TIMEOUT'
  | 'PROCESSING_TIMEOUT'
  | 'UNKNOWN'

/**
 * 처리 실패(failed)의 코드별 안내 문구다. 서버는 같은 문구를 message로 보내므로 평소에는 서버 message를
 * 그대로 쓰고, message가 비어 있을 때만 이 표를 쓴다.
 */
const FAILURE_MESSAGES: Partial<Record<ApiErrorCode, string>> = {
  AGENT_AUTH_FAILED: '질문 처리 서버의 연결 설정에 문제가 있습니다. 운영자에게 알려 주세요.',
  AGENT_REQUEST_REJECTED:
    '질문 처리 서버가 요청 형식을 받아들이지 못했습니다. 질문과 조건을 확인해 주세요.',
  AGENT_INTERNAL_ERROR: '질문 처리 서버에서 오류가 발생했습니다. 잠시 뒤 다시 이용해 주세요.',
  AGENT_BUSY: '다른 질문을 처리 중입니다. 잠시 뒤 다시 질문해 주세요.',
  AGENT_CONNECTION_FAILED: '질문 처리 서버에 연결하지 못했습니다. 잠시 뒤 다시 이용해 주세요.',
  AGENT_TIMEOUT:
    '질문 처리 서버의 응답을 기다리는 시간이 끝났습니다. 자동으로 다시 요청하지 않습니다.',
  AGENT_HTTP_ERROR: '질문 처리 서버에서 예상하지 못한 응답을 받았습니다. 운영자에게 알려 주세요.',
  AGENT_INVALID_RESPONSE: '질문 처리 서버의 결과 형식을 확인하지 못했습니다. 운영자에게 알려 주세요.',
  DISPATCH_UNAVAILABLE:
    '질문을 접수했지만 처리 작업을 시작하지 못했습니다. 잠시 뒤 다시 이용해 주세요.',
  RESULT_STORAGE_FAILED:
    '처리 결과를 저장하지 못했습니다. 운영자에게 알려 주세요. 자동으로 다시 요청하지 않습니다.',
  QUESTION_PROCESSING_FAILED: '질문 처리에 실패했습니다. 자동으로 다시 전송하지 않습니다.',
}

const GENERIC_FAILURE_MESSAGE = '질문을 처리하지 못했습니다. 잠시 뒤 다시 시도해 주세요.'

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
  /** 접수 시점의 오늘 남은 횟수 */
  remainingToday: number
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
  /** HTTP 상태 코드. 서버 응답이 없었거나 HTTP 200 본문의 실패면 null */
  readonly status: number | null
  /** 질문을 접수한 뒤에 난 오류면 접수 정보. 접수 전 오류면 null */
  readonly accepted: AcceptedInfo | null

  constructor(
    code: ApiErrorCode,
    message: string,
    status: number | null = null,
    accepted: AcceptedInfo | null = null,
  ) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.accepted = accepted
  }
}
