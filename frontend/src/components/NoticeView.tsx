import type { NoticeResponse } from '../api'

interface Props {
  response: NoticeResponse
  /** 예시 질문을 누르면 질문 입력란에 채운다. */
  onUseQuestion: (question: string) => void
}

/** 답을 만들지 못했거나 답할 수 없는 질문일 때의 안내다. 표·SQL은 없다. */
export function NoticeView({ response, onUseQuestion }: Props) {
  return (
    <section className="card result result-notice" aria-label="안내">
      <p className="badge badge-notice">안내</p>
      <p className="summary">{response.message}</p>

      {response.examples.length > 0 && (
        <div>
          <p className="small label">이런 질문은 할 수 있습니다</p>
          <ul className="chips">
            {response.examples.map((example) => (
              <li key={example}>
                <button type="button" className="chip" onClick={() => onUseQuestion(example)}>
                  {example}
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}

      {response.warnings.length > 0 && (
        <ul className="plain">
          {response.warnings.map((warning) => (
            <li key={warning} className="muted small">
              {warning}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
