import type { Evidence } from '../api'
import { formatCount, formatDateTime, formatElapsed, formatSampleUnit } from '../lib/format'

interface Props {
  evidence: Evidence | null
  elapsedMs: number
}

/** 이 답이 어떤 데이터에 근거하는지 보여 준다. 확인하지 못한 값은 0이나 빈칸이 아니라 "확인 불가"로 쓴다. */
export function EvidencePanel({ evidence, elapsedMs }: Props) {
  const dataAsOf = evidence ? formatDateTime(evidence.data_as_of) : null

  let sample: string
  if (evidence === null) {
    sample = '확인 불가'
  } else if (evidence.sample_size !== null) {
    sample = `${formatCount(evidence.sample_size)} ${formatSampleUnit(evidence.sample_unit)}`
  } else if (evidence.groups.length > 1) {
    sample = '집단마다 다릅니다. 표의 "표본 근거" 열을 확인해 주세요.'
  } else {
    sample = '확인 불가'
  }

  return (
    <section className="section" aria-labelledby="evidence-title">
      <h3 id="evidence-title">데이터 근거</h3>
      <dl className="kv">
        <dt>데이터 기준 시각</dt>
        <dd>{dataAsOf ? `${dataAsOf} (한국 시간)` : '확인 불가'}</dd>
        <dt>표본</dt>
        <dd>{sample}</dd>
        <dt>조회 소요 시간</dt>
        <dd>{formatElapsed(elapsedMs)}</dd>
      </dl>
    </section>
  )
}
