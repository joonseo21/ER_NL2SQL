import answerEmptyResult from './mocks/answer-empty-result.json'
import answerWithoutSummary from './mocks/answer-without-summary.json'
import answer from './mocks/answer.json'
import clarification from './mocks/clarification.json'
import notice from './mocks/notice.json'
import { ApiError } from './errors'
import type { AgentResponse, ConditionsResponse, ErApi, QuestionResponse } from './types'

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
  { id: 'notice', label: '안내' },
  { id: 'clarification', label: '되묻기' },
  { id: 'error-invite-limit', label: '오류: 코드별 한도 초과' },
  { id: 'error-global-limit', label: '오류: 전체 한도 초과' },
  { id: 'error-agent', label: '오류: 에이전트 연결 실패' },
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

const DAILY_LIMIT = 50

export function createMockApi(options: MockApiOptions = {}): ErApi {
  const delayMs = options.delayMs ?? 800
  const scenario = options.scenario ?? (() => mockControl.scenario)
  let remaining = options.initialRemaining ?? DAILY_LIMIT

  async function wait(): Promise<void> {
    if (delayMs > 0) await new Promise((resolve) => setTimeout(resolve, delayMs))
  }

  function assertInviteCode(code: string): void {
    if (code.trim() === '' || code === 'INVALID') {
      throw new ApiError('INVITE_INVALID', '초대 코드를 확인해 주세요.', 401)
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

    async askQuestion(request, inviteCode) {
      await wait()
      assertInviteCode(inviteCode)
      if (request.question.trim() === '') {
        throw new ApiError('VALIDATION_FAILED', '질문을 입력해 주세요.', 400)
      }

      const id = scenario()
      switch (id) {
        case 'error-invite-limit':
          throw new ApiError('INVITE_LIMIT_EXCEEDED', '오늘 질문 횟수를 모두 사용했습니다.', 429)
        case 'error-global-limit':
          throw new ApiError('GLOBAL_LIMIT_EXCEEDED', '오늘 전체 질문 횟수를 모두 사용했습니다.', 429)
        case 'error-agent':
          throw new ApiError('AGENT_UNAVAILABLE', '에이전트에 연결하지 못했습니다.', 503)
      }

      // 안내·되묻기도 횟수를 소비한다(웹 API 명세).
      remaining = Math.max(0, remaining - 1)
      const sample = structuredClone(SAMPLES[id])
      const response: QuestionResponse = {
        ...sample,
        question: request.question,
        log_id: globalThis.crypto.randomUUID(),
        remaining_today: remaining,
      }
      return response
    },
  }
}
