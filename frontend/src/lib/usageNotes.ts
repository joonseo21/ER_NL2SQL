/**
 * 질문 횟수에 포함되는지 알려 주는 짧은 설명이다(spec D-23).
 * 서버·에이전트의 message에 이미 같은 설명이 있으면 화면이 한 번 더 붙이지 않는다.
 */

/** 접수된 뒤 답변이 아닌 결과(안내·되묻기)로 끝났을 때 */
export const COUNTED_RESPONSE_NOTE = '이 응답도 오늘 질문 횟수에 포함됩니다.'

/** 접수된 뒤 실패하거나 결과가 늦어질 때(기존 문구 유지) */
export const COUNTED_FAILURE_NOTE = '이 질문은 접수되어 오늘 질문 횟수에 포함되었습니다.'

/** 접수 전에 거절됐을 때(한도 초과·처리 중) */
export const NOT_COUNTED_NOTE = '이번 시도는 질문 횟수에 포함되지 않습니다.'

/** 문장에 질문 횟수 포함 여부 설명이 이미 들어 있는지 */
export function mentionsUsage(text: string | null | undefined): boolean {
  if (!text) return false
  return /횟수에\s*포함/.test(text)
}
