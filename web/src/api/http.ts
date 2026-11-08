import axios, { AxiosError, type AxiosRequestConfig } from 'axios'

export const AUTH_TOKEN_KEY = 'campus.lab.token'
export const AUTH_USER_KEY = 'campus.lab.user'

export class ApiError extends Error {
  status?: number
  code?: string | number

  constructor(message: string, status?: number, code?: string | number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

export interface ApiClient {
  get<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<T>
  post<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>
  put<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>
  patch<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>
  delete<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<T>
}

const client = axios.create({
  baseURL: '/',
  timeout: 20000,
})

client.interceptors.request.use((config) => {
  const token = sessionStorage.getItem(AUTH_TOKEN_KEY)
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

client.interceptors.response.use(async (response) => {
  const body = response.data
  if (body instanceof Blob || typeof body !== 'object' || body === null || !('code' in body)) {
    return body
  }
  if (body.code === 0) return body.data
  throw new ApiError(body.message || '请求失败', response.status, body.code)
}, async (error: AxiosError<{ message?: string; code?: string | number }>) => {
  const status = error.response?.status
  const data = error.response?.data
  if (status === 401) {
    sessionStorage.removeItem(AUTH_TOKEN_KEY)
    sessionStorage.removeItem(AUTH_USER_KEY)
    const path = window.location.pathname
    if (path !== '/login' && path !== '/terminal') {
      const { default: router } = await import('../router')
      const current = router.currentRoute.value
      if (current.name !== 'login' && current.name !== 'terminal') {
        await router.replace({ name: 'login', query: { redirect: current.fullPath } })
      }
    }
  }
  const message = data?.message || (status === 403 ? '当前账号无权执行此操作' : status === 409 ? '操作冲突，请检查后重试' : error.message || '网络请求失败')
  throw new ApiError(message, status, data?.code)
})

export function errorMessage(error: unknown) {
  return error instanceof Error && error.message ? error.message : '请求失败，请稍后重试'
}

export default client as ApiClient
