import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { createMockApi } from '../api/mock'
import type { MockScenarioId } from '../api/mock'
import type { QuestionResponse } from '../api/types'
import { ResultView } from './ResultView'

async function load(id: MockScenarioId) {
  const api = createMockApi({ delayMs: 0, scenario: () => id })
  const [response, options] = await Promise.all([
    api.askQuestion({ question: '테스트 질문' }, 'code'),
    api.getConditions('code'),
  ])
  return { response, options }
}

async function renderResult(
  id: MockScenarioId,
  change?: (response: QuestionResponse) => QuestionResponse,
) {
  const loaded = await load(id)
  const response = change ? change(loaded.response) : loaded.response
  const onUseQuestion = vi.fn()
  render(<ResultView response={response} options={loaded.options} onUseQuestion={onUseQuestion} />)
  return { response, onUseQuestion }
}

describe('답변', () => {
  it('요약, 적용된 조건, 표, 데이터 근거, SQL을 차례로 보여 준다', async () => {
    await renderResult('answer')

    const region = screen.getByRole('region', { name: '답변' })
    expect(within(region).getByText(/버전 4.0에서 표본 수는 9773/)).toBeInTheDocument()

    const applied = screen.getByRole('region', { name: '적용된 조건' })
    expect(applied).toHaveTextContent('4.0 (2026-09-10 ~ 2026-09-23), 5.0 (2026-09-24 ~ 2026-10-12)')
    expect(applied).toHaveTextContent('전체 티어')
    expect(applied).toHaveTextContent('아드리아나')

    const table = screen.getByRole('table')
    expect(within(table).getAllByRole('columnheader').map((h) => h.textContent)).toEqual([
      'version_major',
      'version_minor',
      'samples',
      'win_rate',
      '표본 근거',
    ])
    expect(within(table).getAllByRole('row')).toHaveLength(3) // 머리글 1 + 데이터 2

    const evidence = screen.getByRole('region', { name: '데이터 근거' })
    expect(evidence).toHaveTextContent('2026-10-12 14:51 (한국 시간)')
    expect(evidence).toHaveTextContent('11.0초')
  })

  it('긴 소수는 줄여서 보여 주고 마우스를 올리면 원래 값을 볼 수 있다', async () => {
    await renderResult('answer')
    expect(screen.getByText('14.67')).toHaveAttribute('title', '14.6730788908216515')
    expect(screen.getByText('13.94')).toHaveAttribute('title', '13.9377119950662966')
  })

  it('행마다 표본 수와 집계 단위를 보여 주고, 여러 집단이면 전체 표본은 합치지 않는다', async () => {
    await renderResult('answer')
    expect(screen.getByText('9,773 참가자 출현')).toBeInTheDocument()
    expect(screen.getByText('6,486 참가자 출현')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: '데이터 근거' })).toHaveTextContent(
      '집단마다 다릅니다',
    )
  })

  it('SQL은 기본으로 접혀 있고 열면 실행된 SQL이 그대로 있다', async () => {
    const { response } = await renderResult('answer')
    const details = screen.getByText('실행된 SQL 보기').closest('details')!
    expect(details).not.toHaveAttribute('open')
    expect(details.querySelector('code')?.textContent).toBe(response.sql)
  })

  it('표본이 작은 행은 그 행 안에 경고를 보여 준다', async () => {
    await renderResult('answer', (response) => {
      if (response.kind !== 'answer' || !response.evidence) return response
      const groups = response.evidence.groups.map((g, i) =>
        i === 0
          ? { ...g, sample_size: 12, warnings: ['참가자 출현 12건으로 표본이 작습니다.'] }
          : g,
      )
      return { ...response, evidence: { ...response.evidence, groups } }
    })
    expect(screen.getByText('12 참가자 출현')).toBeInTheDocument()
    expect(screen.getByText(/참가자 출현 12건으로 표본이 작습니다/)).toBeInTheDocument()
  })

  it('요약이 없어도 표와 SQL은 그대로 보여 주고 경고를 알린다', async () => {
    await renderResult('answer-without-summary')

    expect(screen.getByText(/요약을 만들지 못했습니다/)).toBeInTheDocument()
    expect(screen.getByRole('table')).toHaveTextContent('아르다')
    expect(screen.getByText('실행된 SQL 보기')).toBeInTheDocument()

    const warnings = screen.getByRole('note', { name: '경고' })
    expect(warnings).toHaveTextContent('요약을 생성하지 못했습니다')
    // 표본을 확인하지 못한 행은 0이 아니라 "확인 불가"로 표시한다.
    expect(screen.getByText('표본 확인 불가')).toBeInTheDocument()
  })

  it('결과가 0행이면 컬럼은 남기고 행이 없다고 알린다', async () => {
    await renderResult('answer-empty-result')

    expect(screen.getByText('조회 결과가 없습니다.')).toBeInTheDocument()
    const table = screen.getByRole('table')
    expect(within(table).getAllByRole('columnheader').map((h) => h.textContent)).toEqual([
      'samples',
      'win_rate',
    ])
    expect(within(table).getByText('표시할 행이 없습니다.')).toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: '표본 근거' })).not.toBeInTheDocument()
  })
})

describe('안내', () => {
  it('메시지만 보여 주고 표·SQL·적용 조건은 없다', async () => {
    await renderResult('notice')

    const region = screen.getByRole('region', { name: '안내' })
    expect(region).toHaveTextContent('SQL을 실행하지 못했습니다. 질문을 다시 제출해 주세요.')
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.queryByText('실행된 SQL 보기')).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '적용된 조건' })).not.toBeInTheDocument()
  })

  it('예시 질문이 있으면 누를 수 있고 누르면 그 질문을 전달한다', async () => {
    const user = userEvent.setup()
    const { onUseQuestion } = await renderResult('notice', (response) => ({
      ...response,
      examples: ['수집된 경기는 몇 판이야?'],
    }))

    await user.click(screen.getByRole('button', { name: '수집된 경기는 몇 판이야?' }))
    expect(onUseQuestion).toHaveBeenCalledWith('수집된 경기는 몇 판이야?')
  })
})

describe('되묻기', () => {
  it('알아보지 못한 이름과 후보를 보여 주고 표는 없다', async () => {
    await renderResult('clarification')

    const region = screen.getByRole('region', { name: '되묻기' })
    expect(region).toHaveTextContent('‘아드리나’ 대신 쓸 이름을 골라 주세요')
    expect(within(region).getByRole('button', { name: /아드리아나/ })).toHaveTextContent('실험체')
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('후보를 누르면 질문 속 이름을 바꾼 질문을 전달한다', async () => {
    const user = userEvent.setup()
    const { onUseQuestion } = await renderResult('clarification', (response) => ({
      ...response,
      question: '아드리나의 최신 패치 승률은?',
    }))

    await user.click(screen.getByRole('button', { name: /아드리아나/ }))
    expect(onUseQuestion).toHaveBeenCalledWith('아드리아나의 최신 패치 승률은?')
  })

  it('후보가 없으면 정식 이름으로 다시 적도록 안내한다', async () => {
    await renderResult('clarification', (response) =>
      response.kind === 'clarification'
        ? { ...response, clarifications: [{ term: '아드리나', candidates: [] }] }
        : response,
    )
    expect(screen.getByText(/정식 이름으로 다시 적어 주세요/)).toBeInTheDocument()
  })
})
