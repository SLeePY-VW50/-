import { beforeEach, describe, expect, it, vi } from 'vitest'

declare const require: (id: string) => { readFileSync(path: string, encoding: string): string }
const { readFileSync } = require('node:fs')
const loginWxml = readFileSync('miniprogram/pages/login/index.wxml', 'utf8')
const loginWxss = readFileSync('miniprogram/pages/login/index.wxss', 'utf8')
const bindWxml = readFileSync('miniprogram/pages/bind/index.wxml', 'utf8')

describe('WeChat login flow', () => {
  beforeEach(() => { vi.resetModules(); vi.clearAllMocks() })
  it('saves bound login and returns ticket for binding without saving a token', async () => {
    const set = vi.fn()
    const user = { userId: 1, account: '20240001', name: '演示学生', roles: ['STUDENT'], status: 'ACTIVE' }
    vi.stubGlobal('wx', {
      login: (options: WechatMiniprogram.LoginOption) => options.success?.({ code: 'code-1', errMsg: 'login:ok' }),
      setStorageSync: set,
      request: (options: WechatMiniprogram.RequestOption) => options.success?.({ statusCode: 200, data: { code: 0, message: 'success', data: { bound: false, token: null, user: null, bindTicket: 'ticket-1', expiresIn: 300 } } } as never),
      getStorageSync: () => '', removeStorageSync: vi.fn(),
    })
    vi.stubGlobal('getCurrentPages', () => [])
    const flow = await import('../miniprogram/auth/login-flow')
    await expect(flow.loginWithWeChat()).resolves.toEqual({ bound: false, bindTicket: 'ticket-1' })
    expect(set).not.toHaveBeenCalled()
  })

  it('saves the JWT and public user after a bound WeChat login', async () => {
    const set = vi.fn()
    const user = { userId: 1, account: '20240001', name: '演示学生', roles: ['STUDENT' as const], status: 'ACTIVE' as const }
    vi.stubGlobal('wx', {
      login: (options: WechatMiniprogram.LoginOption) => options.success?.({ code: 'code-1', errMsg: 'login:ok' }),
      setStorageSync: set,
      request: (options: WechatMiniprogram.RequestOption) => options.success?.({ statusCode: 200, data: { code: 0, message: 'success', data: { bound: true, token: 'jwt-1', user, bindTicket: null, expiresIn: null } } } as never),
      getStorageSync: () => '', removeStorageSync: vi.fn(),
    })
    vi.stubGlobal('getCurrentPages', () => [])
    const flow = await import('../miniprogram/auth/login-flow')

    await expect(flow.loginWithWeChat()).resolves.toEqual({ bound: true, user })
    expect(set).toHaveBeenCalledWith('campus.lab.token', 'jwt-1')
    expect(set).toHaveBeenCalledWith('campus.lab.user', user)
  })

  it('rejects when WeChat cannot supply a login code', async () => {
    vi.stubGlobal('wx', {
      login: (options: WechatMiniprogram.LoginOption) => options.fail?.({ errMsg: 'login failed', errno: -1 }),
    })
    const flow = await import('../miniprogram/auth/login-flow')

    await expect(flow.loginWithWeChat()).rejects.toThrow('无法获取微信登录凭证')
  })

  it('rejects an incomplete backend response instead of saving credentials', async () => {
    const set = vi.fn()
    vi.stubGlobal('wx', {
      login: (options: WechatMiniprogram.LoginOption) => options.success?.({ code: 'code-1', errMsg: 'login:ok' }),
      setStorageSync: set,
      request: (options: WechatMiniprogram.RequestOption) => options.success?.({ statusCode: 200, data: { code: 0, message: 'success', data: { bound: true, token: null, user: null, bindTicket: null, expiresIn: null } } } as never),
      getStorageSync: () => '', removeStorageSync: vi.fn(),
    })
    vi.stubGlobal('getCurrentPages', () => [])
    const flow = await import('../miniprogram/auth/login-flow')

    await expect(flow.loginWithWeChat()).rejects.toThrow('微信登录响应无效')
    expect(set).not.toHaveBeenCalled()
  })
})

