import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import App from './App'
import { ApiError } from './api'
import { createMockApi } from './api/mock'
import type { MockScenarioId } from './api/mock'
import type { ErApi, QuestionResponse, QuestionStatus } from './api/types'
import { loadLastResult } from './lib/lastResult'
import { loadPendingQuestion, savePendingQuestion } from './lib/pendingQuestion'
import { saveInviteCode } from './lib/session'

/**
 * 가짜 서버를 감싸 호출을 기록한다. 시나리오는 테스트에서 바꿀 수 있다.
 * prepare는 화면을 그리기 전에 호출을 바꿔 둘 때 쓴다(새로고침 직후의 첫 조회 등).
 */
function setup(initial: MockScenarioId = 'answer', prepare?: (api: ReturnType<typeof wrapApi>) => void) {
  let scenario = initial
  const api = wrapApi(createMockApi({ delayMs: 0, scenario: () => scenario }))
  prepare?.(api)
  const user = userEvent.setup()
  render(<App api={api} />)
  return { api, user, setScenario: (next: MockScenarioId) => (scenario = next) }
}

function wrapApi(base: ErApi) {
  return {
    verifyInvite: vi.fn<ErApi['verifyInvite']>(base.verifyInvite),
    getConditions: vi.fn<ErApi['getConditions']>(base.getConditions),
    askQuestion: vi.fn<ErApi['askQuestion']>(base.askQuestion),
    resumeQuestion: vi.fn<ErApi['resumeQuestion']>(base.resumeQuestion),
    checkQuestion: vi.fn<ErApi['checkQuestion']>(base.checkQuestion),
    submitFeedback: vi.fn<ErApi['submitFeedback']>(base.submitFeedback),
    getCollectionSummary: vi.fn<ErApi['getCollectionSummary']>(base.getCollectionSummary),
  }
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

  it('응답을 기다리는 동안 버튼을 막고 진행 상태를 접수 → 처리 중 순서로 보여 준다', async () => {
    let notify: (status: QuestionStatus) => void = () => {}
    let release: (response: QuestionResponse) => void = () => {}
    const { user, api } = setup()
    await reachQuestionScreen(user)
    const original = api.askQuestion.getMockImplementation()!
    // 서버가 단계마다 알려 주는 상황을 직접 제어한다.
    api.askQuestion.mockImplementationOnce(
      (_request, _code, onStatus) =>
        new Promise<QuestionResponse>((resolve) => {
          notify = (status) => onStatus?.(status)
          release = resolve
        }),
    )

    await ask(user)
    // 아직 접수 응답이 없다.
    expect(await screen.findByRole('status')).toHaveTextContent('질문을 보내는 중')
    expect(screen.getByRole('button', { name: '조회 중…' })).toBeDisabled()
    expect(screen.getByLabelText('티어')).toBeDisabled()

    act(() => notify('accepted'))
    expect(screen.getByRole('status')).toHaveTextContent('질문이 접수되었습니다. 곧 처리를 시작합니다')
    // 처리 중이면 접수 전에 거절하므로(대기열 없음) 순서를 기다린다는 안내를 하지 않는다.
    expect(screen.getByRole('status')).not.toHaveTextContent('순서')

    act(() => notify('processing'))
    expect(screen.getByRole('status')).toHaveTextContent('조회하고 답변을 만드는 중')

    release(await original({ question: 'x' }, 'friend'))
    await screen.findByLabelText('답변')
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
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

  it('처리 중이라 접수가 거절되면 안내를 보여 주고 질문·조건·남은 횟수를 그대로 두어 다시 보낼 수 있다', async () => {
    const { user, api, setScenario } = setup('error-question-busy')
    await reachQuestionScreen(user)
    await user.selectOptions(screen.getByLabelText('티어'), '다이아몬드')
    await ask(user, '아드리아나 승률은?')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(
      '지금 다른 질문을 처리하고 있습니다. 잠시 뒤 다시 질문해 주세요. 이번 시도는 질문 횟수에 포함되지 않습니다.',
    )
    // 접수되지 않았으므로 횟수 포함 안내가 없고 남은 횟수도 그대로다.
    expect(alert).not.toHaveTextContent('오늘 질문 횟수에 포함되었습니다')
    expect(screen.getByText('오늘 남은 질문 50회')).toBeInTheDocument()
    // 결과 다시 확인 버튼도 없다. 확인할 접수 번호가 없기 때문이다.
    expect(within(alert).queryByRole('button')).not.toBeInTheDocument()

    // 질문과 조건이 남아 있어 그대로 다시 보낼 수 있고, 자동으로 다시 보내지는 않았다.
    expect(screen.getByRole('textbox', { name: /궁금한 점/ })).toHaveValue('아드리아나 승률은?')
    expect(screen.getByLabelText('티어')).toHaveValue('다이아몬드')
    expect(api.askQuestion).toHaveBeenCalledTimes(1)

    setScenario('answer')
    await user.click(screen.getByRole('button', { name: '질문하기' }))
    await screen.findByLabelText('답변')
    expect(api.askQuestion).toHaveBeenCalledTimes(2)
    expect(api.askQuestion.mock.calls[1][0]).toMatchObject({
      question: '아드리아나 승률은?',
      conditions: { tier: '다이아몬드' },
    })
    expect(screen.getByText('오늘 남은 질문 49회')).toBeInTheDocument()
  })

  it('처리 중 거절 응답에 서버가 남은 횟수를 주면 그 값으로 표시한다', async () => {
    const { user, api } = setup()
    await reachQuestionScreen(user)
    api.askQuestion.mockRejectedValueOnce(new ApiError('QUESTION_BUSY', '처리 중입니다.', 503, null, { remainingToday: 47 }))

    await ask(user)
    expect(await screen.findByRole('alert')).toHaveTextContent('처리 중입니다.')
    expect(screen.getByText('오늘 남은 질문 47회')).toBeInTheDocument()
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

describe('접수 뒤의 오류', () => {
  it('처리 실패는 서버 문구와 함께 횟수가 소비되었음을 알리고 남은 횟수를 갱신한다', async () => {
    const { user } = setup('error-agent')
    await reachQuestionScreen(user)
    await ask(user)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('질문 처리 서버에 연결하지 못했습니다.')
    expect(alert).toHaveTextContent('오늘 질문 횟수에 포함되었습니다')
    expect(screen.getByText('오늘 남은 질문 49회')).toBeInTheDocument()
    // 처리 실패에는 후기 폼이 없다. 답변이 없기 때문이다.
    expect(screen.queryByRole('region', { name: /어땠나요/ })).not.toBeInTheDocument()
  })

  it('처리가 오래 걸려 자동 조회가 멈추면 같은 질문을 다시 보내지 않고 결과를 다시 확인한다', async () => {
    const { user, api } = setup('error-timeout')
    await reachQuestionScreen(user)
    await ask(user)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('처리가 오래 걸리고 있습니다. 나중에 결과를 다시 확인해 주세요.')
    expect(alert).toHaveTextContent('같은 질문을 다시 보내지 말고')

    await user.click(within(alert).getByRole('button', { name: '결과 다시 확인' }))

    expect(await screen.findByLabelText('답변')).toBeInTheDocument()
    expect(api.askQuestion).toHaveBeenCalledTimes(1) // 질문은 다시 보내지 않았다.
    expect(api.checkQuestion).toHaveBeenCalledWith(expect.stringMatching(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/), 49, 'friend')
  })
})

describe('후기', () => {
  /** 마지막 질문에 대한 서버 응답의 log_id */
  async function lastLogId(api: ReturnType<typeof setup>['api']) {
    const results = api.askQuestion.mock.results
    return (await results[results.length - 1].value).log_id as string
  }

  async function reachAnswer(scenario: MockScenarioId = 'answer') {
    const ctx = setup(scenario)
    await reachQuestionScreen(ctx.user)
    await ask(ctx.user)
    await screen.findByRole('region', { name: '이 답변은 어땠나요?' })
    return ctx
  }

  it('답변 아래에 후기 폼이 나타나고 평가를 고르기 전에는 보낼 수 없다', async () => {
    await reachAnswer()
    expect(screen.getByRole('button', { name: '후기 보내기' })).toBeDisabled()
    expect(screen.getByRole('button', { name: /좋아요/ })).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByText('0 / 500')).toBeInTheDocument()
  })

  it.each(['notice', 'clarification'] as const)('%s 응답에도 후기를 남길 수 있다', async (scenario) => {
    await reachAnswer(scenario)
    expect(screen.getByRole('button', { name: /싫어요/ })).toBeEnabled()
  })

  it('평가와 의견을 결과의 log_id로 보낸다', async () => {
    const { user, api } = await reachAnswer()
    await user.click(screen.getByRole('button', { name: /좋아요/ }))
    await user.type(screen.getByLabelText(/한 줄 의견/), '표가 보기 좋았어요')
    await user.click(screen.getByRole('button', { name: '후기 보내기' }))

    expect(await screen.findByText(/후기를 보냈습니다/)).toBeInTheDocument()
    expect(api.submitFeedback).toHaveBeenCalledWith(
      await lastLogId(api),
      { rating: 'like', comment: '표가 보기 좋았어요' },
      'friend',
    )
  })

  it('의견을 비워 두면 comment를 보내지 않는다', async () => {
    const { user, api } = await reachAnswer()
    await user.click(screen.getByRole('button', { name: /싫어요/ }))
    await user.click(screen.getByRole('button', { name: '후기 보내기' }))
    await screen.findByText(/후기를 보냈습니다/)

    const [, feedback] = api.submitFeedback.mock.calls[0]
    expect(feedback).toEqual({ rating: 'dislike' })
    expect('comment' in feedback).toBe(false)
  })

  it('다시 보낼 수 있고 덮어쓴다고 안내한다. 내용을 고치면 보냈다는 표시가 사라진다', async () => {
    const { user, api } = await reachAnswer()
    await user.click(screen.getByRole('button', { name: /좋아요/ }))
    await user.click(screen.getByRole('button', { name: '후기 보내기' }))
    expect(await screen.findByRole('status')).toHaveTextContent('다시 보내면 이전 후기를 덮어씁니다')

    await user.click(screen.getByRole('button', { name: /싫어요/ }))
    expect(screen.queryByText(/후기를 보냈습니다/)).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '후기 보내기' }))
    await screen.findByText(/후기를 보냈습니다/)

    expect(api.submitFeedback).toHaveBeenCalledTimes(2)
    const logId = await lastLogId(api)
    expect(api.submitFeedback.mock.calls.map(([id]) => id)).toEqual([logId, logId])
    expect(api.submitFeedback.mock.calls[1][1]).toEqual({ rating: 'dislike' })
  })

  it('의견은 500자까지만 입력되고 글자 수를 보여 준다', async () => {
    const { user } = await reachAnswer()
    const input = screen.getByLabelText(/한 줄 의견/)
    expect(input).toHaveAttribute('maxlength', '500')
    await user.type(input, '가나다')
    expect(screen.getByText('3 / 500')).toBeInTheDocument()
  })

  it('새 질문의 답변에는 새 후기 폼이 나온다', async () => {
    const { user } = await reachAnswer()
    await user.click(screen.getByRole('button', { name: /좋아요/ }))
    await user.click(screen.getByRole('button', { name: '후기 보내기' }))
    await screen.findByText(/후기를 보냈습니다/)

    await user.clear(screen.getByRole('textbox', { name: /궁금한 점/ }))
    await ask(user, '두 번째 질문')
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /좋아요/ })).toHaveAttribute('aria-pressed', 'false'),
    )
    expect(screen.queryByText(/후기를 보냈습니다/)).not.toBeInTheDocument()
  })

  it('서버가 후기를 거절하면 메시지를 보여 주고 보냈다고 표시하지 않는다', async () => {
    const { user, api } = await reachAnswer()
    api.submitFeedback.mockRejectedValueOnce(
      new ApiError('QUESTION_NOT_FOUND', '질문을 찾을 수 없습니다.', 404),
    )
    await user.click(screen.getByRole('button', { name: /좋아요/ }))
    await user.click(screen.getByRole('button', { name: '후기 보내기' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('질문을 찾을 수 없습니다.')
    expect(screen.queryByText(/후기를 보냈습니다/)).not.toBeInTheDocument()
  })

  it('후기를 보내다 초대 코드가 거절되면 첫 화면으로 돌아간다', async () => {
    const { user, api } = await reachAnswer()
    api.submitFeedback.mockRejectedValueOnce(new ApiError('INVITE_INVALID', '초대 코드를 확인해 주세요.', 401))
    await user.click(screen.getByRole('button', { name: /좋아요/ }))
    await user.click(screen.getByRole('button', { name: '후기 보내기' }))

    expect(await screen.findByRole('heading', { name: '초대 코드 입력' })).toBeInTheDocument()
  })
})

describe('수집 현황', () => {
  async function openStatus(user: ReturnType<typeof userEvent.setup>) {
    await user.click(screen.getByRole('button', { name: '수집 현황' }))
    return screen.findByRole('region', { name: '수집 현황' })
  }

  it('처음에는 불러오지 않고 탭을 열 때 한 번만 불러온다', async () => {
    const { user, api } = setup()
    await reachQuestionScreen(user)
    expect(api.getCollectionSummary).not.toHaveBeenCalled()

    await openStatus(user)
    await user.click(screen.getByRole('button', { name: '질문' }))
    await user.click(screen.getByRole('button', { name: '수집 현황' }))
    expect(api.getCollectionSummary).toHaveBeenCalledTimes(1)
    expect(api.getCollectionSummary).toHaveBeenCalledWith('friend')
  })

  it('총 경기 수, 최신 수집 시각, 서비스 가능한 패치, 티어별 규모를 보여 준다', async () => {
    const { user } = setup()
    await reachQuestionScreen(user)
    const status = await openStatus(user)

    expect(await within(status).findByText('72,140')).toBeInTheDocument()
    expect(status).toHaveTextContent('최신 수집 시각')
    expect(status).toHaveTextContent('2026-10-13 11:42')
    expect(status).toHaveTextContent('서비스 가능한 패치 버전')
    for (const version of ['5.0', '4.0', '3.0']) {
      expect(within(status).getByText(version)).toBeInTheDocument()
    }

    const table = within(status).getByRole('table')
    const row = within(table).getByRole('row', { name: /다이아몬드/ })
    expect(row).toHaveTextContent('3,412') // 이용자 수
    expect(row).toHaveTextContent('436,118') // 참가 기록 수
    expect(within(table).getByRole('columnheader', { name: '참가 기록 수' })).toBeInTheDocument()
    expect(within(table).getByRole('columnheader', { name: '이용자 수' })).toBeInTheDocument()
  })

  it('참가 기록 수가 경기 수가 아니라 출전 횟수임을 안내한다', async () => {
    const { user } = setup()
    await reachQuestionScreen(user)
    const status = await openStatus(user)
    expect(await within(status).findByText(/경기 수가 아니라 그 티어 참가자가 경기에 출전한 횟수/)).toBeInTheDocument()
  })

  it('티어를 확인하지 못한 항목은 "티어 미확인"으로 표시하고 뜻을 안내한다', async () => {
    const { user } = setup()
    await reachQuestionScreen(user)
    const status = await openStatus(user)

    const table = await within(status).findByRole('table')
    const row = within(table).getByRole('row', { name: /티어 미확인/ })
    expect(row).toHaveTextContent('12')
    expect(row).toHaveTextContent('1,840')
    expect(status).toHaveTextContent('티어를 확인하지 못한 이용자와 참가 기록입니다')
  })

  it('자료가 없는 티어는 0으로 보여 주고 막대는 비워 둔다', async () => {
    const { user } = setup()
    await reachQuestionScreen(user)
    const status = await openStatus(user)

    const row = within(await within(status).findByRole('table')).getByRole('row', { name: /플래티넘 미만/ })
    expect(within(row).getAllByText('0')).toHaveLength(2)
    expect(row.querySelector('.bar')).toHaveStyle({ width: '0%' })
  })

  it('패치마다 수집 경기 기준 기간을 함께 보여 주고 기간이 없으면 그렇게 표시한다', async () => {
    const { user } = setup()
    await reachQuestionScreen(user)
    const status = await openStatus(user)

    expect(await within(status).findByText('2026-09-24 ~ 2026-10-12')).toBeInTheDocument()
    expect(within(status).getByText('기간 정보 없음')).toBeInTheDocument()
  })

  it('집계 시각과 보관 만료 시각을 안내한다', async () => {
    const { user } = setup()
    await reachQuestionScreen(user)
    const status = await openStatus(user)

    expect(await within(status).findByText(/이 집계는 2026-10-13 11:45에 만들어졌고 2026-10-13 11:50까지/)).toBeInTheDocument()
  })

  it('집계 실패(COLLECTION_UNAVAILABLE)는 서버 메시지를 그대로 보여 주고 다시 시도할 수 있다', async () => {
    const { user, api } = setup()
    await reachQuestionScreen(user)
    api.getCollectionSummary.mockRejectedValueOnce(
      new ApiError(
        'COLLECTION_UNAVAILABLE',
        '수집 현황을 가져오지 못했습니다. 잠시 뒤 다시 확인해 주세요.',
        503,
      ),
    )
    await user.click(screen.getByRole('button', { name: '수집 현황' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '수집 현황을 가져오지 못했습니다. 잠시 뒤 다시 확인해 주세요.',
    )
    // 자료가 없어서 0으로 대신 보여 주지 않는다.
    expect(screen.queryByText('72,140')).not.toBeInTheDocument()
  })

  it('질문 화면으로 돌아가도 입력하던 질문과 결과가 남아 있다', async () => {
    const { user } = setup()
    await reachQuestionScreen(user)
    await ask(user, '유지될 질문')
    await screen.findByLabelText('답변')

    await openStatus(user)
    await user.click(screen.getByRole('button', { name: '질문' }))

    expect(screen.getByRole('textbox', { name: /궁금한 점/ })).toHaveValue('유지될 질문')
    expect(screen.getByLabelText('답변')).toBeInTheDocument()
  })

  it('불러오지 못하면 다시 시도할 수 있다', async () => {
    const { user, api } = setup()
    await reachQuestionScreen(user)
    api.getCollectionSummary.mockRejectedValueOnce(
      new ApiError('UNKNOWN', '요청을 처리하지 못했습니다. (HTTP 404)', 404),
    )
    await user.click(screen.getByRole('button', { name: '수집 현황' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('HTTP 404')
    await user.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(await screen.findByText('72,140')).toBeInTheDocument()
  })
})

const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}/
const KNOWN_ID = '3f2b8c1e-5a4d-4c2b-9e1f-0a1b2c3d4e5f'

/** 문장이 화면 전체에 몇 번 나오는지 센다. */
function countText(text: string): number {
  return (document.body.textContent ?? '').split(text).length - 1
}

/** 같은 localStorage를 쓰는 다른 가짜 서버로 질문을 접수해 둔다(탭을 닫기 전에 보낸 질문). */
async function acceptElsewhere(scenario: MockScenarioId, inviteCode = 'friend'): Promise<string> {
  const server = createMockApi({ delayMs: 0, scenario: () => scenario })
  let requestId = ''
  await server
    .askQuestion({ question: '전에 보낸 질문' }, inviteCode, undefined, (a) => (requestId = a.requestId))
    .catch(() => undefined)
  return requestId
}

async function lookUp(user: ReturnType<typeof userEvent.setup>, requestId: string) {
  await user.type(screen.getByRole('textbox', { name: '접수 번호' }), requestId)
  await user.click(screen.getByRole('button', { name: '결과 확인' }))
}

describe('남은 횟수 갱신(spec D-23)', () => {
  it('코드별 한도 초과로 거절되면 남은 횟수를 0으로 바꾸고 횟수에 포함되지 않는다고 한 번 알린다', async () => {
    const { user } = setup('error-invite-limit')
    await reachQuestionScreen(user)
    await ask(user)

    const alert = await screen.findByRole('alert')
    expect(screen.getByText('오늘 남은 질문 0회')).toBeInTheDocument()
    expect(countText('이번 시도는 질문 횟수에 포함되지 않습니다.')).toBe(1)
    expect(alert).not.toHaveTextContent('포함되었습니다')
    expect(alert).not.toHaveTextContent('접수 번호')
  })

  it('한도 초과 본문에 남은 횟수가 없어도 0으로 표시한다', async () => {
    const { user, api } = setup()
    await reachQuestionScreen(user)
    api.askQuestion.mockRejectedValueOnce(new ApiError('GLOBAL_LIMIT_EXCEEDED', '오늘 전체 질문 횟수를 모두 사용했습니다.', 429))
    await ask(user)

    await screen.findByRole('alert')
    expect(screen.getByText('오늘 남은 질문 0회')).toBeInTheDocument()
  })

  it('처리 중 거절은 문구에 이미 있는 "포함되지 않습니다"를 한 번만 보여 준다', async () => {
    const { user } = setup('error-question-busy')
    await reachQuestionScreen(user)
    await ask(user)

    await screen.findByRole('alert')
    expect(countText('질문 횟수에 포함되지 않습니다')).toBe(1)
    expect(countText('포함되었습니다')).toBe(0)
  })
})

describe('횟수 포함 설명(spec D-23)', () => {
  it.each([
    ['notice', '안내'],
    ['clarification', '되묻기'],
  ] as const)('%s 응답에는 횟수에 포함된다는 설명이 한 번 붙는다', async (scenario, label) => {
    const { user } = setup(scenario)
    await reachQuestionScreen(user)
    await ask(user)

    await screen.findByLabelText(label)
    expect(countText('이 응답도 오늘 질문 횟수에 포함됩니다.')).toBe(1)
  })

  it('답변에는 설명을 붙이지 않는다', async () => {
    const { user } = setup('answer')
    await reachQuestionScreen(user)
    await ask(user)

    await screen.findByLabelText('답변')
    expect(countText('횟수에 포함')).toBe(0)
  })

  it('접수 뒤 실패에는 기존 문구 하나만 붙는다', async () => {
    const { user } = setup('error-agent')
    await reachQuestionScreen(user)
    await ask(user)

    await screen.findByRole('alert')
    expect(countText('이 질문은 접수되어 오늘 질문 횟수에 포함되었습니다.')).toBe(1)
    expect(countText('이 응답도 오늘 질문 횟수에 포함됩니다.')).toBe(0)
  })

  it('안내 문구에 이미 횟수 설명이 있으면 화면이 다시 붙이지 않는다', async () => {
    const { user, api } = setup()
    await reachQuestionScreen(user)
    const sample = await createMockApi({ delayMs: 0, scenario: () => 'notice' }).askQuestion({ question: 'x' }, 'friend')
    api.askQuestion.mockResolvedValueOnce({
      ...sample,
      message: '질문을 이해하지 못했습니다. 이 질문도 오늘 질문 횟수에 포함됩니다.',
    } as QuestionResponse)
    await ask(user)

    await screen.findByLabelText('안내')
    expect(countText('횟수에 포함')).toBe(1)
  })
})

describe('접수 번호 표시와 복구(spec D-23)', () => {
  it('접수되면 번호를 보여 주고 복사할 수 있으며, 같은 탭에 보관했다가 결과가 나오면 지운다', async () => {
    const { user, api } = setup()
    await reachQuestionScreen(user)
    const sample = await createMockApi({ delayMs: 0, scenario: () => 'answer' }).askQuestion({ question: 'x' }, 'friend')
    let release: (response: QuestionResponse) => void = () => {}
    api.askQuestion.mockImplementationOnce(
      (_request, _code, onStatus, onAccepted) =>
        new Promise<QuestionResponse>((resolve) => {
          onStatus?.('accepted')
          onAccepted?.({ requestId: KNOWN_ID, remainingToday: 41, acceptedAt: Date.now() })
          release = resolve
        }),
    )
    await ask(user, '보관할 질문')

    const status = await screen.findByRole('status', { name: '' })
    expect(within(status).getByText(KNOWN_ID)).toBeInTheDocument()
    expect(screen.getByText('오늘 남은 질문 41회')).toBeInTheDocument()
    expect(loadPendingQuestion('friend')).toMatchObject({ requestId: KNOWN_ID, question: '보관할 질문' })

    await user.click(within(status).getByRole('button', { name: '복사' }))
    expect(await screen.findByText('접수 번호를 복사했습니다.')).toBeInTheDocument()
    expect(await navigator.clipboard.readText()).toBe(KNOWN_ID)

    act(() => release({ ...sample, remaining_today: 41 }))
    await screen.findByLabelText('답변')
    expect(loadPendingQuestion('friend')).toBeNull()
  })

  it('처리 실패 화면에도 번호를 보여 주고, 결과가 정해졌으므로 보관한 값을 지운다', async () => {
    const { user } = setup('error-agent')
    await reachQuestionScreen(user)
    await ask(user)

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toMatch(UUID_PATTERN)
    expect(within(alert).getByRole('button', { name: '복사' })).toBeInTheDocument()
    expect(loadPendingQuestion('friend')).toBeNull()
  })

  it('시간 초과 화면에도 번호를 보여 주고, 다시 확인해 결과가 나올 때까지 보관한다', async () => {
    const { user } = setup('error-timeout')
    await reachQuestionScreen(user)
    await ask(user)

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toMatch(UUID_PATTERN)
    expect(loadPendingQuestion('friend')).not.toBeNull()

    await user.click(within(alert).getByRole('button', { name: '결과 다시 확인' }))
    await screen.findByLabelText('답변')
    expect(loadPendingQuestion('friend')).toBeNull()
  })

  it('같은 탭에서 새로고침하면 질문을 다시 보내지 않고 보관한 번호로 조회를 이어 간다', async () => {
    saveInviteCode('friend')
    const acceptedAt = Date.now() - 30_000
    savePendingQuestion('friend', { requestId: KNOWN_ID, remainingToday: 37, acceptedAt, question: '새로고침 전 질문' })
    const sample = await createMockApi({ delayMs: 0, scenario: () => 'notice' }).askQuestion({ question: 'x' }, 'friend')
    let release: (response: QuestionResponse) => void = () => {}
    const { api } = setup('answer', (prepared) => {
      prepared.resumeQuestion.mockImplementation(
        () => new Promise<QuestionResponse>((resolve) => (release = resolve)),
      )
    })

    const status = await screen.findByRole('status', { name: '' })
    expect(status).toHaveTextContent('접수한 질문의 결과를 이어서 확인하는 중')
    expect(status).toHaveTextContent(/\d+초 경과/)
    expect(within(status).getByText(KNOWN_ID)).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: /궁금한 점/ })).toHaveValue('새로고침 전 질문')
    expect(screen.getByText('오늘 남은 질문 37회')).toBeInTheDocument()
    expect(api.resumeQuestion).toHaveBeenCalledWith(
      expect.objectContaining({ requestId: KNOWN_ID, remainingToday: 37, acceptedAt }),
      'friend',
      expect.anything(),
    )

    act(() => release({ ...sample, remaining_today: 37 }))
    await screen.findByLabelText('안내')
    expect(api.askQuestion).not.toHaveBeenCalled()
    expect(loadPendingQuestion('friend')).toBeNull()
  })

  it('새로고침했을 때 접수 시각부터 420초가 이미 지났으면 조회하지 않고 "결과 다시 확인" 버튼을 보인다', async () => {
    saveInviteCode('friend')
    savePendingQuestion('friend', {
      requestId: KNOWN_ID,
      remainingToday: 37,
      acceptedAt: Date.now() - 421_000,
      question: '오래된 질문',
    })
    const sample = await createMockApi({ delayMs: 0, scenario: () => 'answer' }).askQuestion({ question: 'x' }, 'friend')
    const { user, api } = setup('answer', (prepared) => {
      prepared.checkQuestion.mockResolvedValueOnce({ ...sample, remaining_today: 37 })
    })

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('처리가 오래 걸리고 있습니다')
    expect(within(alert).getByText(KNOWN_ID)).toBeInTheDocument()
    await user.click(within(alert).getByRole('button', { name: '결과 다시 확인' }))

    await screen.findByLabelText('답변')
    expect(api.checkQuestion).toHaveBeenCalledWith(KNOWN_ID, 37, 'friend')
    expect(api.askQuestion).not.toHaveBeenCalled()
  })

  it('다른 초대 코드로 들어오면 보관한 질문을 이어 보지 않는다', async () => {
    saveInviteCode('other')
    savePendingQuestion('friend', { requestId: KNOWN_ID, remainingToday: 37, acceptedAt: Date.now(), question: 'q' })
    const { api } = setup()

    await screen.findByRole('heading', { name: '조건' })
    expect(api.resumeQuestion).not.toHaveBeenCalled()
    expect(screen.queryByText(KNOWN_ID)).not.toBeInTheDocument()
  })

  it('저장소를 쓸 수 없어도 질문·번호 표시·결과 다시 확인이 동작한다', async () => {
    const blocked = () => {
      throw new Error('blocked')
    }
    const spies = [
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(blocked),
      vi.spyOn(Storage.prototype, 'getItem').mockImplementation(blocked),
      vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(blocked),
    ]
    try {
      const { user } = setup('error-timeout')
      await reachQuestionScreen(user)
      await ask(user)

      const alert = await screen.findByRole('alert')
      expect(alert.textContent).toMatch(UUID_PATTERN)
      await user.click(within(alert).getByRole('button', { name: '결과 다시 확인' }))
      expect(await screen.findByLabelText('답변')).toBeInTheDocument()
    } finally {
      spies.forEach((spy) => spy.mockRestore())
    }
  })

  it('탭을 다시 연 뒤 접수 번호로 결과를 확인하면 질문을 다시 보내지 않고 후기까지 보여 준다', async () => {
    const requestId = await acceptElsewhere('notice')
    const { user, api } = setup()
    await reachQuestionScreen(user)
    expect(screen.getByText(/질문 횟수에 영향이 없습니다/)).toBeInTheDocument()

    await lookUp(user, requestId.toUpperCase())

    expect(await screen.findByLabelText('안내')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: /어땠나요/ })).toBeInTheDocument()
    expect(countText('이 응답도 오늘 질문 횟수에 포함됩니다.')).toBe(1)
    expect(api.checkQuestion).toHaveBeenCalledWith(requestId, null, 'friend')
    expect(api.askQuestion).not.toHaveBeenCalled()
    expect(screen.getByText('오늘 남은 질문 50회')).toBeInTheDocument()
  })

  it('형식이 맞지 않는 번호는 조회하지 않고 안내한다', async () => {
    const { user, api } = setup()
    await reachQuestionScreen(user)
    await lookUp(user, 'mock-request-1')

    expect(await screen.findByText(/접수 번호 형식이 맞지 않습니다/)).toBeInTheDocument()
    expect(api.checkQuestion).not.toHaveBeenCalled()
  })

  it('없는 번호와 다른 초대 코드의 번호는 같은 안내를 보여 주고 횟수 안내나 번호는 붙이지 않는다', async () => {
    const otherId = await acceptElsewhere('answer', 'other')
    const { user } = setup()
    await reachQuestionScreen(user)

    for (const requestId of ['11111111-1111-4111-8111-111111111111', otherId]) {
      const input = screen.getByRole('textbox', { name: '접수 번호' })
      await user.clear(input)
      await lookUp(user, requestId)
      const alert = await screen.findByRole('alert')
      expect(alert).toHaveTextContent('이 초대 코드로 접수한 질문 중에서 이 번호를 찾지 못했습니다')
      expect(alert).not.toHaveTextContent('포함되었습니다')
      expect(within(alert).queryByRole('button', { name: '복사' })).not.toBeInTheDocument()
    }
  })
})

