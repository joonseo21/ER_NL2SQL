import type { QuestionResponse } from '../api/types'
import { ownerKey } from './pendingQuestion'

/**
 * 마지막으로 받은 결과(답변·안내·되묻기)를 같은 탭의 sessionStorage에 보관한다.
 * 같은 탭에서 새로고침해도 그 결과를 다시 보여 준다. 탭을 닫으면 사라지며, 그때는 접수 번호로 다시 확인한다.
 *
 * 새 질문이 접수되거나, 접수 번호로 다른 결과를 확인하거나, 초대 코드를 바꾸면 지운다.
 * 초대 코드 원문은 두지 않고 짧은 해시만 둔다. 다른 초대 코드로 들어오면 보여 주지 않는다.
 * 저장소는 사생활 보호 모드 등에서 막히거나 예외를 던질 수 있으므로 모든 접근을 감싼다.
 */

const KEY = 'er-analytics.last-result'

export interface LastResult {
  /** 결과의 접수 번호. 모르면 null */
  requestId: string | null
  response: QuestionResponse
}

interface Stored extends LastResult {
  owner: string
}

export function saveLastResult(inviteCode: string, result: LastResult): void {
  const stored: Stored = { ...result, owner: ownerKey(inviteCode) }
  try {
    window.sessionStorage.setItem(KEY, JSON.stringify(stored))
  } catch {
    // 저장하지 못하면 새로고침 뒤 다시 보여 주기만 안 될 뿐이다(용량 초과 포함).
  }
}

/** 이 초대 코드로 보관한 마지막 결과. 없거나 형식이 깨졌거나 다른 코드의 것이면 null */
export function loadLastResult(inviteCode: string): LastResult | null {
  let raw: string | null
  try {
    raw = window.sessionStorage.getItem(KEY)
  } catch {
    return null
  }
  if (raw === null) return null

  let value: unknown
  try {
    value = JSON.parse(raw)
  } catch {
    clearLastResult()
    return null
  }
  if (!isStored(value) || value.owner !== ownerKey(inviteCode)) {
    clearLastResult()
    return null
  }
  return { requestId: value.requestId, response: value.response }
}

export function clearLastResult(): void {
  try {
    window.sessionStorage.removeItem(KEY)
  } catch {
    // 지우지 못해도 계속 진행한다.
  }
}

function isStored(value: unknown): value is Stored {
  if (typeof value !== 'object' || value === null) return false
  const v = value as Record<string, unknown>
  if (typeof v.owner !== 'string') return false
  if (v.requestId !== null && typeof v.requestId !== 'string') return false
  const r = v.response as Record<string, unknown> | null
  return (
    typeof r === 'object' &&
    r !== null &&
    (r.kind === 'answer' || r.kind === 'notice' || r.kind === 'clarification') &&
    typeof r.log_id === 'string' &&
    typeof r.question === 'string' &&
    Array.isArray(r.columns) &&
    Array.isArray(r.rows)
  )
}
