import type { AcceptedQuestion } from '../api/types'

/**
 * 접수했지만 아직 결과를 받지 못한 질문을 같은 탭의 sessionStorage에 보관한다.
 * 같은 탭에서 새로고침하면 이 값으로 조회를 이어 간다. 결과가 나오면 지운다.
 * 탭을 닫으면 sessionStorage가 비므로, 그때는 접수 번호를 직접 입력해 결과를 확인한다.
 *
 * 초대 코드 원문은 여기에 두지 않고 짧은 해시만 둔다. 다른 초대 코드로 들어오면 이어 가지 않는다.
 * 저장소는 사생활 보호 모드 등에서 막히거나 예외를 던질 수 있으므로 모든 접근을 감싼다.
 */

const KEY = 'er-analytics.pending-question'

export interface PendingQuestion extends AcceptedQuestion {
  /** 보낸 질문. 새로고침 뒤 입력란에 다시 채운다. */
  question: string
}

interface Stored extends PendingQuestion {
  owner: string
}

/** 초대 코드를 원문 없이 구분하기 위한 해시(FNV-1a 32비트). 보안용이 아니라 같은 코드인지 보는 용도다. */
export function ownerKey(inviteCode: string): string {
  let hash = 0x811c9dc5
  for (let i = 0; i < inviteCode.length; i += 1) {
    hash ^= inviteCode.charCodeAt(i)
    hash = Math.imul(hash, 0x01000193)
  }
  return (hash >>> 0).toString(16).padStart(8, '0')
}

export function savePendingQuestion(inviteCode: string, pending: PendingQuestion): void {
  const stored: Stored = { ...pending, owner: ownerKey(inviteCode) }
  try {
    window.sessionStorage.setItem(KEY, JSON.stringify(stored))
  } catch {
    // 저장하지 못하면 새로고침 뒤 이어 보기만 안 될 뿐, 접수 번호는 화면에 보인다.
  }
}

/** 이 초대 코드로 보관한 진행 중인 질문. 없거나 형식이 깨졌거나 다른 코드의 것이면 null */
export function loadPendingQuestion(inviteCode: string): PendingQuestion | null {
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
    clearPendingQuestion()
    return null
  }
  if (!isStored(value) || value.owner !== ownerKey(inviteCode)) {
    clearPendingQuestion()
    return null
  }
  return {
    requestId: value.requestId,
    remainingToday: value.remainingToday,
    acceptedAt: value.acceptedAt,
    question: value.question,
  }
}

/** requestId를 주면 그 번호를 보관하고 있을 때만 지운다. 생략하면 무조건 지운다. */
export function clearPendingQuestion(requestId?: string): void {
  try {
    if (requestId !== undefined) {
      const raw = window.sessionStorage.getItem(KEY)
      if (raw === null) return
      const value: unknown = JSON.parse(raw)
      if (isStored(value) && value.requestId !== requestId) return
    }
    window.sessionStorage.removeItem(KEY)
  } catch {
    // 지우지 못해도 계속 진행한다. 다음 조회에서 결과가 나오면 다시 지운다.
  }
}

function isStored(value: unknown): value is Stored {
  if (typeof value !== 'object' || value === null) return false
  const v = value as Record<string, unknown>
  return (
    typeof v.requestId === 'string' &&
    typeof v.remainingToday === 'number' &&
    typeof v.acceptedAt === 'number' &&
    Number.isFinite(v.acceptedAt) &&
    typeof v.question === 'string' &&
    typeof v.owner === 'string'
  )
}
