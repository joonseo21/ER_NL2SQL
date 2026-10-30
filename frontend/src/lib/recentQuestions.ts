import { ownerKey } from './pendingQuestion'
import { normalizeRequestId } from './requestId'

export const RECENT_QUESTIONS_KEY = 'er-analytics.recent-questions'
export const RECENT_QUESTION_LIMIT = 10
export const RECENT_QUESTION_TTL_MS = 7 * 24 * 60 * 60 * 1000

export interface RecentQuestion {
  requestId: string
  question: string
  acceptedAt: number
}

interface Entry extends RecentQuestion {
  owner: string
}

export interface RecentQuestionsState {
  enabled: boolean
  entries: Entry[]
  /** false면 현재 화면의 선택은 적용하되 저장·삭제 성공을 약속하지 않는다. */
  available: boolean
}

function empty(): RecentQuestionsState {
  return { enabled: true, entries: [], available: true }
}

function validEntry(value: unknown, now: number): value is Entry {
  if (typeof value !== 'object' || value === null) return false
  const v = value as Record<string, unknown>
  return (
    typeof v.owner === 'string' && /^[0-9a-f]{8}$/.test(v.owner) &&
    typeof v.requestId === 'string' && normalizeRequestId(v.requestId) === v.requestId &&
    typeof v.question === 'string' && v.question.trim() !== '' &&
    typeof v.acceptedAt === 'number' && Number.isSafeInteger(v.acceptedAt) &&
    v.acceptedAt > 0 && v.acceptedAt <= now && now - v.acceptedAt < RECENT_QUESTION_TTL_MS
  )
}

function sanitize(entries: unknown[], now: number): Entry[] {
  const owners = new Map<string, Set<string>>()
  return entries.filter((entry) => validEntry(entry, now))
    .sort((a, b) => b.acceptedAt - a.acceptedAt)
    .filter((entry) => {
      const ids = owners.get(entry.owner) ?? new Set<string>()
      owners.set(entry.owner, ids)
      if (ids.size >= RECENT_QUESTION_LIMIT || ids.has(entry.requestId)) return false
      ids.add(entry.requestId)
      return true
    })
    // 허용한 필드만 남긴다. 결과·SQL 등이 섞여 있어도 버린다.
    .map(({ owner, requestId, question, acceptedAt }) => ({ owner, requestId, question, acceptedAt }))
}

function serialized(state: RecentQuestionsState): string {
  return JSON.stringify({ version: 1, enabled: state.enabled, entries: state.entries })
}

function write(state: RecentQuestionsState): RecentQuestionsState {
  try {
    window.localStorage.setItem(RECENT_QUESTIONS_KEY, serialized(state))
    return { ...state, available: true }
  } catch {
    return { ...state, available: false }
  }
}

/** 모든 코드의 만료·잘못된 기록을 정리한다. 설정과 목록은 한 값으로 갱신한다. */
export function loadRecentQuestions(now = Date.now()): RecentQuestionsState {
  let raw: string | null
  try {
    raw = window.localStorage.getItem(RECENT_QUESTIONS_KEY)
  } catch {
    return { ...empty(), available: false }
  }
  if (raw === null) return empty()
  let state = empty()
  try {
    const value: unknown = JSON.parse(raw)
    if (typeof value === 'object' && value !== null) {
      const v = value as Record<string, unknown>
      if (v.version === 1 && typeof v.enabled === 'boolean' && Array.isArray(v.entries)) {
        state = {
          enabled: v.enabled,
          entries: v.enabled ? sanitize(v.entries, now) : [],
          available: true,
        }
      }
    }
  } catch {
    // 깨진 JSON은 빈 목록으로 복구한다.
  }
  return raw === serialized(state) ? state : write(state)
}

export function recentQuestionsFor(state: RecentQuestionsState, inviteCode: string): RecentQuestion[] {
  const owner = ownerKey(inviteCode)
  return state.entries.filter((entry) => entry.owner === owner)
    .map(({ requestId, question, acceptedAt }) => ({ requestId, question, acceptedAt }))
}

/** 접수 응답에서만 호출한다. 읽기·조회·거절에서는 추가하지 않는다. */
export function rememberRecentQuestion(inviteCode: string, question: RecentQuestion): RecentQuestionsState {
  const state = loadRecentQuestions()
  if (!state.available || !state.enabled) return state
  const entry = { owner: ownerKey(inviteCode), ...question }
  if (!validEntry(entry, Date.now())) return state
  return write({ ...state, entries: sanitize([entry, ...state.entries], Date.now()) })
}

export function forgetRecentQuestion(inviteCode: string, requestId: string): RecentQuestionsState {
  const state = loadRecentQuestions()
  if (!state.available) return state
  const owner = ownerKey(inviteCode)
  return write({ ...state, entries: state.entries.filter((e) => e.owner !== owner || e.requestId !== requestId) })
}

/** 이 브라우저의 모든 코드 목록을 지운다. 저장 설정과 서버 기록은 유지한다. */
export function clearRecentQuestions(): RecentQuestionsState {
  const state = loadRecentQuestions()
  return write({ ...state, entries: [] })
}

export function setRecentQuestionsEnabled(enabled: boolean): RecentQuestionsState {
  const state = loadRecentQuestions()
  return write({ ...state, enabled, entries: enabled ? state.entries : [] })
}
