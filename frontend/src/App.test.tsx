import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import App from './App'
import { ApiError } from './api'
import { createMockApi } from './api/mock'
import type { MockScenarioId } from './api/mock'
import type { ErApi, QuestionResponse } from './api/types'

/** 가짜 서버를 감싸 호출을 기록한다. 시나리오는 테스트에서 바꿀 수 있다. */
function setup(initial: MockScenarioId = 'answer') {
  let scenario = initial
  const base = createMockApi({ delayMs: 0, scenario: () => scenario })
  const api = {
    verifyInvite: vi.fn<ErApi['verifyInvite']>(base.verifyInvite),
    getConditions: vi.fn<ErApi['getConditions']>(base.getConditions),
    askQuestion: vi.fn<ErApi['askQuestion']>(base.askQuestion),
  }
  const user = userEvent.setup()
  render(<App api={api} />)
  return { api, user, setScenario: (next: MockScenarioId) => (scenario = next) }
}

async function enterInviteCode(user: ReturnType<typeof userEvent.setup>, code = 'friend') {
  await user.type(screen.getByLabelText('초대 코드'), code)
  await user.click(screen.getByRole('button', { name: '확인' }))
}

async function reachQuestionScreen(user: ReturnType<typeof userEvent.setup>) {
  await enterInviteCode(user)
  await screen.findByRole('heading', { name: '조건' })
}

async function ask(user: ReturnType<typeof userEvent.setup>, question = '수집된 경기는 몇 판이야?') {
  await user.type(screen.getByRole('textbox', { name: /궁금한 점/ }), question)
  await user.click(screen.getByRole('button', { name: '질문하기' }))
}

describe('초대 코드 화면', () => {
  it('처음에는 초대 코드 입력 화면을 보여 준다', () => {
    setup()
    expect(screen.getByRole('heading', { name: 'ER Analytics' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '초대 코드 입력' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '조건' })).not.toBeInTheDocument()
  })

  it('가짜 응답 모드임을 화면에 밝힌다', () => {
    setup()
    expect(screen.getByRole('note')).toHaveTextContent('실제 전적 DB를 조회하지 않으며')
  })

  it('잘못된 코드는 오류를 보여 주고 질문 화면으로 넘어가지 않는다', async () => {
    const { user } = setup()
    await enterInviteCode(user, 'INVALID')
    expect(await screen.findByRole('alert')).toHaveTextContent('초대 코드를 확인해 주세요.')
    expect(screen.queryByRole('heading', { name: '조건' })).not.toBeInTheDocument()
  })

  it('코드가 비어 있으면 확인 버튼을 누를 수 없다', () => {
    setup()
    expect(screen.getByRole('button', { name: '확인' })).toBeDisabled()
  })

  it('코드를 확인하면 질문 화면과 오늘 남은 횟수를 보여 준다', async () => {
    const { user, api } = setup()
    await reachQuestionScreen(user)
    expect(api.verifyInvite).toHaveBeenCalledWith('friend')
    expect(await screen.findByText('오늘 남은 질문 50회')).toBeInTheDocument()
  })
})

describe('조건 선택', () => {
  it('조건을 고르지 않으면 최신 패치와 전체 티어가 적용된다고 표시한다', async () => {
    const { user } = setup()
    await reachQuestionScreen(user)
    expect(screen.getByText(/조건을 고르지 않으면/)).toHaveTextContent(
      '조건을 고르지 않으면 최신 패치(5.0)와 전체 티어가 적용됩니다.',
    )
  })

  it('패치 목록은 버전과 수집 기간을 함께 보여 주고 기간이 없으면 그렇게 표시한다', async () => {
    const { user } = setup()
    await reachQuestionScreen(user)
    expect(screen.getByRole('option', { name: '5.0 · 2026-09-24 ~ 2026-10-12' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: '3.0 · 기간 정보 없음' })).toBeInTheDocument()
  })

  it('조건을 고르면 적용될 조건이 바뀐다', async () => {
    const { user } = setup()
    await reachQuestionScreen(user)
    await user.selectOptions(screen.getByLabelText('티어'), '다이아몬드')
    await user.selectOptions(screen.getByLabelText('실험체'), '아드리아나')
    expect(screen.getByText(/적용될 조건/)).toHaveTextContent(
      '적용될 조건: 패치 최신(5.0) · 티어 다이아몬드 · 실험체 아드리아나',
    )
  })
})

