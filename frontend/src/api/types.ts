// 웹 API 계약(docs/contracts/web-api.md)과 에이전트 응답 계약(docs/contracts/agent-http.md)의 형식이다.
// 계약이 바뀌면 이 파일만 고친다. 필드 이름은 서버가 보내는 그대로(snake_case) 둔다.

// ---------- 질문 요청 ----------

export type PatchMode = 'latest' | 'exact' | 'all'

export interface PatchVersion {
  major: number
  minor: number
}

export interface PatchCondition {
  mode: PatchMode
  /** mode가 exact일 때만 1개 이상, 나머지는 빈 배열이다. */
  versions: PatchVersion[]
}

export interface QuestionConditions {
  patch: PatchCondition
  /** 한국어 티어 이름. 선택하지 않으면 null(전체 티어) */
  tier: string | null
  /** tier가 있을 때만 쓴다. 이번 화면에서는 고르지 않으므로 항상 null */
  tier_division: number | null
  /** 선택하지 않으면 null */
  character_code: number | null
}

export interface QuestionRequest {
  /** 공백만 있는 문자열은 허용되지 않는다. */
  question: string
  /** 생략하면 최신 패치·전체 티어다. 명시적인 null은 허용되지 않는다. */
  conditions?: QuestionConditions
}

// ---------- 질문 응답 ----------

export type SampleUnit = 'participant_appearances' | 'games' | 'teams'

export interface EvidenceGroup {
  /** rows의 0부터 시작하는 인덱스 */
  row_index: number
  sample_size: number | null
  sample_unit: SampleUnit | null
  warnings: string[]
}

export interface Evidence {
  /** 결과 집단이 하나이고 근거가 확인된 경우만 채워진다. */
  sample_size: number | null
  sample_unit: SampleUnit | null
  /** 시간대가 붙은 ISO 8601 */
  data_as_of: string | null
  groups: EvidenceGroup[]
}

export interface AppliedConditions {
  season_id: number
  /** latest는 실제 버전으로 풀려서 온다. */
  conditions: QuestionConditions
}

export type CandidateCategory = 'character' | 'weapon' | 'item' | 'trait' | 'tactical'

export interface ClarificationCandidate {
  category: CandidateCategory
  code: number
  name: string
}

export interface Clarification {
  /** 알아보지 못한 이름 */
  term: string
  candidates: ClarificationCandidate[]
}

/** 결과 표의 한 칸. 소수는 정밀도를 지키려고 문자열로 온다. */
export type Cell = string | number | boolean | null

interface ResponseBase {
  question: string
  /** 질문 기록과 후기를 잇는 ID */
  log_id: string
  elapsed_ms: number
  columns: string[]
  rows: Record<string, Cell>[]
  examples: string[]
  /** 모든 유형에 있고, 되묻기가 아니면 빈 배열이다. */
  clarifications: Clarification[]
  evidence: Evidence | null
  warnings: string[]
  /** 예약 필드. 지금은 항상 null이며 화면에서 쓰지 않는다. */
  chart?: null
  conversation_context?: null
}

export interface AnswerResponse extends ResponseBase {
  kind: 'answer'
  sql: string
  applied_conditions: AppliedConditions
  /** 요약 생성이 실패하면 null이다. 표와 SQL은 그대로 보여 줘야 한다. */
  summary: string | null
  message: string | null
}

export interface NoticeResponse extends ResponseBase {
  kind: 'notice'
  sql: null
  applied_conditions: AppliedConditions | null
  summary: null
  message: string
}

export interface ClarificationResponse extends ResponseBase {
  kind: 'clarification'
  sql: null
  applied_conditions: AppliedConditions | null
  summary: null
  message: string
}

/** 에이전트가 만든 응답. kind로 세 유형을 구분한다. */
export type AgentResponse = AnswerResponse | NoticeResponse | ClarificationResponse

/** POST /api/questions의 응답: 에이전트 응답 그대로 + 오늘 남은 횟수 */
export type QuestionResponse = AgentResponse & { remaining_today: number }

// ---------- 그 밖의 엔드포인트 ----------

export interface InviteVerifyResponse {
  valid: boolean
  remaining_today: number
}

export interface PatchOption {
  major: number
  minor: number
  /** 이 패치로 수집된 경기의 최초·최종 시각. 실제 패치 출시·종료일이 아니다. 확인되지 않으면 null */
  first_match: string | null
  last_match: string | null
}

export interface CharacterOption {
  code: number
  name: string
}

export interface ConditionsResponse {
  /** 버전 내림차순(최신 패치가 먼저) */
  patches: PatchOption[]
  tiers: string[]
  characters: CharacterOption[]
}

// ---------- API 인터페이스 ----------

/**
 * 화면이 쓰는 서버 호출의 전부다. 가짜 응답(mock.ts)과 실제 호출(http.ts)이
 * 같은 모양을 가지므로 화면 코드는 어느 쪽인지 알 필요가 없다.
 * 초대 코드 확인을 뺀 모든 호출은 초대 코드를 헤더로 보낸다.
 */
export interface ErApi {
  verifyInvite(code: string): Promise<InviteVerifyResponse>
  getConditions(inviteCode: string): Promise<ConditionsResponse>
  askQuestion(request: QuestionRequest, inviteCode: string): Promise<QuestionResponse>
}