describe('session restoration', () => {
  beforeEach(() => { vi.resetModules(); vi.clearAllMocks() })

  it('relaunches to login when no identity is stored', async () => {
    const reLaunch = vi.fn()
    const request = vi.fn()
    vi.stubGlobal('wx', { getStorageSync: () => '', reLaunch, request })
    const session = await import('../miniprogram/auth/session')

    await expect(session.restoreSession()).resolves.toBeNull()
    expect(reLaunch).toHaveBeenCalledWith({ url: '/pages/login/index' })
    expect(request).not.toHaveBeenCalled()
  })

  it('refreshes the stored public user from auth/me', async () => {
    const storedUser = { userId: 1, account: '20240001', name: '旧姓名', roles: ['STUDENT'], status: 'ACTIVE' }
    const freshUser = { ...storedUser, name: '新姓名' }
    const values = new Map<string, unknown>([['campus.lab.token', 'jwt-1'], ['campus.lab.user', storedUser]])
    vi.stubGlobal('wx', {
      getStorageSync: (key: string) => values.get(key) ?? '',
      setStorageSync: (key: string, value: unknown) => values.set(key, value),
      removeStorageSync: (key: string) => values.delete(key),
      request: (options: WechatMiniprogram.RequestOption) => options.success?.({ statusCode: 200, data: { code: 0, message: 'success', data: freshUser } } as never),
    })
    const session = await import('../miniprogram/auth/session')

    await expect(session.restoreSession()).resolves.toEqual(freshUser)
    expect(values.get('campus.lab.token')).toBe('jwt-1')
    expect(values.get('campus.lab.user')).toEqual(freshUser)
  })

  it('keeps a newer login when a deferred auth/me succeeds', async () => {
    const oldUser = { userId: 1, account: 'old', name: '旧用户', roles: ['STUDENT'], status: 'ACTIVE' }
    const newUser = { userId: 2, account: 'new', name: '新用户', roles: ['STUDENT' as const], status: 'ACTIVE' as const }
    const values = new Map<string, unknown>([['campus.lab.token', 'old-jwt'], ['campus.lab.user', oldUser]])
    let deferredRequest: WechatMiniprogram.RequestOption | undefined
    vi.stubGlobal('wx', {
      getStorageSync: (key: string) => values.get(key) ?? '',
      setStorageSync: (key: string, value: unknown) => values.set(key, value),
      removeStorageSync: (key: string) => values.delete(key),
      request: (options: WechatMiniprogram.RequestOption) => { deferredRequest = options },
    })
    const session = await import('../miniprogram/auth/session')
    const storage = await import('../miniprogram/auth/storage')

    const restoring = session.restoreSession()
    storage.saveSession('new-jwt', newUser)
    deferredRequest!.success?.({ statusCode: 200, data: { code: 0, message: 'success', data: { ...oldUser, name: '旧会话响应' } } } as never)
    await restoring

    expect(values.get('campus.lab.token')).toBe('new-jwt')
    expect(values.get('campus.lab.user')).toEqual(newUser)
  })

  it('keeps a newer login when a deferred auth/me returns 401', async () => {
    const oldUser = { userId: 1, account: 'old', name: '旧用户', roles: ['STUDENT'], status: 'ACTIVE' }
    const newUser = { userId: 2, account: 'new', name: '新用户', roles: ['STUDENT' as const], status: 'ACTIVE' as const }
    const values = new Map<string, unknown>([['campus.lab.token', 'old-jwt'], ['campus.lab.user', oldUser]])
    const reLaunch = vi.fn()
    let deferredRequest: WechatMiniprogram.RequestOption | undefined
    vi.stubGlobal('wx', {
      getStorageSync: (key: string) => values.get(key) ?? '',
      setStorageSync: (key: string, value: unknown) => values.set(key, value),
      removeStorageSync: (key: string) => values.delete(key),
      reLaunch,
      request: (options: WechatMiniprogram.RequestOption) => { deferredRequest = options },
    })
    vi.stubGlobal('getCurrentPages', () => [{ route: 'pages/home/index' }])
    const session = await import('../miniprogram/auth/session')
    const storage = await import('../miniprogram/auth/storage')

    const restoring = session.restoreSession()
    storage.saveSession('new-jwt', newUser)
    deferredRequest!.success?.({ statusCode: 401, data: { code: 401, message: '旧登录已过期', data: null } } as never)
    await expect(restoring).resolves.toBeNull()

    expect(values.get('campus.lab.token')).toBe('new-jwt')
    expect(values.get('campus.lab.user')).toEqual(newUser)
    expect(reLaunch).not.toHaveBeenCalled()
  })

  it('leaves 401 cleanup and redirection to the request layer', async () => {
    const values = new Map<string, unknown>([['campus.lab.token', 'expired'], ['campus.lab.user', { userId: 1 }]])
    const reLaunch = vi.fn()
    const showToast = vi.fn()
    vi.stubGlobal('wx', {
      getStorageSync: (key: string) => values.get(key) ?? '',
      setStorageSync: (key: string, value: unknown) => values.set(key, value),
      removeStorageSync: (key: string) => values.delete(key),
      reLaunch,
      showToast,
      request: (options: WechatMiniprogram.RequestOption) => options.success?.({ statusCode: 401, data: { code: 401, message: '登录已过期', data: null } } as never),
    })
    vi.stubGlobal('getCurrentPages', () => [{ route: 'pages/home/index' }])
    const session = await import('../miniprogram/auth/session')

    await expect(session.restoreSession()).resolves.toBeNull()
    expect(values.size).toBe(0)
    expect(reLaunch).toHaveBeenCalledWith({ url: '/pages/login/index' })
    expect(showToast).not.toHaveBeenCalled()
  })

  it('keeps the stored identity and shows a toast on network failure', async () => {
    const user = { userId: 1, account: '20240001', name: '演示学生', roles: ['STUDENT'], status: 'ACTIVE' }
    const values = new Map<string, unknown>([['campus.lab.token', 'jwt-1'], ['campus.lab.user', user]])
    const reLaunch = vi.fn()
    const showToast = vi.fn()
    vi.stubGlobal('wx', {
      getStorageSync: (key: string) => values.get(key) ?? '',
      removeStorageSync: (key: string) => values.delete(key),
      reLaunch,
      showToast,
      request: (options: WechatMiniprogram.RequestOption) => options.fail?.({ errMsg: 'offline' } as never),
    })
    const session = await import('../miniprogram/auth/session')

    await expect(session.restoreSession()).resolves.toEqual(user)
    expect(values.get('campus.lab.token')).toBe('jwt-1')
    expect(values.get('campus.lab.user')).toEqual(user)
    expect(reLaunch).not.toHaveBeenCalled()
    expect(showToast).toHaveBeenCalledWith({ title: '网络异常，请稍后重试', icon: 'none' })
  })
})

