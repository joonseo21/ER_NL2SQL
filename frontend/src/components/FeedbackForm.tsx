import { useState } from 'react'
import type { FormEvent } from 'react'
import { FEEDBACK_COMMENT_MAX, asApiError } from '../api'
import type { ErApi, FeedbackRating } from '../api'

interface Props {
  api: ErApi
  inviteCode: string
  /** 후기를 연결할 질문 기록 식별자. 접수 번호(request_id)가 아니라 결과의 log_id다. */
  logId: string
  /** 서버가 초대 코드를 거절하면 첫 화면으로 돌아가기 위해 호출한다. */
  onInviteInvalid: () => void
}

type SendState =
  | { status: 'idle' }
  | { status: 'sending' }
  | { status: 'saved' }
  | { status: 'error'; message: string }

/**
 * 답변마다 좋아요·싫어요와 한 줄 의견을 받는 폼이다. 같은 답변에 다시 보내면 서버가 이전 후기를 덮어쓰므로
 * 보낸 뒤에도 폼을 그대로 두고 다시 보낼 수 있게 한다. 결과가 바뀌면 부모가 key로 새 폼을 만든다.
 */
export function FeedbackForm({ api, inviteCode, logId, onInviteInvalid }: Props) {
  const [rating, setRating] = useState<FeedbackRating | null>(null)
  const [comment, setComment] = useState('')
  const [send, setSend] = useState<SendState>({ status: 'idle' })

  const sending = send.status === 'sending'

  function change<T>(setter: (value: T) => void, value: T) {
    setter(value)
    // 고친 내용은 아직 저장되지 않았으므로 "보냈습니다" 표시를 지운다.
    if (send.status !== 'sending') setSend({ status: 'idle' })
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (rating === null || sending) return

    setSend({ status: 'sending' })
    try {
      const trimmed = comment.trim()
      await api.submitFeedback(
        logId,
        // 의견이 비어 있으면 생략한다. 생략하면 서버가 이전 의견도 비운다.
        trimmed === '' ? { rating } : { rating, comment: trimmed },
        inviteCode,
      )
      setSend({ status: 'saved' })
    } catch (e) {
      const error = asApiError(e)
      if (error.code === 'INVITE_INVALID') onInviteInvalid()
      else setSend({ status: 'error', message: error.message })
    }
  }

  return (
    <section className="card feedback" aria-labelledby="feedback-title">
      <h3 id="feedback-title">이 답변은 어땠나요?</h3>
      <form onSubmit={handleSubmit} className="stack">
        <div className="rating" role="group" aria-label="평가">
          <button
            type="button"
            className={`rating-button${rating === 'like' ? ' selected' : ''}`}
            aria-pressed={rating === 'like'}
            disabled={sending}
            onClick={() => change(setRating, 'like')}
          >
            <span aria-hidden="true">👍</span> 좋아요
          </button>
          <button
            type="button"
            className={`rating-button${rating === 'dislike' ? ' selected' : ''}`}
            aria-pressed={rating === 'dislike'}
            disabled={sending}
            onClick={() => change(setRating, 'dislike')}
          >
            <span aria-hidden="true">👎</span> 싫어요
          </button>
        </div>

        <label className="field">
          <span>한 줄 의견 (선택)</span>
          <input
            type="text"
            value={comment}
            maxLength={FEEDBACK_COMMENT_MAX}
            placeholder="예: 표가 보기 좋았어요 / 질문과 다른 답이 나왔어요"
            disabled={sending}
            onChange={(e) => change(setComment, e.target.value)}
          />
          <span className="muted small counter">
            {comment.length} / {FEEDBACK_COMMENT_MAX}
          </span>
        </label>

        <div className="actions">
          <button type="submit" disabled={rating === null || sending}>
            {sending ? '보내는 중…' : '후기 보내기'}
          </button>
          {rating === null && send.status === 'idle' && (
            <span className="muted small">좋아요 또는 싫어요를 먼저 골라 주세요.</span>
          )}
        </div>

        {send.status === 'saved' && (
          <p role="status" className="saved-text">
            후기를 보냈습니다. 다시 보내면 이전 후기를 덮어씁니다.
          </p>
        )}
        {send.status === 'error' && (
          <p role="alert" className="error-text">
            {send.message}
          </p>
        )}
      </form>
    </section>
  )
}
