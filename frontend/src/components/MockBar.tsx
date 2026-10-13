import { useState } from 'react'
import { MOCK_SCENARIOS, mockControl } from '../api'
import type { MockScenarioId } from '../api'

/** 가짜 응답 모드에서만 보이는 개발용 막대다. 다음 질문에 어떤 응답을 받을지 고른다. */
export function MockBar() {
  const [scenario, setScenario] = useState<MockScenarioId>(mockControl.scenario)

  return (
    <div className="mock-bar">
      <label>
        <span>개발용 · 다음 질문의 가짜 응답</span>
        <select
          value={scenario}
          onChange={(e) => {
            const next = e.target.value as MockScenarioId
            mockControl.scenario = next
            setScenario(next)
          }}
        >
          {MOCK_SCENARIOS.map((s) => (
            <option key={s.id} value={s.id}>
              {s.label}
            </option>
          ))}
        </select>
      </label>
    </div>
  )
}
