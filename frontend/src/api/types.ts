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

/**
 * 화면이 받는 질문 결과: 에이전트 응답(상태 조회의 result) + 오늘 남은 횟수.
 * 서버는 접수 응답(POST)에 remaining_today를, 상태 조회(GET)에 result를 따로 주므로
 * ErApi.askQuestion이 둘을 합쳐 이 형태로 돌려준다.
 */
export type QuestionResponse = AgentResponse & { remaining_today: number }

// ---------- 질문 접수와 상태 조회 ----------

/**
 * 질문은 먼저 접수하고(POST, HTTP 202) 접수 번호로 결과를 조회(GET)한다.
 * accepted: 접수됨, processing: 처리 중, completed: 처리 완료(result 있음), failed: 실패(error_code·message 있음)
 */
export type QuestionStatus = 'accepted' | 'processing' | 'completed' | 'failed'

/** POST /api/questions의 접수 응답 */
export interface QuestionAcceptedResponse {
  /** Spring의 접수 식별자. 상태 조회에 쓴다. 후기에 쓰는 result.log_id와 다르다. */
  request_id: string
  status: 'accepted'
  message: string
  remaining_today: number
}

/** GET /api/questions/{requestId}의 응답 */
export interface QuestionStatusResponse {
  request_id: string
  status: QuestionStatus
  /** completed일 때만 있다. 안내·되묻기도 completed다. */
  result: AgentResponse | null
  /** failed일 때의 오류 코드. 값 목록은 웹 API 계약의 "상태 조회와 비동기 실패"에 있다. */
  error_code: string | null
  message: string | null
}

// ---------- 후기 ----------

export type FeedbackRating = 'like' | 'dislike'

export interface FeedbackRequest {
  rating: FeedbackRating
  /** 최대 500자. 생략하거나 null이면 의견 없음이며, 다시 보낼 때 생략하면 이전 의견도 지워진다. */
  comment?: string | null
}

export interface FeedbackResponse {
  saved: boolean
}

/** 후기 의견의 최대 길이(사용자가 500자로 확정) */
export const FEEDBACK_COMMENT_MAX = 500

// ---------- 이용자용 수집 현황 ----------

/**
 * 티어별 수집 규모. 두 수치는 단위가 다르다.
 * - users: 그 티어에 속한 이용자 수. 이용자의 가장 최근 티어로 세어 한 사람은 한 티어에만 들어간다.
 * - participant_appearances: 참가 기록 수. 경기 수나 서로 다른 이용자 수가 아니라 그 티어 참가자의 출전 횟수다.
 */
export interface CollectionTierRow {
  /** 한국어 티어 이름. 티어를 확인하지 못한 이용자·참가 기록을 묶은 항목은 null이다. */
  tier: string | null
  users: number
  participant_appearances: number
}

/**
 * GET /api/collection/summary의 응답(웹 API 계약 "이용자용 수집 현황", 2026-10-13 확정).
 * 서버가 같은 읽기 시점의 결과를 5분간 보관하므로 조건 목록과 최대 5분 차이가 날 수 있다.
 */
export interface CollectionSummaryResponse {
  /** 현재 시즌 랭크 스쿼드의 총 경기 수(경기 단위) */
  total_games: number
  /** 알려진 티어는 자료가 없어도 0으로 포함되고, 티어를 확인하지 못한 항목은 tier가 null이다. */
  by_tier: CollectionTierRow[]
  /** 서비스 가능한 패치. 수집된 경기가 있는 버전만, 버전 내림차순이며 조건 목록과 같은 형식이다. */
  patches: PatchOption[]
  /** 가장 최근에 수집한 경기의 수집 시각. 확인할 수 없으면 null */
  latest_collected_at: string | null
  /** 이 응답을 집계하기 시작한 서버 시각 */
  snapshot_at: string
  /** 서버가 이 응답을 보관하는 만료 시각. 그 전에는 같은 결과가 돌아온다. */
  cached_until: string
}

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
  /**
   * 질문을 접수하고 결과가 나올 때까지 기다려 돌려준다. 접수·조회 반복은 구현이 맡으므로 호출하는 쪽은
   * 한 번의 호출로 본다. onStatus는 접수·처리 중처럼 진행 상태가 바뀔 때마다 불린다.
   * 처리 실패(failed)와 대기 시간 초과는 ApiError로 던진다.
   */
  askQuestion(
    request: QuestionRequest,
    inviteCode: string,
    onStatus?: (status: QuestionStatus) => void,
  ): Promise<QuestionResponse>
  /**
   * 대기 시간이 지나 자동 조회를 멈춘 질문의 결과를 한 번 다시 확인한다. 질문을 다시 보내지 않는다.
   * 아직 처리 중이면 PROCESSING_TIMEOUT을 다시 던진다.
   */
  checkQuestion(requestId: string, remainingToday: number, inviteCode: string): Promise<QuestionResponse>
  /** 답변(result.log_id)에 후기를 보낸다. 같은 질문에 다시 보내면 이전 후기를 덮어쓴다. */
  submitFeedback(logId: string, feedback: FeedbackRequest, inviteCode: string): Promise<FeedbackResponse>
  getCollectionSummary(inviteCode: string): Promise<CollectionSummaryResponse>
}