describe('질문하기', () => {
  it('조건을 고르지 않으면 conditions를 보내지 않는다', async () => {
    const { user, api } = setup()
    await reachQuestionScreen(user)
    await ask(user)
    await screen.findByLabelText('답변')

    const [request, code] = api.askQuestion.mock.calls[0]
    expect(request.question).toBe('수집된 경기는 몇 판이야?')
    expect(request.conditions).toBeUndefined()
    expect(code).toBe('friend')
  })

  it('고른 조건을 요청에 담아 보낸다', async () => {
    const { user, api } = setup()
    await reachQuestionScreen(user)
    await user.selectOptions(screen.getByLabelText('패치'), '4.0')
    await user.selectOptions(screen.getByLabelText('티어'), '다이아몬드')
    await ask(user, '아드리아나 승률은?')
    await screen.findByLabelText('답변')

    expect(api.askQuestion.mock.calls[0][0].conditions).toEqual({
      patch: { mode: 'exact', versions: [{ major: 4, minor: 0 }] },
      tier: '다이아몬드',
      tier_division: null,
      character_code: null,
    })
  })

  it('공백뿐인 질문은 보낼 수 없다', async () => {
    const { user } = setup()
    await reachQuestionScreen(user)
    await user.type(screen.getByRole('textbox', { name: /궁금한 점/ }), '   ')
    expect(screen.getByRole('button', { name: '질문하기' })).toBeDisabled()
  })

  it('예시 질문을 누르면 입력란에 채워진다', async () => {
    const { user } = setup()
    await reachQuestionScreen(user)
    await user.click(screen.getByRole('button', { name: '수집된 경기는 몇 판이야?' }))
    expect(screen.getByRole('textbox', { name: /궁금한 점/ })).toHaveValue('수집된 경기는 몇 판이야?')
  })

  it('정식 명칭 사용을 안내한다', async () => {
    const { user } = setup()
    await reachQuestionScreen(user)
    expect(screen.getByText(/게임 속 정식 이름/)).toBeInTheDocument()
  })

  it('응답을 기다리는 동안 버튼을 막고 대기 안내를 보여 준다', async () => {
    let release: (response: QuestionResponse) => void = () => {}
    const { user, api } = setup()
    await reachQuestionScreen(user)
    const original = api.askQuestion.getMockImplementation()!
    api.askQuestion.mockImplementationOnce(
      (...args) =>
        new Promise<QuestionResponse>((resolve) => {
          release = resolve
          void original(...args)
        }),
    )

    await ask(user)
    expect(await screen.findByRole('status')).toHaveTextContent('조회하고 답변을 만드는 중')
    expect(screen.getByRole('button', { name: '조회 중…' })).toBeDisabled()
    expect(screen.getByLabelText('티어')).toBeDisabled()

    release(await original({ question: 'x' }, 'friend'))
    await screen.findByLabelText('답변')
    expect(screen.getByRole('button', { name: '질문하기' })).toBeEnabled()
  })
})

describe('응답 유형 구분', () => {
  it.each([
    ['answer', '답변'],
    ['notice', '안내'],
    ['clarification', '되묻기'],
  ] as const)('%s 응답은 "%s"로 구분되어 보인다', async (scenario, label) => {
    const { user } = setup(scenario)
    await reachQuestionScreen(user)
    await ask(user)

    const region = await screen.findByLabelText(label)
    expect(region).toHaveClass(`result-${scenario}`)
    for (const other of ['답변', '안내', '되묻기'].filter((l) => l !== label)) {
      expect(screen.queryByLabelText(other)).not.toBeInTheDocument()
    }
  })
})

describe('되묻기 후보 선택', () => {
  it('후보를 누르면 질문이 바뀌어 입력란에 채워지고 포커스가 옮겨진다', async () => {
    const { user } = setup('clarification')
    await reachQuestionScreen(user)
    await ask(user, '아드리나의 최신 패치 승률은?')
    const region = await screen.findByRole('region', { name: '되묻기' })
    await user.click(within(region).getByRole('button', { name: /아드리아나/ }))

    const input = screen.getByRole('textbox', { name: /궁금한 점/ })
    expect(input).toHaveValue('아드리아나의 최신 패치 승률은?')
    expect(input).toHaveFocus()
  })
})

describe('오류', () => {
  it('질문 오류는 서버 메시지를 그대로 보여 주고 질문 화면에 머문다', async () => {
    const { user } = setup('error-invite-limit')
    await reachQuestionScreen(user)
    await ask(user)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('오늘 질문 횟수를 모두 사용했습니다.')
    expect(alert).toHaveTextContent('한국 시간 자정에 초기화됩니다')
    expect(screen.getByRole('heading', { name: '조건' })).toBeInTheDocument()
  })

  it('질문 중 초대 코드가 거절되면 첫 화면으로 돌아가 안내한다', async () => {
    const { user, api } = setup()
    await reachQuestionScreen(user)
    api.askQuestion.mockRejectedValueOnce(new ApiError('INVITE_INVALID', '초대 코드를 확인해 주세요.', 401))

    await ask(user)
    await waitFor(() => expect(screen.getByRole('heading', { name: '초대 코드 입력' })).toBeInTheDocument())
    expect(screen.getByRole('alert')).toHaveTextContent('초대 코드가 유효하지 않습니다')
  })

  it('조건 목록을 불러오지 못하면 다시 시도할 수 있다', async () => {
    const { user, api } = setup()
    api.getConditions.mockRejectedValueOnce(new ApiError('NETWORK_ERROR', '서버에 연결하지 못했습니다.'))
    await enterInviteCode(user)

    expect(await screen.findByRole('alert')).toHaveTextContent('서버에 연결하지 못했습니다.')
    await user.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(await screen.findByRole('heading', { name: '조건' })).toBeInTheDocument()
  })
})
