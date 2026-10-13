import type { ConditionsResponse, PatchVersion, QuestionConditions } from '../api/types'

/** 화면에서 고른 조건. 패치·티어·실험체를 하나씩만 고른다(공개 시점의 범위). */
export interface ConditionSelection {
  /** null이면 최신 패치(기본) */
  patch: PatchVersion | null
  /** 빈 문자열이면 전체 티어(기본) */
  tier: string
  /** null이면 실험체 제한 없음 */
  characterCode: number | null
}

export const DEFAULT_SELECTION: ConditionSelection = { patch: null, tier: '', characterCode: null }

export function isDefaultSelection(selection: ConditionSelection): boolean {
  return selection.patch === null && selection.tier === '' && selection.characterCode === null
}

/**
 * 선택을 질문 요청의 conditions로 바꾼다.
 * 아무것도 고르지 않았으면 undefined를 돌려준다. 서버는 conditions를 생략하면 최신 패치·전체 티어를
 * 적용하고 명시적인 null은 거부하기 때문이다.
 */
export function toRequestConditions(selection: ConditionSelection): QuestionConditions | undefined {
  if (isDefaultSelection(selection)) return undefined
  return {
    patch: selection.patch
      ? { mode: 'exact', versions: [{ major: selection.patch.major, minor: selection.patch.minor }] }
      : { mode: 'latest', versions: [] },
    tier: selection.tier === '' ? null : selection.tier,
    tier_division: null,
    character_code: selection.characterCode,
  }
}

export function formatVersion(version: PatchVersion): string {
  return `${version.major}.${version.minor}`
}

/** 조건 목록의 첫 항목이 최신 패치다(서버가 버전 내림차순으로 준다). */
export function latestPatch(options: ConditionsResponse): PatchVersion | null {
  const first = options.patches[0]
  return first ? { major: first.major, minor: first.minor } : null
}
