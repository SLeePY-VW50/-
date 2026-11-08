import { request } from './http'
import type { LoginResponse, User, WeChatLoginResponse } from './types'

export function developmentLogin(account: string, password: string) {
  return request<LoginResponse>('/api/auth/login', { method: 'POST', data: { account, password }, redirectOnUnauthorized: false })
}

export function wechatLogin(code: string) {
  return request<WeChatLoginResponse>('/api/auth/wechat/login', { method: 'POST', data: { code }, redirectOnUnauthorized: false })
}

export function wechatBind(bindTicket: string, account: string, password: string) {
  return request<LoginResponse>('/api/auth/wechat/bind', { method: 'POST', data: { bindTicket, account, password }, redirectOnUnauthorized: false })
}

export function currentUser() {
  return request<User>('/api/auth/me')
}
