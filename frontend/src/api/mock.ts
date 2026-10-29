import answerEmptyResult from './mocks/answer-empty-result.json'
import answerWithoutSummary from './mocks/answer-without-summary.json'
import answer from './mocks/answer.json'
import clarification from './mocks/clarification.json'
import notice from './mocks/notice.json'
import { ownerKey } from '../lib/pendingQuestion'
import { ApiError, failureMessage, QUESTION_BUSY_MESSAGE } from './errors'
import type { AcceptedInfo } from './errors'
import { FEEDBACK_COMMENT_MAX } from './types'
import type {
  AgentResponse,
  CollectionSummaryResponse,
  ConditionsResponse,
  ErApi,
  PollOptions,
  QuestionResponse,
  QuestionStatus,
} from './types'

/**
 * 백엔드 없이 개발하기 위한 가짜 서버다.
 * 응답은 docs/contracts/samples의 에이전트 응답 예시를 그대로 쓰고, 질문 문구·log_id·남은 횟수만 요청마다 바꾼다.
 * 표·적용 조건·시각은 고정된 예시이므로 질문이나 조건과 맞지 않을 수 있다.
 *
 * 초대 코드: 비어 있거나 "INVALID"이면 INVITE_INVALID, 그 밖의 값은 모두 유효하다.
 */

/** 화면 위의 개발용 선택 막대가 보여 줄 목록이다. */
export const MOCK_SCENARIOS = [
  { id: 'answer', label: '답변' },
  { id: 'answer-without-summary', label: '답변(요약 없음)' },
  { id: 'answer-empty-result', label: '답변(결과 0행)' },
  { id: 'slow-answer', label: '답변(처리 20초, 새로고침·재열기 시험용)' },
  { id: 'notice', label: '안내' },
  { id: 'clarification', label: '되묻기' },
  { id: 'error-invite-limit', label: '오류: 코드별 한도 초과' },
  { id: 'error-global-limit', label: '오류: 전체 한도 초과' },
  { id: 'error-question-busy', label: '오류: 다른 질문 처리 중(접수 전 거절)' },
  { id: 'error-agent', label: '오류: 접수 뒤 처리 실패(에이전트 연결 실패)' },
  { id: 'error-timeout', label: '오류: 처리 지연(자동 조회 중단)' },
] as const

export type MockScenarioId = (typeof MOCK_SCENARIOS)[number]['id']

/** 개발용 선택 막대가 바꾸는 값이다. createMockApi의 scenario 옵션을 생략하면 이 값을 따른다. */
export const mockControl: { scenario: MockScenarioId } = { scenario: 'answer' }

export interface MockApiOptions {
  /** 응답이 오기까지 걸리는 시간(ms). 대기 화면을 확인할 수 있도록 기본은 0.8초다. */
  delayMs?: number
  /** 질문마다 어떤 응답을 줄지 정한다. 생략하면 mockControl.scenario를 따른다. */
  scenario?: () => MockScenarioId
  /** 오늘 남은 횟수의 시작 값 */
  initialRemaining?: number
}

// JSON을 가져오면 kind가 string으로만 추론되므로 계약 타입으로 한 번 단언한다.
// 형식이 계약과 맞는지는 mock.test.ts가 검사한다.
const SAMPLES: Record<string, AgentResponse> = {
  answer: answer as unknown as AgentResponse,
  'answer-without-summary': answerWithoutSummary as unknown as AgentResponse,
  'answer-empty-result': answerEmptyResult as unknown as AgentResponse,
  notice: notice as unknown as AgentResponse,
  clarification: clarification as unknown as AgentResponse,
}

// 실험체 코드와 이름의 대응은 실제와 다를 수 있다. 아드리아나(17)와 아르다(66)만 샘플에서 확인했다.
const CONDITIONS: ConditionsResponse = {
  patches: [
    { major: 5, minor: 0, first_match: '2026-09-24T03:10:00+09:00', last_match: '2026-10-12T14:51:30+09:00' },
    { major: 4, minor: 0, first_match: '2026-09-10T01:20:00+09:00', last_match: '2026-09-23T23:58:00+09:00' },
    { major: 3, minor: 0, first_match: null, last_match: null },
  ],
  tiers: ['플래티넘', '다이아몬드', '메테오라이트', '미스릴 이상', '플래티넘 미만'],
  characters: [
    { code: 17, name: '아드리아나' },
    { code: 66, name: '아르다' },
    { code: 1, name: '재키' },
    { code: 2, name: '아야' },
    { code: 3, name: '피오라' },
  ],
}

