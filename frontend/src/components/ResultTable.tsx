import type { Cell, EvidenceGroup } from '../api'
import { formatCell, formatCount, formatSampleUnit } from '../lib/format'

interface Props {
  columns: string[]
  rows: Record<string, Cell>[]
  /** 행별 표본 근거. 비어 있으면 근거 열을 만들지 않는다. */
  groups: EvidenceGroup[]
}

/** 에이전트가 반환한 결과 행의 최대 개수 */
const MAX_ROWS = 200

/**
 * 결과 표다. 열 이름은 SQL의 별칭 그대로 보여 준다. 마지막 열에는 서버가 행마다 계산해 준 표본 수와 경고를
 * 붙여, 표본이 작은 행을 그 자리에서 알아볼 수 있게 한다.
 */
export function ResultTable({ columns, rows, groups }: Props) {
  const groupByRow = new Map(groups.map((g) => [g.row_index, g]))
  const hasEvidence = groups.length > 0

  return (
    <div>
      <div className="table-wrap" role="region" aria-label="결과 표" tabIndex={0}>
        <table>
          <caption className="sr-only">조회 결과 {rows.length}행</caption>
          <thead>
            <tr>
              {columns.map((column) => (
                <th key={column} scope="col">
                  {column}
                </th>
              ))}
              {hasEvidence && <th scope="col">표본 근거</th>}
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr>
                <td className="empty" colSpan={columns.length + (hasEvidence ? 1 : 0)}>
                  표시할 행이 없습니다.
                </td>
              </tr>
            )}
            {rows.map((row, index) => {
              const group = groupByRow.get(index)
              return (
                <tr key={index}>
                  {columns.map((column) => {
                    const cell = formatCell(row[column] ?? null)
                    return (
                      <td
                        key={column}
                        className={[cell.numeric ? 'num' : '', cell.missing ? 'missing' : '']
                          .filter(Boolean)
                          .join(' ')}
                        title={cell.full ?? (cell.missing ? '값 없음' : undefined)}
                      >
                        {cell.text}
                      </td>
                    )
                  })}
                  {hasEvidence && (
                    <td className="evidence-cell">
                      {group ? <RowEvidence group={group} /> : <span className="muted">-</span>}
                    </td>
                  )}
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      <p className="muted small">
        {rows.length}행{rows.length >= MAX_ROWS && ` · 결과는 최대 ${MAX_ROWS}행까지만 표시됩니다.`}
      </p>
    </div>
  )
}

function RowEvidence({ group }: { group: EvidenceGroup }) {
  return (
    <>
      <span>
        {group.sample_size === null
          ? '표본 확인 불가'
          : `${formatCount(group.sample_size)} ${formatSampleUnit(group.sample_unit)}`}
      </span>
      {group.warnings.map((warning) => (
        <span key={warning} className="row-warning">
          ⚠ {warning}
        </span>
      ))}
    </>
  )
}
