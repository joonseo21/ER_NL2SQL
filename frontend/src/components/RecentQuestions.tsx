import { formatDateTime } from '../lib/format'
import type { RecentQuestion } from '../lib/recentQuestions'

interface Props {
  entries: RecentQuestion[]
  enabled: boolean
  available: boolean
  disabled: boolean
  onLookup: (requestId: string) => void
  onClear: () => void
  onEnabledChange: (enabled: boolean) => void
}

export function RecentQuestions({ entries, enabled, available, disabled, onLookup, onClear, onEnabledChange }: Props) {
  return (
    <section className="card recent-questions" aria-labelledby="recent-title">
      <h2 id="recent-title">최근 질문</h2>
      <p className="muted small">
        같은 초대 코드로 접수한 최근 10건을 이 브라우저에 7일간 남깁니다. 질문을 고르면 결과를 읽기만 하며,
        질문을 다시 보내지 않으므로 질문 횟수에 영향이 없습니다.
      </p>
      <div className="stack recent-controls">
        <button type="button" onClick={onClear}>이 기기에 남은 질문 기록 지우기</button>
        <label className="recent-toggle">
          <input type="checkbox" checked={enabled} onChange={(event) => onEnabledChange(event.target.checked)} />
          <span>이 기기에 질문 기록 남기기</span>
        </label>
      </div>
      <p className="muted small">
        질문 문장·접수 번호·접수 시각이 남습니다. 지우기와 끄기는 이 브라우저의 모든 초대 코드에 적용되며,
        끄면 기존 목록도 지웁니다. 서버 기록과 같은 탭의 새로고침 복구에는 영향이 없습니다.
        초대 코드를 바꿔도 목록은 남습니다. 만료 기록은 앱을 열거나 사용하는 때 정리합니다.
        서버의 결과 보관 기간은 확인되지 않아 목록에 있어도 결과를 가져오지 못할 수 있습니다.
      </p>
      {!available && (
        <p role="status" className="error-text small">
          브라우저 저장소를 사용할 수 없어 질문 기록이나 설정을 저장·삭제하지 못했습니다.
          기록이 남아 있을 수 있으니 브라우저의 사이트 데이터 설정을 확인해 주세요.
        </p>
      )}
      {!enabled ? <p className="muted small">질문 기록 남기기가 꺼져 있습니다.</p> : entries.length === 0 ? (
        <p className="muted small">이 초대 코드로 이 기기에 남긴 최근 질문이 없습니다.</p>
      ) : (
        <ol className="recent-list">
          {entries.map((entry) => (
            <li key={entry.requestId}>
              <button type="button" className="recent-question" disabled={disabled} onClick={() => onLookup(entry.requestId)}>
                <span>{entry.question}</span>
                <time className="small" dateTime={new Date(entry.acceptedAt).toISOString()}>
                  접수 {formatDateTime(new Date(entry.acceptedAt).toISOString())} (한국 시간)
                </time>
              </button>
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}
