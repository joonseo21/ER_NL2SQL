import type { AnswerResponse, ConditionsResponse } from '../api'
import { AppliedConditions } from './AppliedConditions'
import { EvidencePanel } from './EvidencePanel'
import { ResultTable } from './ResultTable'
import { SqlBlock } from './SqlBlock'

interface Props {
  response: AnswerResponse
  options: ConditionsResponse | null
}

export function AnswerView({ response, options }: Props) {
  return (
    <section className="card result result-answer" aria-label="답변">
      <p className="badge badge-answer">답변</p>

      {response.summary !== null ? (
        <p className="summary">{response.summary}</p>
      ) : (
        <p className="summary muted">
          요약을 만들지 못했습니다. 아래 표와 SQL은 그대로 실제 조회 결과입니다.
        </p>
      )}
      {response.message && <p>{response.message}</p>}

      {response.warnings.length > 0 && (
        <div className="warnings" role="note" aria-label="경고">
          <p className="warnings-title">확인할 점</p>
          <ul>
            {response.warnings.map((warning) => (
              <li key={warning}>{warning}</li>
            ))}
          </ul>
        </div>
      )}

      <AppliedConditions applied={response.applied_conditions} options={options} />

      <section className="section" aria-labelledby="table-title">
        <h3 id="table-title">결과</h3>
        <ResultTable
          columns={response.columns}
          rows={response.rows}
          groups={response.evidence?.groups ?? []}
        />
      </section>

      <EvidencePanel evidence={response.evidence} elapsedMs={response.elapsed_ms} />

      <SqlBlock sql={response.sql} />
    </section>
  )
}
