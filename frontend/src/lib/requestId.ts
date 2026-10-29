/**
 * 접수 번호(request_id)의 형식. 웹 API 계약: 서버가 만드는 소문자 UUID 버전 4 문자열(36자).
 * 입력란에서 앞뒤 공백은 지우고, 대문자로 붙여 넣은 번호는 소문자로 바꿔 검사한다.
 */
const REQUEST_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

/** 입력값을 계약 형식으로 정리한다. 형식이 맞지 않으면 null */
export function normalizeRequestId(input: string): string | null {
  const value = input.trim().toLowerCase()
  return REQUEST_ID_PATTERN.test(value) ? value : null
}
