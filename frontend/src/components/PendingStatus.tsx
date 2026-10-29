import { useEffect, useState } from 'react'
import type { QuestionStatus } from '../api'
import { RequestIdBox } from './RequestIdBox'

interface Props {
  /** 서버가 알려 준 진행 상태. 아직 접수 응답을 받기 전이면 null이다. */
  status: QuestionStatus | null
  /** 접수 번호. 접수 응답을 받기 전이면 null이다. */
  requestId?: string | null
  /** 새로고침 뒤 이어서 조회하는 중이면 true */
  resumed?: boolean
  /** 경과 시간을 셀 시작 시각(밀리초). 새로고침 뒤에는 접수 시각을 넘긴다. 생략하면 화면이 나타난 때부터 센다. */
  since?: number
}

const STATUS_TEXT: Record<QuestionStatus | 'sending', string> = {
  sending: '질문을 보내는 중입니다…',
  // 처리 중인 질문이 있으면 서버가 접수 전에 거절하므로(spec D-22) 접수된 질문은 곧 처리가 시작된다.
  accepted: '질문이 접수되었습니다. 곧 처리를 시작합니다…',
  processing: '전적 DB를 조회하고 답변을 만드는 중입니다…',
  // completed·failed는 곧 결과나 오류 화면으로 바뀌므로 짧게 머문다.
  completed: '결과를 불러오는 중입니다…',
  failed: '처리 결과를 확인하는 중입니다…',
}

const RESUMED_TEXT = '접수한 질문의 결과를 이어서 확인하는 중입니다…'

/**
 * 응답을 기다리는 동안 보여 주는 안내다. 답변 생성에는 수십 초에서 몇 분이 걸릴 수 있어
 * 멈춘 것처럼 보이지 않도록 진행 상태와 경과 시간을 함께 표시한다. 질문을 보내는 순간 나타났다
 * 사라지므로 마운트될 때마다 0초에서 시작한다.
 */
export function PendingStatus({ status, requestId = null, resumed = false, since }: Props) {
  const [start] = useState(() => since ?? Date.now())
  const [seconds, setSeconds] = useState(() => elapsedSeconds(start))

  useEffect(() => {
    const id = window.setInterval(() => setSeconds(elapsedSeconds(start)), 1000)
    return () => window.clearInterval(id)
  }, [start])

  return (
    <section className="card pending" role="status" aria-live="polite">
      <p>
        <strong>{resumed && status === null ? RESUMED_TEXT : STATUS_TEXT[status ?? 'sending']}</strong>{' '}
        {seconds}초 경과
      </p>
      {requestId !== null && (
        <>
          <RequestIdBox requestId={requestId} />
          <p className="muted small">
            이 탭에서는 새로고침해도 이어서 결과를 확인합니다. 탭을 닫았다면 초대 코드를 다시 입력한 뒤 이 번호로 결과를
            확인할 수 있습니다.
          </p>
        </>
      )}
      <p className="muted small">
        질문 하나에 수십 초에서 몇 분까지 걸릴 수 있습니다. 같은 질문을 다시 보내지 말고 기다려 주세요.
      </p>
    </section>
  )
}

function elapsedSeconds(start: number): number {
  return Math.max(0, Math.floor((Date.now() - start) / 1000))
}
