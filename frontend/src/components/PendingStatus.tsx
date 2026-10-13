import { useEffect, useState } from 'react'

/**
 * 응답을 기다리는 동안 보여 주는 안내다. 답변 생성에는 수십 초에서 몇 분이 걸릴 수 있어
 * 멈춘 것처럼 보이지 않도록 경과 시간을 함께 표시한다. 질문을 보내는 순간 나타났다 사라지므로
 * 마운트될 때마다 0초에서 시작한다.
 */
export function PendingStatus() {
  const [seconds, setSeconds] = useState(0)

  useEffect(() => {
    const id = window.setInterval(() => setSeconds((s) => s + 1), 1000)
    return () => window.clearInterval(id)
  }, [])

  return (
    <section className="card pending" role="status" aria-live="polite">
      <p>
        <strong>전적 DB를 조회하고 답변을 만드는 중입니다…</strong> {seconds}초 경과
      </p>
      <p className="muted small">
        질문 하나에 수십 초에서 몇 분까지 걸릴 수 있습니다. 같은 질문을 다시 보내지 말고 기다려 주세요.
      </p>
    </section>
  )
}
