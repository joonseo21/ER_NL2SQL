import { describe, expect, it } from 'vitest'
import { applyCandidate, describeApplied } from './applied'
import {
  formatCell,
  formatCount,
  formatDateTime,
  formatElapsed,
  formatSampleUnit,
} from './format'

describe('formatCell', () => {
  it('긴 소수 문자열은 둘째 자리까지 줄이고 원래 값을 남긴다', () => {
    expect(formatCell('14.6730788908216515')).toEqual({
      text: '14.67',
      full: '14.6730788908216515',
      numeric: true,
      missing: false,
    })
  })

  it('이미 짧은 소수는 그대로 두고 full을 만들지 않는다', () => {
    expect(formatCell('3.97')).toMatchObject({ text: '3.97', full: undefined, numeric: true })
  })

  it('뒤쪽의 불필요한 0은 없앤다', () => {
    expect(formatCell('5.00').text).toBe('5')
    expect(formatCell('3.50').text).toBe('3.5')
  })

  it('정수는 자릿점 없이 그대로 보여 준다(코드·ID일 수 있다)', () => {
    expect(formatCell(101101)).toMatchObject({ text: '101101', numeric: true })
    expect(formatCell('9773')).toMatchObject({ text: '9773', numeric: true })
  })

  it('숫자 타입의 소수도 줄인다', () => {
    expect(formatCell(0.123456)).toMatchObject({ text: '0.12', full: '0.123456' })
  })

  it('null은 값 없음으로, 불리언은 예·아니오로 보여 준다', () => {
    expect(formatCell(null)).toEqual({ text: '-', numeric: false, missing: true })
    expect(formatCell(true).text).toBe('예')
    expect(formatCell(false).text).toBe('아니오')
  })

  it('이름 같은 문자열은 그대로 두고 숫자로 취급하지 않는다', () => {
    expect(formatCell('아드리아나')).toMatchObject({ text: '아드리아나', numeric: false })
    expect(formatCell('v5.0-beta')).toMatchObject({ text: 'v5.0-beta', numeric: false })
  })
})

describe('시각·단위 표시', () => {
  it('시각을 한국 시간 "YYYY-MM-DD HH:mm"으로 바꾼다', () => {
    expect(formatDateTime('2026-10-12T05:51:30.242689+00:00')).toBe('2026-10-12 14:51')
    expect(formatDateTime('2026-10-11T15:00:00+00:00')).toBe('2026-10-12 00:00')
    expect(formatDateTime(null)).toBeNull()
    expect(formatDateTime('어제')).toBeNull()
  })

  it('걸린 시간과 개수를 표시한다', () => {
    expect(formatElapsed(11010)).toBe('11.0초')
    expect(formatCount(9773)).toBe('9,773')
  })

  it('집계 단위를 한국어로 바꾸고 모르는 단위는 그대로 둔다', () => {
    expect(formatSampleUnit('participant_appearances')).toBe('참가자 출현')
    expect(formatSampleUnit('games')).toBe('경기')
    expect(formatSampleUnit('teams')).toBe('팀')
    expect(formatSampleUnit(null)).toBe('단위 확인 불가')
    expect(formatSampleUnit('rounds')).toBe('rounds')
  })
})

describe('describeApplied', () => {
  const options = {
    patches: [{ major: 5, minor: 0, first_match: '2026-09-24T03:10:00+09:00', last_match: '2026-10-12T14:51:30+09:00' }],
    tiers: [],
    characters: [{ code: 17, name: '아드리아나' }],
  }

  it('실제 적용된 버전, 기간, 티어, 실험체 이름을 풀어 쓴다', () => {
    const summary = describeApplied(
      {
        season_id: 41,
        conditions: {
          patch: { mode: 'exact', versions: [{ major: 5, minor: 0 }] },
          tier: '다이아몬드',
          tier_division: null,
          character_code: 17,
        },
      },
      options,
    )
    expect(summary).toEqual({
      season: '시즌 41',
      patch: '5.0 (2026-09-24 ~ 2026-10-12)',
      tier: '다이아몬드',
      character: '아드리아나',
    })
  })

  it('조건이 없으면 전체로, 목록에 없는 값은 코드로 보여 준다', () => {
    const summary = describeApplied(
      {
        season_id: 41,
        conditions: {
          patch: { mode: 'exact', versions: [{ major: 4, minor: 0 }, { major: 3, minor: 1 }] },
          tier: null,
          tier_division: null,
          character_code: 99,
        },
      },
      options,
    )
    expect(summary.patch).toBe('4.0, 3.1')
    expect(summary.tier).toBe('전체 티어')
    expect(summary.character).toBe('실험체 코드 99')
  })

  it('전체 패치와 최신 패치 모드를 표시한다', () => {
    const base = { tier: null, tier_division: null, character_code: null }
    const all = describeApplied(
      { season_id: 41, conditions: { ...base, patch: { mode: 'all', versions: [] } } },
      null,
    )
    const latest = describeApplied(
      { season_id: 41, conditions: { ...base, patch: { mode: 'latest', versions: [] } } },
      null,
    )
    expect(all.patch).toBe('전체 패치')
    expect(latest.patch).toBe('최신 패치')
  })
})

describe('applyCandidate', () => {
  it('질문 속 알아보지 못한 이름을 후보로 바꾼다', () => {
    expect(applyCandidate('아드리나의 최신 패치 승률은?', '아드리나', '아드리아나')).toBe(
      '아드리아나의 최신 패치 승률은?',
    )
  })

  it('질문에 그 이름이 없으면 그대로 둔다', () => {
    expect(applyCandidate('승률은?', '아드리나', '아드리아나')).toBe('승률은?')
  })
})
