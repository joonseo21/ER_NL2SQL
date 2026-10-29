import { useState } from 'react'

interface Props {
  requestId: string
}

type CopyState = 'idle' | 'copied' | 'failed'

/**
 * 접수 번호를 보여 주고 복사할 수 있게 한다. 탭을 닫은 뒤 초대 코드를 다시 입력하고 이 번호로
 * 결과를 확인할 수 있다. 클립보드를 쓸 수 없으면 번호를 직접 선택해 복사하도록 안내한다.
 */
export function RequestIdBox({ requestId }: Props) {
  const [copy, setCopy] = useState<CopyState>('idle')

  async function handleCopy() {
    try {
      if (!navigator.clipboard) throw new Error('clipboard unavailable')
      await navigator.clipboard.writeText(requestId)
      setCopy('copied')
    } catch {
      setCopy('failed')
    }
  }

  return (
    <div className="request-id">
      <p className="small">
        <span className="label">접수 번호</span> <code className="request-id-value">{requestId}</code>{' '}
        <button type="button" className="link" onClick={() => void handleCopy()}>
          복사
        </button>
      </p>
      {copy === 'copied' && (
        <p className="muted small" role="status">
          접수 번호를 복사했습니다.
        </p>
      )}
      {copy === 'failed' && (
        <p className="muted small" role="status">
          복사하지 못했습니다. 번호를 직접 선택해 복사해 주세요.
        </p>
      )}
    </div>
  )
}