// 수치는 모두 예시이며 실제 수집량과 관계없다. 형식은 웹 API 계약 "이용자용 수집 현황"을 따른다.
// 티어를 확인하지 못한 항목(tier: null)과 자료가 없는 알려진 티어(0)도 포함해 화면이 둘 다 다루는지 본다.
const COLLECTION_SUMMARY: CollectionSummaryResponse = {
  total_games: 72_140,
  by_tier: [
    { tier: '플래티넘', users: 1_980, participant_appearances: 214_502 },
    { tier: '다이아몬드', users: 3_412, participant_appearances: 436_118 },
    { tier: '메테오라이트', users: 2_201, participant_appearances: 389_774 },
    { tier: '미스릴 이상', users: 870, participant_appearances: 207_355 },
    { tier: '플래티넘 미만', users: 0, participant_appearances: 0 },
    { tier: null, users: 12, participant_appearances: 1_840 },
  ],
  patches: CONDITIONS.patches,
  latest_collected_at: '2026-10-13T11:42:00+09:00',
  snapshot_at: '2026-10-13T11:45:10+09:00',
  cached_until: '2026-10-13T11:50:10+09:00',
}

const DAILY_LIMIT = 50
/** 화면 대기 시간과 같은 값(웹 API 계약 420초). 가짜 서버도 이 시간이 지나면 자동 조회를 멈춘다. */
const QUESTION_TIMEOUT_MS = 420_000
/** 새로고침·재열기 시험용 시나리오의 처리 시간 */
const SLOW_PROCESSING_MS = 20_000
const PROCESSING_TIMEOUT_MESSAGE = '처리가 오래 걸리고 있습니다. 나중에 결과를 다시 확인해 주세요.'

/**
 * 가짜 서버가 접수한 질문. 실제 서버처럼 탭을 닫아도 남도록 localStorage에 보관한다(개발용 가짜 응답 모드 전용).
 * 저장소를 쓸 수 없으면 메모리에만 둔다. 초대 코드 원문은 두지 않고 해시만 둔다.
 */
interface MockRecord {
  requestId: string
  owner: string
  /** 접수할 때 고른 시나리오. 결과의 종류를 정한다. */
  outcome: MockScenarioId
  acceptedAt: number
  /** 이 시각부터 처리가 끝난 것으로 본다. */
  readyAt: number
  question: string
}

const STORE_KEY = 'er-analytics.mock-server-requests'
const STORE_LIMIT = 50
let memoryRecords: MockRecord[] = []
let persisted = true

function loadRecords(): MockRecord[] {
  if (!persisted) return memoryRecords
  try {
    const raw = window.localStorage.getItem(STORE_KEY)
    if (raw === null) return memoryRecords
    const value: unknown = JSON.parse(raw)
    return Array.isArray(value) ? (value as MockRecord[]) : memoryRecords
  } catch {
    return memoryRecords
  }
}

function saveRecords(records: MockRecord[]): void {
  memoryRecords = records.slice(-STORE_LIMIT)
  try {
    window.localStorage.setItem(STORE_KEY, JSON.stringify(memoryRecords))
    persisted = true
  } catch {
    // 저장하지 못하면 이 탭의 메모리에만 남는다.
    persisted = false
  }
}

/** 시나리오별로 완료 응답에 쓸 샘플 */
function sampleFor(outcome: MockScenarioId): keyof typeof SAMPLES {
  return Object.hasOwn(SAMPLES, outcome) ? outcome : 'answer'
}

