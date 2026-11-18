import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import App from './App'
import { ApiError } from './api'
import { createMockApi, MOCK_LOGIN, MOCK_LOGIN_INVITE_CODE } from './api/mock'
import type { ErApi, LoginResponse } from './api/types'
import { loadInviteCode, loadUsername, saveInviteCode } from './lib/session'

// 로그인 화면(M1-045, spec D-26). 가짜 응답 모드의 계정은 api/mock.ts의 MOCK_LOGIN이다.

type User = ReturnType<typeof userEvent.setup>

function setup(overrides: Partial<ErApi> = {}) {
  const base = createMockApi({ delayMs: 0 })
  const api = {
    ...base,
    login: vi.fn<ErApi['login']>(overrides.login ?? base.login),
    verifyInvite: vi.fn<ErApi['verifyInvite']>(overrides.verifyInvite ?? base.verifyInvite),
    askQuestion: vi.fn<ErApi['askQuestion']>(overrides.askQuestion ?? base.askQuestion),
  }
  const user = userEvent.setup()
  render(<App api={api} />)
  return { api, user }
}

async function logIn(user: User, username: string = MOCK_LOGIN.username, password: string = MOCK_LOGIN.password) {
  await user.type(screen.getByLabelText('아이디'), username)
  await user.type(screen.getByLabelText('비밀번호'), password)
  await user.click(screen.getByRole('button', { name: '로그인' }))
}

const usernameInput = () => screen.getByLabelText('아이디') as HTMLInputElement
const passwordInput = () => screen.getByLabelText('비밀번호') as HTMLInputElement

/** 탭 저장소와 기기 저장소 어디에도 이 값이 없는지 본다. */
function storedAnywhere(value: string): boolean {
  for (const storage of [window.sessionStorage, window.localStorage]) {
    for (let i = 0; i < storage.length; i += 1) {
      const key = storage.key(i)!
      if (key.includes(value) || (storage.getItem(key) ?? '').includes(value)) return true
    }
  }
  return false
}

