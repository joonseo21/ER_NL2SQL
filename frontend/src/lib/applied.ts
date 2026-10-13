import type { AppliedConditions, CandidateCategory, ConditionsResponse } from '../api/types'
import { formatVersion } from './conditions'
import { formatPeriod } from './format'

export interface AppliedSummary {
  season: string
  patch: string
  tier: string
  character: string
}

/**
 * 서버가 실제로 적용했다고 알려 준 조건을 화면용 문구로 바꾼다. latest는 서버가 실제 버전으로 풀어서 주지만
 * 혹시 남아 있으면 그대로 "최신 패치"로 표시한다. 기간과 실험체 이름은 조건 목록에서 찾고, 없으면 코드를 보여 준다.
 */
export function describeApplied(
  applied: AppliedConditions,
  options: ConditionsResponse | null,
): AppliedSummary {
  const { patch, tier, tier_division, character_code } = applied.conditions

  let patchText: string
  if (patch.mode === 'all') {
    patchText = '전체 패치'
  } else if (patch.mode === 'latest' || patch.versions.length === 0) {
    patchText = '최신 패치'
  } else {
    patchText = patch.versions
      .map((v) => {
        const known = options?.patches.find((p) => p.major === v.major && p.minor === v.minor)
        const period = known ? ` (${formatPeriod(known.first_match, known.last_match)})` : ''
        return `${formatVersion(v)}${period}`
      })
      .join(', ')
  }

  let tierText = tier ?? '전체 티어'
  if (tier !== null && tier_division !== null) tierText += ` (세부 구간 ${tier_division})`

  let characterText = '전체 실험체'
  if (character_code !== null) {
    const known = options?.characters.find((c) => c.code === character_code)
    characterText = known ? known.name : `실험체 코드 ${character_code}`
  }

  return {
    season: `시즌 ${applied.season_id}`,
    patch: patchText,
    tier: tierText,
    character: characterText,
  }
}

const CATEGORY_LABELS: Record<CandidateCategory, string> = {
  character: '실험체',
  weapon: '무기',
  item: '아이템',
  trait: '특성',
  tactical: '전술 스킬',
}

export function categoryLabel(category: CandidateCategory): string {
  return CATEGORY_LABELS[category]
}

/** 되묻기에서 후보를 고르면 질문 속 알아보지 못한 이름을 후보로 바꾼 질문을 만든다. 이름이 없으면 그대로 둔다. */
export function applyCandidate(question: string, term: string, name: string): string {
  return term === '' ? question : question.split(term).join(name)
}
