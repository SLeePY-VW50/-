import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

function success(options: WechatMiniprogram.RequestOption, statusCode: number, data: unknown) {
  options.success?.({ statusCode, data } as never)
}

describe('http', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.clearAllMocks()
  })

  afterEach(() => vi.unstubAllGlobals())

  it('unwraps code zero and sends the bearer token, query and timeout', async () => {
    const request = vi.fn((options: WechatMiniprogram.RequestOption) => {
      success(options, 200, { code: 0, message: 'success', data: { ok: true } })
    })
    vi.stubGlobal('wx', {
      getStorageSync: (key: string) => key === 'campus.lab.token' ? 'jwt' : { userId: 1 },
      request,
    })
    const http = await import('../miniprogram/api/http')

    await expect(http.request<{ ok: boolean }>('/api/example', {
      method: 'POST',
      data: { value: 1 },
      query: { keyword: '实验室 A', page: 2, ignored: undefined },
    })).resolves.toEqual({ ok: true })
    expect(request).toHaveBeenCalledWith(expect.objectContaining({
      url: 'http://127.0.0.1:8080/api/example?keyword=%E5%AE%9E%E9%AA%8C%E5%AE%A4%20A&page=2',
      method: 'POST',
      data: { value: 1 },
      timeout: 20000,
      header: { Authorization: 'Bearer jwt' },
    }))
  })

  it('clears identity and relaunches to login on 401', async () => {
    const remove = vi.fn()
    const reLaunch = vi.fn()
    vi.stubGlobal('wx', {
      getStorageSync: () => '',
      removeStorageSync: remove,
      reLaunch,
      request: (options: WechatMiniprogram.RequestOption) => success(options, 401, { code: 401, message: '登录已过期', data: null }),
    })
    vi.stubGlobal('getCurrentPages', () => [{ route: 'pages/home/index' }])
    const http = await import('../miniprogram/api/http')

    await expect(http.request('/api/private')).rejects.toMatchObject({
      message: '登录已过期', status: 401, code: 401,
    })
    expect(remove).toHaveBeenCalledTimes(2)
    expect(reLaunch).toHaveBeenCalledWith({ url: '/pages/login/index' })
  })

  it('clears identity without redirecting again on the login page', async () => {
    const remove = vi.fn()
    const reLaunch = vi.fn()
    vi.stubGlobal('wx', {
      getStorageSync: () => '',
      removeStorageSync: remove,
      reLaunch,
      request: (options: WechatMiniprogram.RequestOption) => success(options, 401, { code: 401, message: '未登录', data: null }),
    })
    vi.stubGlobal('getCurrentPages', () => [{ route: 'pages/login/index' }])
    const http = await import('../miniprogram/api/http')

    await expect(http.request('/api/login')).rejects.toMatchObject({ status: 401 })
    expect(remove).toHaveBeenCalledTimes(2)
    expect(reLaunch).not.toHaveBeenCalled()
  })

  it.each([403, 409])('preserves the backend message for HTTP %i without clearing identity', async (statusCode) => {
    const remove = vi.fn()
    const message = statusCode === 403 ? '无权操作' : '预约冲突'
    vi.stubGlobal('wx', {
      getStorageSync: () => '',
      removeStorageSync: remove,
      request: (options: WechatMiniprogram.RequestOption) => success(options, statusCode, { code: statusCode, message, data: null }),
    })
    const http = await import('../miniprogram/api/http')

    await expect(http.request('/api/private')).rejects.toMatchObject({ message, status: statusCode, code: statusCode })
    expect(remove).not.toHaveBeenCalled()
  })

  it('keeps identity on network failure', async () => {
    const remove = vi.fn()
    vi.stubGlobal('wx', {
      getStorageSync: () => '',
      removeStorageSync: remove,
      request: (options: WechatMiniprogram.RequestOption) => options.fail?.({ errMsg: 'offline' } as never),
    })
    const http = await import('../miniprogram/api/http')

    await expect(http.request('/api/private')).rejects.toMatchObject({
      message: '网络连接失败，请检查服务是否启动', status: 0,
    })
    expect(remove).not.toHaveBeenCalled()
  })

  it('returns an ApiError instead of throwing a TypeError for an empty error response', async () => {
    vi.stubGlobal('wx', {
      getStorageSync: () => '',
      request: (options: WechatMiniprogram.RequestOption) => success(options, 500, null),
    })
    const http = await import('../miniprogram/api/http')

    await expect(http.request('/api/private')).rejects.toMatchObject({
      name: 'ApiError', message: '请求失败', status: 500,
    })
  })

  it('rejects a non-zero envelope even when HTTP succeeds', async () => {
    vi.stubGlobal('wx', {
      getStorageSync: () => '',
      request: (options: WechatMiniprogram.RequestOption) => success(options, 200, { code: 409, message: '时间段已被占用', data: null }),
    })
    const http = await import('../miniprogram/api/http')

    await expect(http.request('/api/reservations')).rejects.toMatchObject({
      message: '时间段已被占用', status: 200, code: 409,
    })
  })

  it('formats unknown errors for page display', async () => {
    vi.stubGlobal('wx', {})
    const http = await import('../miniprogram/api/http')

    expect(http.errorMessage(new Error('后端消息'))).toBe('后端消息')
    expect(http.errorMessage(null)).toBe('请求失败，请稍后重试')
  })
})
