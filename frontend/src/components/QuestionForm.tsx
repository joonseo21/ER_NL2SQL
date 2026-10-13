import type { FormEvent, KeyboardEvent } from 'react'
import { QUESTION_INPUT_ID } from '../lib/dom'
import { EXAMPLE_QUESTIONS } from '../lib/examples'

interface Props {
  value: string
  pending: boolean
  onChange: (value: string) => void
  onSubmit: (question: string) => void
}

export function QuestionForm({ value, pending, onChange, onSubmit }: Props) {
  const blank = value.trim() === ''

  function submit() {
    if (!blank && !pending) onSubmit(value)
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    submit()
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    // Ctrl+Enter(맥은 Cmd+Enter)로 보낸다. 일반 Enter는 줄바꿈이다.
    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
      event.preventDefault()
      submit()
    }
  }

  return (
    <section className="card" aria-labelledby="question-title">
      <h2 id="question-title">질문</h2>
      <form onSubmit={handleSubmit} className="stack">
        <label className="field">
          <span>궁금한 점을 한국어로 적어 주세요</span>
          <textarea
            id={QUESTION_INPUT_ID}
            value={value}
            rows={3}
            placeholder="예: 다이아몬드 티어에서 평균 순위가 가장 좋은 실험체는?"
            onChange={(e) => onChange(e.target.value)}
            onKeyDown={handleKeyDown}
            disabled={pending}
          />
        </label>

        <p className="muted small">
          실험체·아이템·특성은 <strong>게임 속 정식 이름</strong>으로 적어 주세요. 별명이나 줄임말은 알아보지
          못할 수 있습니다. 개인 전적 조회와 승패 예측·추천은 제공하지 않습니다.
        </p>

        <div>
          <p className="small label">예시 질문</p>
          <ul className="chips">
            {EXAMPLE_QUESTIONS.map((example) => (
              <li key={example}>
                <button type="button" className="chip" disabled={pending} onClick={() => onChange(example)}>
                  {example}
                </button>
              </li>
            ))}
          </ul>
        </div>

        <div className="actions">
          <button type="submit" disabled={blank || pending}>
            {pending ? '조회 중…' : '질문하기'}
          </button>
          <span className="muted small">Ctrl + Enter로도 보낼 수 있습니다.</span>
        </div>
      </form>
    </section>
  )
}