interface AuthPageDefinition {
  data: {
    development?: boolean
    ticket?: string
    account: string
    password: string
    showPassword: boolean
    loading: boolean
    error: string
  }
  onLoad(query: Record<string, string>): void
  onAccount(event: WechatMiniprogram.Input): void
  onPassword(event: WechatMiniprogram.Input): void
  togglePassword(): void
  wechat(): Promise<void>
  developmentLogin(): Promise<void>
  bind(): Promise<void>
}

function instantiatePage(definition: AuthPageDefinition) {
  const page = {
    ...definition,
    data: { ...definition.data },
    setData(patch: Partial<AuthPageDefinition['data']>) { Object.assign(page.data, patch) },
  }
  return page
}

function input(value: string) {
  return { detail: { value } } as WechatMiniprogram.Input
}

describe('authentication pages', () => {
  beforeEach(() => { vi.resetModules(); vi.clearAllMocks() })

  it('uses the existing account endpoint, saves the session and clears the password', async () => {
    let definition: AuthPageDefinition | undefined
    const values = new Map<string, unknown>()
    const switchTab = vi.fn()
    const request = vi.fn((options: WechatMiniprogram.RequestOption) => options.success?.({
      statusCode: 200,
      data: { code: 0, message: 'success', data: { token: 'jwt-dev', user: { userId: 1, account: '20240001', name: '演示学生', roles: ['STUDENT'], status: 'ACTIVE' } } },
    } as never))
    vi.stubGlobal('Page', (page: AuthPageDefinition) => { definition = page })
    vi.stubGlobal('wx', {
      getAccountInfoSync: () => ({ miniProgram: { envVersion: 'develop' } }),
      getStorageSync: (key: string) => values.get(key) ?? '',
      setStorageSync: (key: string, value: unknown) => values.set(key, value),
      request,
      switchTab,
    })
    await import('../miniprogram/pages/login/index')
    const page = instantiatePage(definition!)

    page.onLoad({})
    expect(page.data.development).toBe(true)
    page.togglePassword()
    expect(page.data.showPassword).toBe(true)
    page.onAccount(input(' 20240001 '))
    page.onPassword(input('Lab@2026'))
    await page.developmentLogin()

    expect(request).toHaveBeenCalledWith(expect.objectContaining({
      url: 'http://127.0.0.1:8080/api/auth/login',
      method: 'POST',
      data: { account: '20240001', password: 'Lab@2026' },
    }))
    expect(values.get('campus.lab.token')).toBe('jwt-dev')
    expect(page.data.password).toBe('')
    expect(page.data.loading).toBe(false)
    expect(switchTab).toHaveBeenCalledWith({ url: '/pages/home/index' })
  })

  it('hides development login outside develop and rejects incomplete forms without loading', async () => {
    let definition: AuthPageDefinition | undefined
    let environment = 'release'
    const request = vi.fn()
    vi.stubGlobal('Page', (page: AuthPageDefinition) => { definition = page })
    vi.stubGlobal('wx', {
      getAccountInfoSync: () => ({ miniProgram: { envVersion: environment } }),
      request,
    })
    await import('../miniprogram/pages/login/index')
    const page = instantiatePage(definition!)

    page.onLoad({})
    await page.developmentLogin()
    expect(page.data.development).toBe(false)
    expect(request).not.toHaveBeenCalled()

    environment = 'develop'
    page.data.development = true
    await page.developmentLogin()
    expect(page.data.error).toBe('请输入学号和密码')
    expect(page.data.loading).toBe(false)
    expect(request).not.toHaveBeenCalled()
  })

  it('rejects a direct development login call with a valid form in release', async () => {
    let definition: AuthPageDefinition | undefined
    const values = new Map<string, unknown>()
    const getAccountInfoSync = vi.fn(() => ({ miniProgram: { envVersion: 'release' } }))
    const request = vi.fn((options: WechatMiniprogram.RequestOption) => options.success?.({
      statusCode: 200,
      data: { code: 0, message: 'success', data: { token: 'should-not-save', user: { userId: 3, account: '20240001', name: '不应登录', roles: ['STUDENT'], status: 'ACTIVE' } } },
    } as never))
    vi.stubGlobal('Page', (page: AuthPageDefinition) => { definition = page })
    vi.stubGlobal('wx', {
      getAccountInfoSync,
      getStorageSync: (key: string) => values.get(key) ?? '',
      setStorageSync: (key: string, value: unknown) => values.set(key, value),
      request,
      switchTab: vi.fn(),
    })
    await import('../miniprogram/pages/login/index')
    const page = instantiatePage(definition!)

    page.data.development = true
    page.onAccount(input('20240001'))
    page.onPassword(input('Lab@2026'))
    await page.developmentLogin()

    expect(getAccountInfoSync).toHaveBeenCalled()
    expect(request).not.toHaveBeenCalled()
    expect(values.size).toBe(0)
  })

  it('navigates to binding with an encoded in-memory ticket', async () => {
    let definition: AuthPageDefinition | undefined
    const navigateTo = vi.fn()
    vi.stubGlobal('Page', (page: AuthPageDefinition) => { definition = page })
    vi.stubGlobal('wx', {
      getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }),
      login: (options: WechatMiniprogram.LoginOption) => options.success?.({ code: 'code-1', errMsg: 'login:ok' }),
      request: (options: WechatMiniprogram.RequestOption) => options.success?.({ statusCode: 200, data: { code: 0, message: 'success', data: { bound: false, token: null, user: null, bindTicket: 'ticket / 1', expiresIn: 300 } } } as never),
      getStorageSync: () => '',
      setStorageSync: vi.fn(),
      removeStorageSync: vi.fn(),
      navigateTo,
    })
    vi.stubGlobal('getCurrentPages', () => [])
    await import('../miniprogram/pages/login/index')
    const page = instantiatePage(definition!)

    await page.wechat()
    expect(navigateTo).toHaveBeenCalledWith({ url: `/pages/bind/index?ticket=${encodeURIComponent('ticket / 1')}` })
    expect(page.data.loading).toBe(false)
  })

  it('binds the in-memory ticket through the real endpoint and clears the password', async () => {
    let definition: AuthPageDefinition | undefined
    const values = new Map<string, unknown>()
    const switchTab = vi.fn()
    const request = vi.fn((options: WechatMiniprogram.RequestOption) => options.success?.({
      statusCode: 200,
      data: { code: 0, message: 'success', data: { token: 'jwt-bind', user: { userId: 1, account: '20240001', name: '演示学生', roles: ['STUDENT'], status: 'ACTIVE' } } },
    } as never))
    vi.stubGlobal('Page', (page: AuthPageDefinition) => { definition = page })
    vi.stubGlobal('wx', {
      getStorageSync: (key: string) => values.get(key) ?? '',
      setStorageSync: (key: string, value: unknown) => values.set(key, value),
      request,
      switchTab,
    })
    await import('../miniprogram/pages/bind/index')
    const page = instantiatePage(definition!)

    page.onLoad({ ticket: 'ticket-1' })
    page.onAccount(input(' 20240001 '))
    page.onPassword(input('Lab@2026'))
    page.togglePassword()
    expect(page.data.showPassword).toBe(true)
    await page.bind()

    expect(request).toHaveBeenCalledWith(expect.objectContaining({
      url: 'http://127.0.0.1:8080/api/auth/wechat/bind',
      method: 'POST',
      data: { bindTicket: 'ticket-1', account: '20240001', password: 'Lab@2026' },
    }))
    expect(values.get('campus.lab.token')).toBe('jwt-bind')
    expect(page.data.password).toBe('')
    expect(page.data.loading).toBe(false)
    expect(switchTab).toHaveBeenCalledWith({ url: '/pages/home/index' })
  })

  it('shows binding errors and clears the password without saving a session', async () => {
    let definition: AuthPageDefinition | undefined
    const set = vi.fn()
    vi.stubGlobal('Page', (page: AuthPageDefinition) => { definition = page })
    vi.stubGlobal('wx', {
      getStorageSync: () => '',
      setStorageSync: set,
      request: (options: WechatMiniprogram.RequestOption) => options.success?.({ statusCode: 400, data: { code: 400, message: '学号或密码错误', data: null } } as never),
    })
    await import('../miniprogram/pages/bind/index')
    const page = instantiatePage(definition!)

    page.onLoad({ ticket: 'ticket-1' })
    page.onAccount(input('20240001'))
    page.onPassword(input('wrong'))
    await page.bind()

    expect(page.data.error).toBe('学号或密码错误')
    expect(page.data.password).toBe('')
    expect(page.data.loading).toBe(false)
    expect(set).not.toHaveBeenCalled()
  })

  it('provides associated labels, password toggles and live errors in login markup', () => {
    expect(loginWxml).toContain('<label class="auth-label" for="login-account">学号</label>')
    expect(loginWxml).toContain('id="login-account"')
    expect(loginWxml).toContain('<label class="auth-label" for="login-password">密码</label>')
    expect(loginWxml).toContain('id="login-password"')
    expect(loginWxml).toContain('password="{{!showPassword}}"')
    expect(loginWxml).toContain('bindtap="togglePassword"')
    expect(loginWxml).toContain('aria-role="alert"')
    expect(loginWxml).toContain('aria-live="assertive"')
    expect(loginWxss).toContain('height: 96rpx')
    expect(loginWxss).toContain('min-height: 96rpx')
  })

  it('provides associated labels, password toggle and live errors in binding markup', () => {
    expect(bindWxml).toContain('<label class="auth-label" for="bind-account">学号</label>')
    expect(bindWxml).toContain('id="bind-account"')
    expect(bindWxml).toContain('<label class="auth-label" for="bind-password">密码</label>')
    expect(bindWxml).toContain('id="bind-password"')
    expect(bindWxml).toContain('password="{{!showPassword}}"')
    expect(bindWxml).toContain('bindtap="togglePassword"')
    expect(bindWxml).toContain('aria-role="alert"')
    expect(bindWxml).toContain('aria-live="assertive"')
  })
})

describe('application launch', () => {
  it('starts identity restoration', async () => {
    vi.resetModules()
    let definition: { onLaunch(): void } | undefined
    const reLaunch = vi.fn()
    vi.stubGlobal('App', (app: { onLaunch(): void }) => { definition = app })
    vi.stubGlobal('wx', { getStorageSync: () => '', reLaunch })
    await import('../miniprogram/app')

    definition!.onLaunch()
    expect(reLaunch).toHaveBeenCalledWith({ url: '/pages/login/index' })
  })
})