export function createMockApi(options: MockApiOptions = {}): ErApi {
  const delayMs = options.delayMs ?? 800
  const scenario = options.scenario ?? (() => mockControl.scenario)
  let remaining = options.initialRemaining ?? DAILY_LIMIT
  /** 이 가짜 서버가 돌려준 log_id. 후기는 아는 질문에만 받는다. */
  const knownLogIds = new Set<string>()

  async function wait(): Promise<void> {
    if (delayMs > 0) await new Promise((resolve) => setTimeout(resolve, delayMs))
  }

  function assertInviteCode(code: string): void {
    if (code.trim() === '' || code === 'INVALID') {
      throw new ApiError('INVITE_INVALID', '초대 코드를 확인해 주세요.', 401)
    }
  }

  /** 질문 응답 하나를 만든다. 샘플을 복사해 질문 문구와 log_id, 남은 횟수만 바꾼다. */
  function buildResponse(
    id: keyof typeof SAMPLES,
    question: string,
    remainingToday: number | null,
  ): QuestionResponse {
    const log_id = globalThis.crypto.randomUUID()
    knownLogIds.add(log_id)
    return { ...structuredClone(SAMPLES[id]), question, log_id, remaining_today: remainingToday }
  }

  /** 같은 초대 코드로 접수한 질문만 찾는다. 없는 번호와 다른 코드의 번호는 같은 404다. */
  function findRecord(requestId: string, inviteCode: string): MockRecord {
    const record = loadRecords().find((r) => r.requestId === requestId && r.owner === ownerKey(inviteCode))
    if (!record) throw new ApiError('QUESTION_NOT_FOUND', '질문을 찾을 수 없습니다.', 404)
    return record
  }

  /** 이 초대 코드로 접수해 아직 처리 중인 질문이 있는지(처리 중 거절 재현용) */
  function hasActive(inviteCode: string): boolean {
    const now = Date.now()
    return loadRecords().some((r) => r.owner === ownerKey(inviteCode) && r.readyAt > now)
  }

  /** 기록의 현재 상태를 조회 응답처럼 해석한다. 완료면 응답, 실패면 오류, 처리 중이면 null */
  function interpret(record: MockRecord, info: AcceptedInfo): QuestionResponse | null {
    if (Date.now() < record.readyAt) return null
    if (record.outcome === 'error-agent') {
      throw new ApiError(
        'AGENT_CONNECTION_FAILED',
        failureMessage('AGENT_CONNECTION_FAILED', null),
        null,
        info,
        { requestFinished: true },
      )
    }
    return buildResponse(sampleFor(record.outcome), record.question, info.remainingToday)
  }

  /** 결과가 나올 때까지 기록을 조회한다. 실제 서버 조회처럼 상태가 바뀔 때만 알린다. */
  async function poll(
    record: MockRecord,
    info: AcceptedInfo,
    lastStatus: QuestionStatus | null,
    pollOptions: PollOptions,
  ): Promise<QuestionResponse> {
    const step = record.readyAt - record.acceptedAt > delayMs ? 1000 : Math.round(delayMs / 3)
    let status = lastStatus
    for (;;) {
      if (pollOptions.signal?.aborted) throw new ApiError('UNKNOWN', '결과 조회를 멈췄습니다.', null, info)
      const response = interpret(record, info)
      const next: QuestionStatus = response === null ? 'processing' : 'completed'
      if (next !== status) {
        status = next
        pollOptions.onStatus?.(next)
      }
      if (response !== null) return response
      if (Date.now() - record.acceptedAt >= QUESTION_TIMEOUT_MS) {
        throw new ApiError('PROCESSING_TIMEOUT', PROCESSING_TIMEOUT_MESSAGE, null, info)
      }
      if (step > 0) await new Promise((resolve) => setTimeout(resolve, step))
    }
  }

  return {
    async verifyInvite(code) {
      await wait()
      assertInviteCode(code)
      return { valid: true, remaining_today: remaining }
    },

    async getConditions(inviteCode) {
      await wait()
      assertInviteCode(inviteCode)
      return structuredClone(CONDITIONS)
    },

    /**
     * 실제 서버처럼 접수(accepted) → 처리 중(processing) → 완료(completed)/실패(failed) 순서를 거친다.
     * 접수 단계의 오류(초대 코드·한도·형식·처리 중 거절)는 바로 던지고, 접수한 뒤의 실패는 횟수를 소비한 채 던진다.
     */
    async askQuestion(request, inviteCode, onStatus, onAccepted, signal) {
      const step = Math.round(delayMs / 3)
      const pause = () => (step > 0 ? new Promise((resolve) => setTimeout(resolve, step)) : undefined)

      await pause()
      assertInviteCode(inviteCode)
      if (request.question.trim() === '') {
        throw new ApiError('VALIDATION_FAILED', '질문을 입력해 주세요.', 400)
      }

      const id = scenario()
      switch (id) {
        case 'error-invite-limit':
          throw new ApiError('INVITE_LIMIT_EXCEEDED', '오늘 질문 횟수를 모두 사용했습니다.', 429, null, {
            remainingToday: 0,
          })
        case 'error-global-limit':
          throw new ApiError('GLOBAL_LIMIT_EXCEEDED', '오늘 전체 질문 횟수를 모두 사용했습니다.', 429, null, {
            remainingToday: 0,
          })
        case 'error-question-busy':
          throw new ApiError('QUESTION_BUSY', QUESTION_BUSY_MESSAGE, 503, null, { remainingToday: remaining })
      }
      // 같은 초대 코드로 처리 중인 질문이 있으면(다른 탭에서 보낸 느린 질문 등) 실제 서버처럼 접수 전에 거절한다.
      if (hasActive(inviteCode)) {
        throw new ApiError('QUESTION_BUSY', QUESTION_BUSY_MESSAGE, 503, null, { remainingToday: remaining })
      }

      // 접수하면 횟수를 소비한다. 안내·되묻기도, 접수 이후의 실패도 마찬가지다(웹 API 명세).
      remaining = Math.max(0, remaining - 1)
      const acceptedAt = Date.now()
      const record: MockRecord = {
        requestId: globalThis.crypto.randomUUID(),
        owner: ownerKey(inviteCode),
        outcome: id,
        acceptedAt,
        readyAt: acceptedAt + (id === 'slow-answer' ? SLOW_PROCESSING_MS : 0),
        question: request.question,
      }
      saveRecords([...loadRecords(), record])
      const info: AcceptedInfo = { requestId: record.requestId, remainingToday: remaining }
      onStatus?.('accepted')
      onAccepted?.({ requestId: record.requestId, remainingToday: remaining, acceptedAt })
      await pause()
      onStatus?.('processing')
      await pause()

      if (id === 'error-timeout') {
        // 자동 조회가 멈춘 상황을 흉내 낸다. 기록은 처리가 끝난 것으로 두어 다시 확인하면 답변을 받는다.
        throw new ApiError('PROCESSING_TIMEOUT', PROCESSING_TIMEOUT_MESSAGE, null, info)
      }
      return poll(record, info, 'processing', { onStatus, signal })
    },

    async resumeQuestion(accepted, inviteCode, pollOptions = {}) {
      await wait()
      assertInviteCode(inviteCode)
      const info: AcceptedInfo = { requestId: accepted.requestId, remainingToday: accepted.remainingToday }
      if (Date.now() - accepted.acceptedAt >= QUESTION_TIMEOUT_MS) {
        throw new ApiError('PROCESSING_TIMEOUT', PROCESSING_TIMEOUT_MESSAGE, null, info)
      }
      const record = findRecord(accepted.requestId, inviteCode)
      return poll(record, info, null, pollOptions)
    },

    /** 접수 번호로 한 번 조회한다. 가짜 서버가 접수한 번호만 알고, 다른 초대 코드의 번호는 찾지 못한다. */
    async checkQuestion(requestId, remainingToday, inviteCode) {
      await wait()
      assertInviteCode(inviteCode)
      const info: AcceptedInfo = { requestId, remainingToday }
      const record = findRecord(requestId, inviteCode)
      const response = interpret(record, info)
      if (response !== null) return response
      throw new ApiError('PROCESSING_TIMEOUT', PROCESSING_TIMEOUT_MESSAGE, null, info)
    },

    async submitFeedback(logId, feedback, inviteCode) {
      await wait()
      assertInviteCode(inviteCode)
      if (!knownLogIds.has(logId)) {
        throw new ApiError('QUESTION_NOT_FOUND', '질문을 찾을 수 없습니다.', 404)
      }
      if ((feedback.comment ?? '').length > FEEDBACK_COMMENT_MAX) {
        throw new ApiError('VALIDATION_FAILED', `의견은 ${FEEDBACK_COMMENT_MAX}자까지 쓸 수 있습니다.`, 400)
      }
      // 같은 질문에 다시 보내면 서버가 덮어쓰므로 가짜 서버도 항상 저장된 것으로 응답한다.
      return { saved: true }
    },

    async getCollectionSummary(inviteCode) {
      await wait()
      assertInviteCode(inviteCode)
      return structuredClone(COLLECTION_SUMMARY)
    },
  }
}
