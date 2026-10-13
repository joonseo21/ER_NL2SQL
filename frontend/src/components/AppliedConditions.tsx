import type { AppliedConditions as Applied, ConditionsResponse } from '../api'
import { describeApplied } from '../lib/applied'

interface Props {
  applied: Applied
  options: ConditionsResponse | null
}

/** 서버가 실제로 적용한 조건이다. 화면에서 고른 조건과 질문 속 조건 중 최종 결과를 보여 준다. */
export function AppliedConditions({ applied, options }: Props) {
  const summary = describeApplied(applied, options)
  return (
    <section className="section" aria-labelledby="applied-title">
      <h3 id="applied-title">적용된 조건</h3>
      <dl className="kv">
        <dt>시즌</dt>
        <dd>{summary.season}</dd>
        <dt>패치</dt>
        <dd>{summary.patch}</dd>
        <dt>티어</dt>
        <dd>{summary.tier}</dd>
        <dt>실험체</dt>
        <dd>{summary.character}</dd>
      </dl>
    </section>
  )
}
