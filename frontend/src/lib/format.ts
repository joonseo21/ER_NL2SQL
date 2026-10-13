const KST_DATE = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})

/** 시간대가 붙은 ISO 8601 시각을 한국 시간 기준 YYYY-MM-DD로 바꾼다. 값이 없거나 형식이 틀리면 null이다. */
export function formatDate(iso: string | null): string | null {
  if (iso === null) return null
  const time = Date.parse(iso)
  if (Number.isNaN(time)) return null
  return KST_DATE.format(new Date(time))
}

const KST_DATE_TIME = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

/** 한국 시간 기준 "YYYY-MM-DD HH:mm"으로 바꾼다. 값이 없거나 형식이 틀리면 null이다. */
export function formatDateTime(iso: string | null): string | null {
  if (iso === null) return null
  const time = Date.parse(iso)
  if (Number.isNaN(time)) return null
  const parts = Object.fromEntries(
    KST_DATE_TIME.formatToParts(new Date(time)).map((p) => [p.type, p.value]),
  )
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`
}

/** 걸린 시간(ms)을 "11.0초"처럼 표시한다. */
export function formatElapsed(ms: number): string {
  return `${(ms / 1000).toFixed(1)}초`
}

const SAMPLE_UNIT_LABELS: Record<string, string> = {
  participant_appearances: '참가자 출현',
  games: '경기',
  teams: '팀',
}

/** 표본의 집계 단위 이름. 모르는 단위는 값을 그대로 보여 준다. */
export function formatSampleUnit(unit: string | null): string {
  if (unit === null) return '단위 확인 불가'
  return SAMPLE_UNIT_LABELS[unit] ?? unit
}

export function formatCount(value: number): string {
  return value.toLocaleString('ko-KR')
}

const DECIMAL_STRING = /^-?\d+\.\d+$/
const INTEGER_STRING = /^-?\d+$/

export interface FormattedCell {
  /** 화면에 보이는 글자 */
  text: string
  /** 줄여서 보여 주었을 때 원래 값. 마우스를 올리면 볼 수 있게 한다. */
  full?: string
  /** 오른쪽 정렬할 숫자인지 */
  numeric: boolean
  /** 값이 없는(null) 칸인지 */
  missing: boolean
}

function trimDecimals(value: number): string {
  // 소수 둘째 자리까지 보여 주고 뒤쪽의 불필요한 0은 없앤다. (3.50 → 3.5, 5.00 → 5)
  return String(Number(value.toFixed(2)))
}

/**
 * 결과 표의 한 칸을 표시용으로 바꾼다. 소수는 정밀도를 지키기 위해 문자열로 오므로 숫자처럼 보이는 문자열의
 * 자릿수를 줄이고 원래 값은 full로 남긴다. 코드·ID가 섞여 있을 수 있어 정수에는 자릿점을 넣지 않는다.
 */
export function formatCell(cell: string | number | boolean | null): FormattedCell {
  if (cell === null) return { text: '-', numeric: false, missing: true }
  if (typeof cell === 'boolean') return { text: cell ? '예' : '아니오', numeric: false, missing: false }

  if (typeof cell === 'number') {
    if (Number.isInteger(cell)) return { text: String(cell), numeric: true, missing: false }
    const text = trimDecimals(cell)
    return { text, full: text === String(cell) ? undefined : String(cell), numeric: true, missing: false }
  }

  if (DECIMAL_STRING.test(cell)) {
    const text = trimDecimals(Number(cell))
    return { text, full: text === cell ? undefined : cell, numeric: true, missing: false }
  }
  if (INTEGER_STRING.test(cell)) return { text: cell, numeric: true, missing: false }
  return { text: cell, numeric: false, missing: false }
}

/** 패치의 수집 기간 문구. 한쪽이라도 확인되지 않으면 기간 정보가 없다고 표시한다. */
export function formatPeriod(first: string | null, last: string | null): string {
  const start = formatDate(first)
  const end = formatDate(last)
  if (start === null || end === null) return '기간 정보 없음'
  return start === end ? start : `${start} ~ ${end}`
}