describe('답변 화면의 접수 번호와 새로고침 뒤 결과 유지', () => {
  it('답변 아래에 접수 번호와 복사 버튼, 다시 보는 방법을 보여 준다', async () => {
    const { user } = setup('answer')
    await reachQuestionScreen(user)
    await ask(user)

    await screen.findByLabelText('답변')
    const box = document.querySelector('.result-id') as HTMLElement
    expect(box.textContent).toMatch(UUID_PATTERN)
    expect(within(box).getByRole('button', { name: '복사' })).toBeInTheDocument()
    expect(box).toHaveTextContent("'접수 번호로 결과 확인'에 이 번호를 넣으면")
    expect(box).not.toHaveTextContent('새로고침 전에 받은 결과입니다.')
  })

  it('접수 번호로 확인한 결과에도 그 번호를 보여 준다', async () => {
    const requestId = await acceptElsewhere('answer')
    const { user } = setup()
    await reachQuestionScreen(user)
    await lookUp(user, requestId)

    await screen.findByLabelText('답변')
    expect(within(document.querySelector('.result-id') as HTMLElement).getByText(requestId)).toBeInTheDocument()
  })

  it('같은 탭에서 새로고침해도 마지막 결과를 다시 보여 주고 질문이나 조회를 다시 보내지 않는다', async () => {
    const first = setup('notice')
    await reachQuestionScreen(first.user)
    await ask(first.user, '보관할 질문')
    await screen.findByLabelText('안내')
    const requestId = (document.querySelector('.result-id code') as HTMLElement).textContent

    cleanup() // 새로고침: 화면은 사라지고 같은 탭의 sessionStorage는 남는다.
    const second = setup('answer')

    expect(await screen.findByLabelText('안내')).toBeInTheDocument()
    expect(screen.getByText('새로고침 전에 받은 결과입니다.')).toBeInTheDocument()
    expect(within(document.querySelector('.result-id') as HTMLElement).getByText(requestId!)).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: /궁금한 점/ })).toHaveValue('보관할 질문')
    expect(screen.getByText('오늘 남은 질문 49회')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: /어땠나요/ })).toBeInTheDocument()
    expect(countText('이 응답도 오늘 질문 횟수에 포함됩니다.')).toBe(1)
    expect(second.api.askQuestion).not.toHaveBeenCalled()
    expect(second.api.checkQuestion).not.toHaveBeenCalled()
    expect(second.api.resumeQuestion).not.toHaveBeenCalled()
  })

  it('새 질문이 접수되면 이전 결과는 새로고침 뒤 다시 보여 주지 않는다', async () => {
    const first = setup('answer')
    await reachQuestionScreen(first.user)
    await ask(first.user)
    await screen.findByLabelText('답변')

    first.setScenario('error-agent')
    await first.user.click(screen.getByRole('button', { name: '질문하기' }))
    await screen.findByRole('alert')

    cleanup()
    setup()
    await screen.findByRole('heading', { name: '조건' })
    expect(screen.queryByLabelText('답변')).not.toBeInTheDocument()
    expect(screen.queryByText('새로고침 전에 받은 결과입니다.')).not.toBeInTheDocument()
  })

  it('초대 코드를 바꾸면 보관한 결과를 지우고, 다른 초대 코드로는 보여 주지 않는다', async () => {
    const first = setup('answer')
    await reachQuestionScreen(first.user)
    await ask(first.user)
    await screen.findByLabelText('답변')
    expect(loadLastResult('friend')).not.toBeNull()

    await first.user.click(screen.getByRole('button', { name: '초대 코드 바꾸기' }))
    expect(loadLastResult('friend')).toBeNull()
  })

  it('저장소를 쓸 수 없어도 답변과 번호는 보이고, 새로고침 뒤에는 빈 질문 화면이다', async () => {
    const blocked = () => {
      throw new Error('blocked')
    }
    const spies = [
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(blocked),
      vi.spyOn(Storage.prototype, 'getItem').mockImplementation(blocked),
      vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(blocked),
    ]
    try {
      const first = setup('answer')
      await reachQuestionScreen(first.user)
      await ask(first.user)
      await screen.findByLabelText('답변')
      expect((document.querySelector('.result-id') as HTMLElement).textContent).toMatch(UUID_PATTERN)

      cleanup()
      setup()
      // 초대 코드도 저장하지 못했으므로 첫 화면부터 다시 시작한다.
      expect(await screen.findByRole('heading', { name: '초대 코드 입력' })).toBeInTheDocument()
    } finally {
      spies.forEach((spy) => spy.mockRestore())
    }
  })
})
