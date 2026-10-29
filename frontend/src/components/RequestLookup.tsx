import { useState } from 'react'
import type { FormEvent } from 'react'
import { normalizeRequestId } from '../lib/requestId'

interface Props {
  /** 다른 질문을 기다리는 중이면 막는다. */
  disabled: boolean
  /** 형식이 맞는 접수 번호로 결과를 조회한다. */
  onLookup: (requestId: string) => void
}

/**
 * 탭을 닫았다 다시 열었을 때처럼 진행 상태가 없을 때, 화면에 보였던 접수 번호로 결과를 다시 확인한다.
 * 질문을 다시 보내지 않으며 조회는 횟수에 영향이 없다.
 */
export function RequestLookup({ disabled, onLookup }: Props) {
  const [value, setValue] = useState('')
  const [formatError, setFormatError] = useState(false)

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const requestId = normalizeRequestId(value)
    if (requestId === null) {
      setFormatError(true)
      return
    }
    setFormatError(false)
    setValue(requestId)
    onLookup(requestId)
  }

  return (
    <section className="card" aria-labelledby="lookup-title">
      <h2 id="lookup-title">접수 번호로 결과 확인</h2>
      <p className="muted small">
        질문할 때 화면에 보였던 접수 번호를 넣으면 그 질문의 결과를 다시 볼 수 있습니다. 결과를 읽기만 하며 질문을
        다시 보내지 않으므로 질문 횟수에 영향이 없습니다. 같은 초대 코드로 접수한 질문만 확인할 수 있습니다.
      </p>
      <form onSubmit={handleSubmit} className="stack">
        <label className="field">
          <span>접수 번호</span>
          <input
            type="text"
            value={value}
            onChange={(e) => {
              setValue(e.target.value)
              setFormatError(false)
            }}
            placeholder="예: 3f2b8c1e-5a4d-4c2b-9e1f-0a1b2c3d4e5f"
            autoComplete="off"
            spellCheck={false}
            disabled={disabled}
          />
        </label>
        {formatError && (
          <p role="alert" className="error-text">
            접수 번호 형식이 맞지 않습니다. 화면에 보였던 36자 번호(영문 소문자·숫자와 하이픈)를 그대로 넣어 주세요.
          </p>
        )}
        <button type="submit" disabled={disabled || value.trim() === ''}>
          결과 확인
        </button>
      </form>
    </section>
  )
}
