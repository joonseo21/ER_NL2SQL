import type { ConditionsResponse, QuestionResponse } from '../api'
import { COUNTED_RESPONSE_NOTE, mentionsUsage } from '../lib/usageNotes'
import { AnswerView } from './AnswerView'
import { ClarificationView } from './ClarificationView'
import { NoticeView } from './NoticeView'

interface Props {
  response: QuestionResponse
  /** 적용 조건의 기간·실험체 이름을 풀어 쓰는 데 쓴다. 불러오지 못했으면 null */
  options: ConditionsResponse | null
  /** 안내의 예시 질문이나 되묻기의 후보를 질문 입력란에 채울 때 호출한다. */
  onUseQuestion: (question: string) => void
}

/**
 * 응답 유형(kind)에 따라 답변·안내·되묻기를 서로 다른 모습으로 보여 준다.
 * 안내·되묻기는 답변이 아니어도 질문 횟수에 포함되므로 짧게 알린다. message에 이미 있으면 붙이지 않는다.
 */
export function ResultView({ response, options, onUseQuestion }: Props) {
  switch (response.kind) {
    case 'answer':
      return <AnswerView response={response} options={options} />
    case 'notice':
      return (
        <>
          <NoticeView response={response} onUseQuestion={onUseQuestion} />
          <UsageNote message={response.message} />
        </>
      )
    case 'clarification':
      return (
        <>
          <ClarificationView response={response} onUseQuestion={onUseQuestion} />
          <UsageNote message={response.message} />
        </>
      )
  }
}

function UsageNote({ message }: { message: string }) {
  if (mentionsUsage(message)) return null
  return <p className="muted small">{COUNTED_RESPONSE_NOTE}</p>
}
