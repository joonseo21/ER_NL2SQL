import { describe, expect, it } from 'vitest'
import { formatDate, formatPeriod } from './format'

describe('formatDate', () => {
  it('한국 시간 기준 날짜로 바꾼다', () => {
    // UTC로는 9월 30일 16시지만 한국 시간으로는 10월 1일 01시다.
    expect(formatDate('2026-09-30T16:00:00+00:00')).toBe('2026-10-01')
    expect(formatDate('2026-10-12T14:51:30+09:00')).toBe('2026-10-12')
  })

  it('null이거나 형식이 틀리면 null이다', () => {
    expect(formatDate(null)).toBeNull()
    expect(formatDate('어제')).toBeNull()
  })
})

describe('formatPeriod', () => {
  it('시작과 끝을 물결표로 잇는다', () => {
    expect(formatPeriod('2026-09-24T03:10:00+09:00', '2026-10-12T14:51:30+09:00')).toBe(
      '2026-09-24 ~ 2026-10-12',
    )
  })

  it('같은 날이면 하루만 표시한다', () => {
    expect(formatPeriod('2026-10-12T01:00:00+09:00', '2026-10-12T23:00:00+09:00')).toBe('2026-10-12')
  })

  it('한쪽이라도 없으면 기간 정보 없음이다', () => {
    expect(formatPeriod(null, null)).toBe('기간 정보 없음')
    expect(formatPeriod('2026-10-12T01:00:00+09:00', null)).toBe('기간 정보 없음')
  })
})