describe('로그인 첫 화면', () => {
  it('아이디·비밀번호 입력란과 안내, 초대 코드로 입장하는 전환을 보여 준다', () => {
    setup()
    expect(screen.getByRole('heading', { name: '로그인' })).toBeInTheDocument()
    expect(screen.getByText('아이디·비밀번호는 운영자가 발급합니다.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '초대 코드로 입장' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '조건' })).not.toBeInTheDocument()
  })

  it('비밀번호는 가려지고 브라우저 자동 완성 힌트가 붙는다', () => {
    setup()
    expect(passwordInput()).toHaveAttribute('type', 'password')
    expect(passwordInput()).toHaveAttribute('autocomplete', 'current-password')
    expect(usernameInput()).toHaveAttribute('autocomplete', 'username')
  })

  it('아이디나 비밀번호가 비어 있으면 로그인 버튼을 누를 수 없다', async () => {
    const { user, api } = setup()
    const button = screen.getByRole('button', { name: '로그인' })
    expect(button).toBeDisabled()
    await user.type(usernameInput(), 'demo')
    expect(button).toBeDisabled()
    await user.type(passwordInput(), 'x')
    expect(button).toBeEnabled()
    expect(api.login).not.toHaveBeenCalled()
  })
})

describe('로그인 성공', () => {
  it('받은 초대 코드로 기존 질문 화면에 들어가고 상단에 아이디와 로그아웃이 보인다', async () => {
    const { user, api } = setup()
    await logIn(user)

    expect(await screen.findByRole('heading', { name: '조건' })).toBeInTheDocument()
    expect(api.login).toHaveBeenCalledWith('demo', 'demo-password')
    expect(await screen.findByText('오늘 남은 질문 50회')).toBeInTheDocument()
    expect(screen.getByText('demo', { selector: 'strong' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '로그아웃' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '초대 코드 바꾸기' })).not.toBeInTheDocument()
  })

  it('받은 초대 코드를 탭 저장소에 두고 질문은 그 코드로 보낸다(X-Invite-Code가 되는 값)', async () => {
    const { user, api } = setup()
    await logIn(user)
    await screen.findByRole('heading', { name: '조건' })
    expect(loadInviteCode()).toBe(MOCK_LOGIN_INVITE_CODE)
    expect(loadUsername()).toBe('demo')

    await user.type(screen.getByRole('textbox', { name: /궁금한 점/ }), '수집된 경기는 몇 판이야?')
    await user.click(screen.getByRole('button', { name: '질문하기' }))
    await screen.findByLabelText('답변')
    expect(api.askQuestion.mock.calls[0][1]).toBe(MOCK_LOGIN_INVITE_CODE)
    // 로그인은 질문 횟수를 소비하지 않고, 질문 한 번이 접수되면 한 번만 줄어든다.
    expect(screen.getByText('오늘 남은 질문 49회')).toBeInTheDocument()
  })

  it('비밀번호는 어디에도 저장하지 않는다(성공)', async () => {
    const { user } = setup()
    await logIn(user, 'demo', MOCK_LOGIN.password)
    await screen.findByRole('heading', { name: '조건' })
    expect(storedAnywhere(MOCK_LOGIN.password)).toBe(false)
    expect(document.body.innerHTML).not.toContain(MOCK_LOGIN.password)
  })

  it('새로고침하면 로그인 상태(초대 코드와 아이디)가 같은 탭에서 이어진다', async () => {
    const first = setup()
    await logIn(first.user)
    await screen.findByRole('heading', { name: '조건' })

    cleanup()
    setup()
    expect(await screen.findByRole('heading', { name: '조건' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '로그아웃' })).toBeInTheDocument()
    expect(screen.getByText('demo', { selector: 'strong' })).toBeInTheDocument()
  })

  it('진행 중에는 중복 제출을 막고 버튼이 로그인 중으로 바뀐다', async () => {
    let resolve!: (value: LoginResponse) => void
    const login = vi.fn<ErApi['login']>(() => new Promise<LoginResponse>((r) => (resolve = r)))
    const { user } = setup({ login })

    await user.type(usernameInput(), 'demo')
    await user.type(passwordInput(), 'demo-password{Enter}')
    expect(screen.getByRole('button', { name: '로그인 중…' })).toBeDisabled()
    // Enter를 다시 눌러도 버튼을 눌러도 두 번째 요청은 나가지 않는다.
    await user.type(passwordInput(), '{Enter}')
    await user.click(screen.getByRole('button', { name: '로그인 중…' }))
    expect(login).toHaveBeenCalledTimes(1)

    resolve({ invite_code: 'linked', remaining_today: 7 })
    expect(await screen.findByText('오늘 남은 질문 7회')).toBeInTheDocument()
    expect(login).toHaveBeenCalledTimes(1)
  })

  it('아이디 앞뒤 공백은 지우고 비밀번호는 그대로 보낸다', async () => {
    const { user, api } = setup()
    await logIn(user, '  demo  ', ' demo-password')
    // 공백이 붙은 비밀번호는 틀린 것으로 서버가 판단한다. 화면은 비밀번호를 바꾸지 않는다.
    await screen.findByRole('alert')
    expect(api.login).toHaveBeenCalledWith('demo', ' demo-password')
  })
})

describe('로그인 실패', () => {
  it.each([
    ['틀린 비밀번호', 'demo', 'wrong', '아이디 또는 비밀번호가 올바르지 않습니다.'],
    ['없는 아이디', 'nobody', 'x', '아이디 또는 비밀번호가 올바르지 않습니다.'],
    ['시도 횟수 초과', 'locked', 'x', '로그인 시도가 너무 많습니다. 10분 뒤 다시 시도해 주세요.'],
  ])('%s: 서버 message를 그대로 보여 주고 로그인 화면에 머문다', async (_name, username, password, message) => {
    const { user } = setup()
    await logIn(user, username, password)

    expect(await screen.findByRole('alert')).toHaveTextContent(message)
    expect(screen.getByRole('heading', { name: '로그인' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '조건' })).not.toBeInTheDocument()
    expect(loadInviteCode()).toBeNull()
    expect(loadUsername()).toBeNull()
    // 비밀번호 입력란은 비워지고 아이디는 남으며, 다시 시도할 수 있다.
    expect(passwordInput()).toHaveValue('')
    expect(usernameInput()).toHaveValue(username)
    expect(screen.getByRole('button', { name: '로그인' })).toBeDisabled()
    expect(storedAnywhere(password)).toBe(false)
  })

  it('형식 오류(VALIDATION_FAILED)는 서버 message와 입력 규칙을 함께 보여 준다', async () => {
    const { user } = setup()
    await logIn(user, 'ab', 'x')
    expect(await screen.findByRole('alert')).toHaveTextContent('요청 형식이 올바르지 않습니다.')
    expect(screen.getByText(/소문자·숫자·_·- 3~32자/)).toBeInTheDocument()
  })

  it('실패한 뒤 다시 입력해 로그인하면 들어간다', async () => {
    const { user } = setup()
    await logIn(user, 'demo', 'wrong')
    await screen.findByRole('alert')
    await user.type(passwordInput(), MOCK_LOGIN.password)
    await user.click(screen.getByRole('button', { name: '로그인' }))
    expect(await screen.findByRole('heading', { name: '조건' })).toBeInTheDocument()
  })

  it('네트워크 실패는 기존 방식의 안내를 보여 주고 비밀번호를 비운다', async () => {
    const { user } = setup({
      login: vi.fn<ErApi['login']>().mockRejectedValue(
        new ApiError('NETWORK_ERROR', '서버에 연결하지 못했습니다. 네트워크를 확인해 주세요.'),
      ),
    })
    await logIn(user, 'demo', 'secret-pw')
    expect(await screen.findByRole('alert')).toHaveTextContent('서버에 연결하지 못했습니다. 네트워크를 확인해 주세요.')
    expect(passwordInput()).toHaveValue('')
    expect(storedAnywhere('secret-pw')).toBe(false)
  })

  it('모르는 오류 code도 서버 message를 그대로 보여 준다', async () => {
    const { user } = setup({
      login: vi.fn<ErApi['login']>().mockRejectedValue(new ApiError('UNKNOWN', '서버가 응답하지 못했습니다.', 500)),
    })
    await logIn(user)
    expect(await screen.findByRole('alert')).toHaveTextContent('서버가 응답하지 못했습니다.')
  })
})

describe('로그인을 쓸 수 없을 때(LOGIN_DISABLED)', () => {
  it('서버 안내와 함께 자동으로 초대 코드 입장을 보여 주고, 초대 코드로 들어갈 수 있다', async () => {
    const { user, api } = setup()
    await logIn(user, 'disabled', 'x')

    expect(await screen.findByRole('heading', { name: '초대 코드 입력' })).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent(
      '지금은 로그인을 사용할 수 없습니다. 초대 코드로 입장해 주세요.',
    )
    expect(screen.queryByRole('heading', { name: '로그인' })).not.toBeInTheDocument()
    // 로그인을 다시 시도해 봐야 소용없으므로 로그인으로 돌아가는 전환은 숨긴다.
    expect(screen.queryByRole('button', { name: '아이디·비밀번호로 로그인' })).not.toBeInTheDocument()
    expect(storedAnywhere('x')).toBe(false)

    await user.type(screen.getByLabelText('초대 코드'), 'friend')
    await user.click(screen.getByRole('button', { name: '확인' }))
    expect(await screen.findByRole('heading', { name: '조건' })).toBeInTheDocument()
    expect(api.verifyInvite).toHaveBeenCalledWith('friend')
    // 초대 코드로 들어왔으므로 아이디는 없고 기존 "초대 코드 바꾸기"가 보인다.
    expect(screen.getByRole('button', { name: '초대 코드 바꾸기' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '로그아웃' })).not.toBeInTheDocument()
  })

  it('로그아웃해도 로그인이 꺼져 있으면 초대 코드 입장으로 돌아간다', async () => {
    const { user } = setup()
    await logIn(user, 'disabled', 'x')
    await screen.findByRole('heading', { name: '초대 코드 입력' })
    await user.type(screen.getByLabelText('초대 코드'), 'friend')
    await user.click(screen.getByRole('button', { name: '확인' }))
    await screen.findByRole('heading', { name: '조건' })

    await user.click(screen.getByRole('button', { name: '초대 코드 바꾸기' }))
    expect(screen.getByRole('heading', { name: '초대 코드 입력' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '아이디·비밀번호로 로그인' })).not.toBeInTheDocument()
  })
})

describe('초대 코드 입장 회귀', () => {
  it('전환한 뒤 초대 코드를 입력하면 기존처럼 들어가고 아이디는 저장되지 않는다', async () => {
    const { user, api } = setup()
    await user.click(screen.getByRole('button', { name: '초대 코드로 입장' }))
    await user.type(screen.getByLabelText('초대 코드'), 'friend')
    await user.click(screen.getByRole('button', { name: '확인' }))

    expect(await screen.findByRole('heading', { name: '조건' })).toBeInTheDocument()
    expect(api.verifyInvite).toHaveBeenCalledWith('friend')
    expect(api.login).not.toHaveBeenCalled()
    expect(loadInviteCode()).toBe('friend')
    expect(loadUsername()).toBeNull()
    expect(screen.queryByText('로그인:')).not.toBeInTheDocument()
  })

  it('잘못된 초대 코드의 안내는 기존 그대로다', async () => {
    const { user } = setup()
    await user.click(screen.getByRole('button', { name: '초대 코드로 입장' }))
    await user.type(screen.getByLabelText('초대 코드'), 'INVALID')
    await user.click(screen.getByRole('button', { name: '확인' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('초대 코드를 확인해 주세요.')
  })

  it('로그인 화면과 초대 코드 화면을 오갈 수 있다', async () => {
    const { user } = setup()
    await user.click(screen.getByRole('button', { name: '초대 코드로 입장' }))
    expect(screen.getByRole('heading', { name: '초대 코드 입력' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '아이디·비밀번호로 로그인' }))
    expect(screen.getByRole('heading', { name: '로그인' })).toBeInTheDocument()
  })

  it('이전에 저장된 초대 코드(예전 방식)로 새로고침하면 아이디 없이 질문 화면이 이어진다', async () => {
    saveInviteCode('friend')
    setup()
    expect(await screen.findByRole('heading', { name: '조건' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '초대 코드 바꾸기' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '로그아웃' })).not.toBeInTheDocument()
  })

  it('초대 코드로 입장한 뒤 "초대 코드 바꾸기"는 초대 코드 화면으로 돌아간다(기존 흐름)', async () => {
    const { user } = setup()
    await user.click(screen.getByRole('button', { name: '초대 코드로 입장' }))
    await user.type(screen.getByLabelText('초대 코드'), 'friend')
    await user.click(screen.getByRole('button', { name: '확인' }))
    await screen.findByRole('heading', { name: '조건' })

    await user.click(screen.getByRole('button', { name: '초대 코드 바꾸기' }))
    expect(screen.getByRole('heading', { name: '초대 코드 입력' })).toBeInTheDocument()
    expect(loadInviteCode()).toBeNull()
  })
})

describe('로그아웃', () => {
  it('탭 저장소의 코드·아이디·진행 기록을 지우고 로그인 화면으로 돌아간다', async () => {
    const { user } = setup()
    await logIn(user)
    await screen.findByRole('heading', { name: '조건' })
    await user.type(screen.getByRole('textbox', { name: /궁금한 점/ }), '수집된 경기는 몇 판이야?')
    await user.click(screen.getByRole('button', { name: '질문하기' }))
    await screen.findByLabelText('답변')
    // 다시 보기용 마지막 결과가 탭 저장소에 있다.
    expect(Object.keys(window.sessionStorage).some((k) => k.includes('last-result'))).toBe(true)

    await user.click(screen.getByRole('button', { name: '로그아웃' }))

    expect(screen.getByRole('heading', { name: '로그인' })).toBeInTheDocument()
    expect(loadInviteCode()).toBeNull()
    expect(loadUsername()).toBeNull()
    expect(window.sessionStorage.length).toBe(0)
    expect(screen.queryByRole('button', { name: '로그아웃' })).not.toBeInTheDocument()
    expect(passwordInput()).toHaveValue('')
    expect(usernameInput()).toHaveValue('')
  })

  it('로그아웃 뒤 새로고침해도 로그인 화면이다', async () => {
    const first = setup()
    await logIn(first.user)
    await screen.findByRole('heading', { name: '조건' })
    await first.user.click(screen.getByRole('button', { name: '로그아웃' }))

    cleanup()
    setup()
    expect(screen.getByRole('heading', { name: '로그인' })).toBeInTheDocument()
  })

  it('로그아웃하면 이전 계정의 진행 중인 질문을 다음 로그인에서 이어 보지 않는다', async () => {
    const { user } = setup()
    await logIn(user)
    await screen.findByRole('heading', { name: '조건' })
    await user.click(screen.getByRole('button', { name: '로그아웃' }))
    await logIn(user)
    await screen.findByRole('heading', { name: '조건' })
    expect(screen.queryByText(/새로고침 전에 받은 결과/)).not.toBeInTheDocument()
  })
})

describe('연결된 초대 코드가 거절될 때', () => {
  it('서버가 INVITE_INVALID를 주면 로그인 첫 화면으로 돌아가 안내한다', async () => {
    const { user, api } = setup()
    await logIn(user)
    await screen.findByRole('heading', { name: '조건' })
    api.askQuestion.mockRejectedValueOnce(new ApiError('INVITE_INVALID', '초대 코드를 확인해 주세요.', 401))

    await user.type(screen.getByRole('textbox', { name: /궁금한 점/ }), '질문')
    await user.click(screen.getByRole('button', { name: '질문하기' }))

    await waitFor(() => expect(screen.getByRole('heading', { name: '로그인' })).toBeInTheDocument())
    expect(screen.getByRole('alert')).toHaveTextContent('연결된 초대 코드가 유효하지 않습니다')
    expect(loadInviteCode()).toBeNull()
    expect(loadUsername()).toBeNull()
  })

  it('초대 코드로 입장한 사용자는 기존처럼 초대 코드 화면으로 돌아간다', async () => {
    saveInviteCode('friend')
    const { user, api } = setup()
    await screen.findByRole('heading', { name: '조건' })
    api.askQuestion.mockRejectedValueOnce(new ApiError('INVITE_INVALID', '초대 코드를 확인해 주세요.', 401))
    await user.type(screen.getByRole('textbox', { name: /궁금한 점/ }), '질문')
    await user.click(screen.getByRole('button', { name: '질문하기' }))
    expect(await screen.findByRole('heading', { name: '초대 코드 입력' })).toBeInTheDocument()
    expect(within(screen.getByRole('main')).getByRole('alert')).toHaveTextContent('초대 코드가 유효하지 않습니다')
  })
})
