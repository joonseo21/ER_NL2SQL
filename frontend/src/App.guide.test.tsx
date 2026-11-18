import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import App from './App'
import { createMockApi } from './api/mock'
import type { ErApi, QuestionResponse } from './api/types'
import { SERVICE_GUIDE } from './content/serviceGuide'

function setup() {
  const base = createMockApi({ delayMs: 0, scenario: () => 'answer' })
  const api = { ...base, askQuestion: vi.fn<ErApi['askQuestion']>(base.askQuestion) }
  const user = userEvent.setup()
  render(<App api={api} />)
  return { api, user }
}

async function reachQuestionScreen(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: '초대 코드로 입장' }))
  await user.type(screen.getByLabelText('초대 코드'), 'friend')
  await user.click(screen.getByRole('button', { name: '확인' }))
  await screen.findByRole('heading', { name: '조건' })
}

const guideButton = () => screen.getByRole('button', { name: '서비스 안내' })
const guidePanel = () => screen.queryByRole('region', { name: '서비스 안내' })

describe('서비스 안내(spec D-25, M1-044)', () => {
  it('첫 화면(로그인)에서 열고 닫을 수 있다', async () => {
    const { user } = setup()
    expect(screen.getByRole('heading', { name: '로그인' })).toBeInTheDocument()
    expect(guideButton()).toHaveAttribute('aria-expanded', 'false')
    expect(guidePanel()).not.toBeInTheDocument()

    await user.click(guideButton())
    expect(guidePanel()).toBeInTheDocument()
    expect(guideButton()).toHaveAttribute('aria-expanded', 'true')
    // 안내를 열어도 첫 화면은 그대로다.
    expect(screen.getByRole('heading', { name: '로그인' })).toBeInTheDocument()

    await user.click(within(guidePanel()!).getByRole('button', { name: '닫기' }))
    expect(guidePanel()).not.toBeInTheDocument()
    expect(guideButton()).toHaveAttribute('aria-expanded', 'false')
  })

  it('머리글 버튼을 다시 눌러도 닫힌다', async () => {
    const { user } = setup()
    await user.click(guideButton())
    await user.click(guideButton())
    expect(guidePanel()).not.toBeInTheDocument()
  })

  it('제목 구조를 갖추고 안내문의 모든 제목과 문장을 그대로 보여 준다', async () => {
    const { user } = setup()
    await user.click(guideButton())
    const panel = guidePanel()!

    expect(within(panel).getByRole('heading', { level: 2, name: '서비스 안내' })).toBeInTheDocument()
    const titles = within(panel)
      .getAllByRole('heading', { level: 3 })
      .map((h) => h.textContent)
    expect(titles).toEqual(SERVICE_GUIDE.map((s) => s.title))
    for (const section of SERVICE_GUIDE) {
      for (const line of section.lines) {
        expect(within(panel).getByText(line)).toBeInTheDocument()
      }
    }
  })

  it('안내문에 쌓인 경기 수 같은 변하는 숫자를 쓰지 않고 수집 현황을 가리킨다', () => {
    const text = SERVICE_GUIDE.flatMap((s) => s.lines).join('\n')
    expect(text).toContain("'수집 현황' 탭에서 볼 수 있습니다")
    expect(text).not.toMatch(/\d[\d,]*\s*(판|경기)이 (쌓|모였)/)
    expect(text).not.toMatch(/\d{1,3}(,\d{3})+/) // 72,140 같은 집계 수치
  })

  it('제목을 누르면 내용을 접고 펼 수 있고 처음에는 첫 제목만 펼쳐져 있다', async () => {
    const { user } = setup()
    await user.click(guideButton())
    const sections = guidePanel()!.querySelectorAll('details')
    expect(sections).toHaveLength(SERVICE_GUIDE.length)
    expect(sections[0]).toHaveAttribute('open')
    expect(sections[1]).not.toHaveAttribute('open')

    await user.click(within(sections[1] as HTMLElement).getByText(SERVICE_GUIDE[1].title))
    expect(sections[1]).toHaveAttribute('open')
    await user.click(within(sections[1] as HTMLElement).getByText(SERVICE_GUIDE[1].title))
    expect(sections[1]).not.toHaveAttribute('open')
  })

  it('키보드로 열면 안내 제목으로 초점이 옮겨 가고, Esc로 닫으면 안내 버튼으로 초점이 돌아온다', async () => {
    const { user } = setup()
    guideButton().focus()
    await user.keyboard('{Enter}')

    const heading = within(guidePanel()!).getByRole('heading', { level: 2, name: '서비스 안내' })
    expect(heading).toHaveFocus()

    await user.tab() // 닫기 버튼
    expect(within(guidePanel()!).getByRole('button', { name: '닫기' })).toHaveFocus()
    await user.keyboard('{Escape}')

    expect(guidePanel()).not.toBeInTheDocument()
    expect(guideButton()).toHaveFocus()
  })

  it('닫기 버튼으로 닫아도 안내 버튼으로 초점이 돌아온다', async () => {
    const { user } = setup()
    await user.click(guideButton())
    await user.click(within(guidePanel()!).getByRole('button', { name: '닫기' }))
    expect(guideButton()).toHaveFocus()
  })

  it('질문 화면에서 열고 닫아도 입력해 둔 질문과 고른 조건이 남는다', async () => {
    const { user } = setup()
    await reachQuestionScreen(user)
    await user.selectOptions(screen.getByLabelText('티어'), '다이아몬드')
    await user.type(screen.getByRole('textbox', { name: /궁금한 점/ }), '아드리아나 승률은?')

    await user.click(guideButton())
    expect(guidePanel()).toBeInTheDocument()
    await user.keyboard('{Escape}')

    expect(screen.getByRole('textbox', { name: /궁금한 점/ })).toHaveValue('아드리아나 승률은?')
    expect(screen.getByLabelText('티어')).toHaveValue('다이아몬드')
  })

  it('질문을 처리하는 중에 열고 닫아도 진행 상태가 그대로이고 결과를 받는다', async () => {
    const { user, api } = setup()
    await reachQuestionScreen(user)
    const sample = await createMockApi({ delayMs: 0, scenario: () => 'answer' }).askQuestion({ question: 'x' }, 'friend')
    let release: (response: QuestionResponse) => void = () => {}
    api.askQuestion.mockImplementationOnce(
      (_request, _code, onStatus, onAccepted) =>
        new Promise<QuestionResponse>((resolve) => {
          onStatus?.('processing')
          onAccepted?.({ requestId: '3f2b8c1e-5a4d-4c2b-9e1f-0a1b2c3d4e5f', remainingToday: 49, acceptedAt: Date.now() })
          release = resolve
        }),
    )
    await user.type(screen.getByRole('textbox', { name: /궁금한 점/ }), '수집된 경기는 몇 판이야?')
    await user.click(screen.getByRole('button', { name: '질문하기' }))
    expect(await screen.findByText(/답변을 만드는 중/)).toBeInTheDocument()

    await user.click(guideButton())
    await user.click(within(guidePanel()!).getByRole('button', { name: '닫기' }))
    expect(screen.getByText(/답변을 만드는 중/)).toBeInTheDocument()
    expect(screen.getByText('3f2b8c1e-5a4d-4c2b-9e1f-0a1b2c3d4e5f')).toBeInTheDocument()

    act(() => release(sample))
    expect(await screen.findByLabelText('답변')).toBeInTheDocument()
    expect(api.askQuestion).toHaveBeenCalledTimes(1)
  })

  it('수집 현황 탭에서도 열린다', async () => {
    const { user } = setup()
    await reachQuestionScreen(user)
    await user.click(screen.getByRole('button', { name: '수집 현황' }))
    await user.click(guideButton())
    expect(guidePanel()).toBeInTheDocument()
  })
})
