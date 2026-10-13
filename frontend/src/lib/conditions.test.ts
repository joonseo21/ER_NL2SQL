import { describe, expect, it } from 'vitest'
import {
  DEFAULT_SELECTION,
  formatVersion,
  isDefaultSelection,
  latestPatch,
  toRequestConditions,
} from './conditions'

describe('toRequestConditions', () => {
  it('아무것도 고르지 않으면 conditions를 생략한다(undefined)', () => {
    expect(toRequestConditions(DEFAULT_SELECTION)).toBeUndefined()
  })

  it('티어만 고르면 패치는 latest, 나머지는 null로 보낸다', () => {
    expect(toRequestConditions({ ...DEFAULT_SELECTION, tier: '다이아몬드' })).toEqual({
      patch: { mode: 'latest', versions: [] },
      tier: '다이아몬드',
      tier_division: null,
      character_code: null,
    })
  })

  it('패치를 고르면 exact와 그 버전 하나를 보낸다', () => {
    expect(toRequestConditions({ ...DEFAULT_SELECTION, patch: { major: 4, minor: 0 } })).toEqual({
      patch: { mode: 'exact', versions: [{ major: 4, minor: 0 }] },
      tier: null,
      tier_division: null,
      character_code: null,
    })
  })

  it('실험체만 고르면 character_code를 채운다', () => {
    expect(toRequestConditions({ ...DEFAULT_SELECTION, characterCode: 17 })?.character_code).toBe(17)
  })

  it('세 조건을 모두 고를 수 있다', () => {
    expect(
      toRequestConditions({ patch: { major: 5, minor: 0 }, tier: '미스릴 이상', characterCode: 66 }),
    ).toEqual({
      patch: { mode: 'exact', versions: [{ major: 5, minor: 0 }] },
      tier: '미스릴 이상',
      tier_division: null,
      character_code: 66,
    })
  })
})

describe('보조 함수', () => {
  it('기본 선택을 구분한다', () => {
    expect(isDefaultSelection(DEFAULT_SELECTION)).toBe(true)
    expect(isDefaultSelection({ ...DEFAULT_SELECTION, tier: '플래티넘' })).toBe(false)
  })

  it('버전은 major.minor로 표시한다', () => {
    expect(formatVersion({ major: 5, minor: 0 })).toBe('5.0')
  })

  it('최신 패치는 목록의 첫 항목이고 목록이 비면 null이다', () => {
    const patches = [
      { major: 5, minor: 0, first_match: null, last_match: null },
      { major: 4, minor: 0, first_match: null, last_match: null },
    ]
    expect(latestPatch({ patches, tiers: [], characters: [] })).toEqual({ major: 5, minor: 0 })
    expect(latestPatch({ patches: [], tiers: [], characters: [] })).toBeNull()
  })
})
