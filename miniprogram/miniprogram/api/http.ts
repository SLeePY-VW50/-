import { API_BASE_URL } from '../config/index'
import { clearSession, readSession } from '../auth/storage'
import type { ApiEnvelope } from './types'

export class ApiError extends Error {
  constructor(message: string, public status = 0, public code?: number) {
    super(message)
    this.name = 'ApiError'
  }
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  data?: unknown
  query?: Record<string, string | number | undefined>
  redirectOnUnauthorized?: boolean
}

export function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const query = Object.entries(options.query ?? {})
    .filter((entry): entry is [string, string | number] => entry[1] !== undefined)
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
    .join('&')
  const token = readSession()?.token

  return new Promise<T>((resolve, reject) => {
    wx.request({
      url: `${API_BASE_URL}${path}${query ? `?${query}` : ''}`,
      method: (options.method ?? 'GET') as WechatMiniprogram.RequestOption['method'],
      data: options.data as never,
      timeout: 20000,
      header: token ? { Authorization: `Bearer ${token}` } : {},
      success: (response) => {
        const body = response.data as ApiEnvelope<T> | null | undefined
        if (response.statusCode === 401) {
          if (readSession()?.token === token) {
            clearSession()
            const current = getCurrentPages().at(-1)?.route
            if (options.redirectOnUnauthorized !== false && current !== 'pages/login/index') {
              wx.reLaunch({ url: '/pages/login/index' })
            }
          }
          reject(new ApiError(body?.message || '登录已过期', 401, body?.code))
          return
        }
        if (response.statusCode >= 200 && response.statusCode < 300 && body?.code === 0) {
          resolve(body.data)
          return
        }
        reject(new ApiError(
          body?.message || (response.statusCode === 403 ? '当前账号无权执行此操作' : '请求失败'),
          response.statusCode,
          body?.code,
        ))
      },
      fail: () => reject(new ApiError('网络连接失败，请检查服务是否启动', 0)),
    })
  })
}

export function errorMessage(error: unknown) {
  return error instanceof Error && error.message ? error.message : '请求失败，请稍后重试'
}
