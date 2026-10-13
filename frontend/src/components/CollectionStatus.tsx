import { useEffect, useState } from 'react'
import { asApiError } from '../api'
import type { ApiError, CollectionSummaryResponse, ErApi } from '../api'
import { formatVersion } from '../lib/conditions'
import { formatCount, formatDateTime, formatPeriod } from '../lib/format'
import { ErrorNotice } from './ErrorNotice'

interface Props {
  api: ErApi
  inviteCode: string
  /** 서버가 초대 코드를 거절하면 첫 화면으로 돌아가기 위해 호출한다. 같은 함수를 계속 넘겨야 한다. */
  onInviteInvalid: () => void
}

type State =
  | { status: 'loading' }
  | { status: 'ready'; summary: CollectionSummaryResponse }
  | { status: 'error'; error: ApiError }

/**
 * 이용자용 수집 현황이다. 답이 어떤 데이터에서 나오는지, 그 데이터가 실제로 쌓여 있다는 것을 숫자로 보여 준다.
 * 운영 정보(큐·오류·수집 속도)는 넣지 않는다.
 */
export function CollectionStatus({ api, inviteCode, onInviteInvalid }: Props) {
  const [state, setState] = useState<State>({ status: 'loading' })
  const [reloadCount, setReloadCount] = useState(0)

  useEffect(() => {
    let cancelled = false
    api
      .getCollectionSummary(inviteCode)
      .then((summary) => {
        if (!cancelled) setState({ status: 'ready', summary })
      })
      .catch((e: unknown) => {
        if (cancelled) return
        const error = asApiError(e)
        if (error.code === 'INVITE_INVALID') onInviteInvalid()
        else setState({ status: 'error', error })
      })
    return () => {
      cancelled = true
    }
  }, [api, inviteCode, onInviteInvalid, reloadCount])

  function reload() {
    setState({ status: 'loading' })
    setReloadCount((n) => n + 1)
  }

  return (
    <section className="stack" aria-labelledby="status-title">
      <div>
        <h2 id="status-title">수집 현황</h2>
        <p className="muted">
          질문의 답은 아래에 수집된 실제 경기 전적 데이터를 집계해서 만듭니다.
        </p>
      </div>

      {state.status === 'loading' && (
        <p className="muted" role="status">
          수집 현황을 불러오는 중…
        </p>
      )}
      {state.status === 'error' && <ErrorNotice error={state.error} onRetry={reload} />}
      {state.status === 'ready' && <Summary summary={state.summary} onReload={reload} />}
    </section>
  )
}

const UNKNOWN_TIER_LABEL = '티어 미확인'

function Summary({ summary, onReload }: { summary: CollectionSummaryResponse; onReload: () => void }) {
  const latest = formatDateTime(summary.latest_collected_at)
  const snapshotAt = formatDateTime(summary.snapshot_at)
  const cachedUntil = formatDateTime(summary.cached_until)
  const hasUnknownTier = summary.by_tier.some((row) => row.tier === null)
  const maxAppearances = Math.max(0, ...summary.by_tier.map((row) => row.participant_appearances))

  return (
    <>
      <div className="stat-grid">
        <div className="card stat">
          <p className="stat-label">총 경기 수</p>
          <p className="stat-value">{formatCount(summary.total_games)}</p>
          <p className="muted small">수집한 경기 (경기 단위)</p>
        </div>

        <div className="card stat">
          <p className="stat-label">최신 수집 시각</p>
          <p className="stat-value stat-time">{latest ?? '확인 불가'}</p>
          <p className="muted small">한국 시간 기준</p>
        </div>

        <div className="card stat">
          <p className="stat-label">서비스 가능한 패치 버전</p>
          {summary.patches.length === 0 ? (
            <p className="stat-value stat-time">확인 불가</p>
          ) : (
            <ul className="patch-list">
              {summary.patches.map((patch) => (
                <li key={formatVersion(patch)}>
                  <strong className="chip static">{formatVersion(patch)}</strong>
                  <span className="muted small">{formatPeriod(patch.first_match, patch.last_match)}</span>
                </li>
              ))}
            </ul>
          )}
          <p className="muted small">수집된 경기가 있는 패치와 수집 경기 기준 기간</p>
        </div>
      </div>

      <section className="card" aria-labelledby="tier-title">
        <h3 id="tier-title">티어별 수집 규모</h3>
        <p className="muted small">
          참가 기록 수는 경기 수가 아니라 그 티어 참가자가 경기에 출전한 횟수입니다. 이용자 수는 이용자의 가장
          최근 티어 기준이라 한 사람은 한 티어에만 들어갑니다.
        </p>

        {summary.by_tier.length === 0 ? (
          <p className="muted">아직 집계된 티어가 없습니다.</p>
        ) : (
          <div className="table-wrap" role="region" aria-label="티어별 수집 규모 표" tabIndex={0}>
            <table>
              <thead>
                <tr>
                  <th scope="col">티어</th>
                  <th scope="col" className="num">
                    이용자 수
                  </th>
                  <th scope="col" className="num">
                    참가 기록 수
                  </th>
                  <th scope="col">
                    <span className="sr-only">참가 기록 수 비교</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {summary.by_tier.map((row) => (
                  <tr key={row.tier ?? UNKNOWN_TIER_LABEL}>
                    <th scope="row">{row.tier ?? UNKNOWN_TIER_LABEL}</th>
                    <td className="num">{formatCount(row.users)}</td>
                    <td className="num">{formatCount(row.participant_appearances)}</td>
                    <td className="bar-cell" aria-hidden="true">
                      <div
                        className="bar"
                        style={{
                          width: `${maxAppearances === 0 ? 0 : (row.participant_appearances / maxAppearances) * 100}%`,
                        }}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {hasUnknownTier && (
          <p className="muted small">‘{UNKNOWN_TIER_LABEL}’은 티어를 확인하지 못한 이용자와 참가 기록입니다.</p>
        )}
      </section>

      <div className="actions">
        <button type="button" onClick={onReload}>
          새로 고침
        </button>
        <span className="muted small">
          {snapshotAt && cachedUntil
            ? `이 집계는 ${snapshotAt}에 만들어졌고 ${cachedUntil}까지 같은 결과가 보관됩니다. 그 전에는 새로 고침해도 같은 결과가 나올 수 있습니다.`
            : '집계 결과는 서버가 몇 분 동안 보관하므로 새로 고침해도 같은 결과가 나올 수 있습니다.'}
        </span>
      </div>
    </>
  )
}
