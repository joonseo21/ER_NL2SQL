import type { ApiError, ApiErrorCode } from '../api'

/**
 * 서버 message는 그대로 보여 주고, 코드별로 이용자가 할 수 있는 일을 덧붙인다.
 * 처리 실패 코드(AGENT_* 등)는 서버 message에 안내가 이미 들어 있어 따로 덧붙이지 않는다.
 */
const HINTS: Partial<Record<ApiErrorCode, string>> = {
  INVITE_LIMIT_EXCEEDED: '질문 횟수는 한국 시간 자정에 초기화됩니다.',
  GLOBAL_LIMIT_EXCEEDED: '서비스 전체의 하루 한도입니다. 한국 시간 자정 이후에 다시 이용해 주세요.',
  AGENT_UNAVAILABLE: '다른 질문을 처리 중이거나 일시적인 장애일 수 있습니다. 잠시 뒤 다시 시도해 주세요.',
  STORAGE_BUSY: '요청이 몰려 있습니다. 잠시 뒤 다시 시도해 주세요.',
  VALIDATION_FAILED: '질문과 조건을 확인해 주세요.',
  TIMEOUT: '요청이 서버에 도착했는지 알 수 없습니다. 같은 요청을 바로 반복해서 보내지 마세요.',
  PROCESSING_TIMEOUT:
    '질문은 서버에서 계속 처리되고 있을 수 있습니다. 같은 질문을 다시 보내지 말고 결과를 다시 확인해 주세요.',
}

interface Props {
  error: ApiError
  /** 있으면 버튼을 보여 준다. 조건 목록 다시 불러오기, 결과 다시 확인에 쓴다. */
  onRetry?: () => void
  retryLabel?: string
}

export function ErrorNotice({ error, onRetry, retryLabel = '다시 시도' }: Props) {
  const hint = HINTS[error.code]
  return (
    <section className="card error" role="alert">
      <p>
        <strong>{error.message}</strong>
      </p>
      {hint && <p className="muted small">{hint}</p>}
      {error.accepted && (
        <p className="muted small">이 질문은 접수되어 오늘 질문 횟수에 포함되었습니다.</p>
      )}
      {onRetry && (
        <button type="button" onClick={onRetry}>
          {retryLabel}
        </button>
      )}
    </section>
  )
}
