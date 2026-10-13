import type { ClarificationResponse } from '../api'
import { applyCandidate, categoryLabel } from '../lib/applied'

interface Props {
  response: ClarificationResponse
  onUseQuestion: (question: string) => void
}

/** 알아보지 못한 이름이 있어 되묻는다. 후보를 누르면 질문 속 이름을 바꿔 입력란에 채워 준다. */
export function ClarificationView({ response, onUseQuestion }: Props) {
  return (
    <section className="card result result-clarification" aria-label="되묻기">
      <p className="badge badge-clarification">되묻기</p>
      <p className="summary">{response.message}</p>

      {response.clarifications.map((item) => (
        <div key={item.term} className="clarify-item">
          <p className="small label">
            ‘{item.term}’ 대신 쓸 이름을 골라 주세요
          </p>
          {item.candidates.length === 0 ? (
            <p className="muted small">비슷한 이름을 찾지 못했습니다. 게임 속 정식 이름으로 다시 적어 주세요.</p>
          ) : (
            <ul className="chips">
              {item.candidates.map((candidate) => (
                <li key={`${candidate.category}-${candidate.code}`}>
                  <button
                    type="button"
                    className="chip"
                    onClick={() =>
                      onUseQuestion(applyCandidate(response.question, item.term, candidate.name))
                    }
                  >
                    {candidate.name}
                    <span className="chip-meta"> {categoryLabel(candidate.category)}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      ))}
    </section>
  )
}
