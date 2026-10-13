import type { ConditionsResponse } from '../api'
import { formatVersion, isDefaultSelection, latestPatch } from '../lib/conditions'
import type { ConditionSelection } from '../lib/conditions'
import { formatPeriod } from '../lib/format'

interface Props {
  options: ConditionsResponse
  value: ConditionSelection
  disabled?: boolean
  onChange: (next: ConditionSelection) => void
}

const LATEST = 'latest'

/** 패치·티어·실험체를 하나씩 고르는 패널이다. 고른 조건은 기본값이고 질문에 적힌 조건이 우선한다. */
export function ConditionPanel({ options, value, disabled, onChange }: Props) {
  const latest = latestPatch(options)
  const latestLabel = latest ? formatVersion(latest) : '확인 불가'

  const patchValue = value.patch ? formatVersion(value.patch) : LATEST
  const characterName =
    options.characters.find((c) => c.code === value.characterCode)?.name ?? '전체'

  return (
    <section className="card" aria-labelledby="conditions-title">
      <h2 id="conditions-title">조건</h2>

      <div className="fields">
        <label className="field">
          <span>패치</span>
          <select
            value={patchValue}
            disabled={disabled}
            onChange={(e) => {
              const picked = options.patches.find((p) => formatVersion(p) === e.target.value)
              onChange({ ...value, patch: picked ? { major: picked.major, minor: picked.minor } : null })
            }}
          >
            <option value={LATEST}>최신 패치 (기본) · {latestLabel}</option>
            {options.patches.map((p) => (
              <option key={formatVersion(p)} value={formatVersion(p)}>
                {formatVersion(p)} · {formatPeriod(p.first_match, p.last_match)}
              </option>
            ))}
          </select>
        </label>

        <label className="field">
          <span>티어</span>
          <select
            value={value.tier}
            disabled={disabled}
            onChange={(e) => onChange({ ...value, tier: e.target.value })}
          >
            <option value="">전체 티어 (기본)</option>
            {options.tiers.map((tier) => (
              <option key={tier} value={tier}>
                {tier}
              </option>
            ))}
          </select>
        </label>

        <label className="field">
          <span>실험체</span>
          <select
            value={value.characterCode ?? ''}
            disabled={disabled}
            onChange={(e) =>
              onChange({ ...value, characterCode: e.target.value === '' ? null : Number(e.target.value) })
            }
          >
            <option value="">전체 실험체 (기본)</option>
            {options.characters.map((c) => (
              <option key={c.code} value={c.code}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
      </div>

      <p className="applied" aria-live="polite">
        {isDefaultSelection(value) ? (
          <>
            조건을 고르지 않으면 <strong>최신 패치({latestLabel})</strong>와 <strong>전체 티어</strong>가
            적용됩니다.
          </>
        ) : (
          <>
            적용될 조건: 패치{' '}
            <strong>{value.patch ? formatVersion(value.patch) : `최신(${latestLabel})`}</strong> · 티어{' '}
            <strong>{value.tier === '' ? '전체' : value.tier}</strong> · 실험체{' '}
            <strong>{characterName}</strong>
          </>
        )}
      </p>
      <p className="muted small">
        패치 기간은 수집된 경기를 기준으로 하며 실제 패치 출시일과 다를 수 있습니다. 질문에 조건을 직접
        적으면 질문 속 조건이 우선합니다.
      </p>
    </section>
  )
}
