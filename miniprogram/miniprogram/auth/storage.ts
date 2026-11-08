import type { User } from '../api/types'

const TOKEN_KEY = 'campus.lab.token'
const USER_KEY = 'campus.lab.user'

export interface StoredSession { token: string; user: User }

export function saveSession(token: string, user: User) {
  const publicUser: User = {
    userId: user.userId,
    account: user.account,
    name: user.name,
    roles: [...user.roles],
    status: user.status,
  }
  wx.setStorageSync(TOKEN_KEY, token)
  wx.setStorageSync(USER_KEY, publicUser)
}

export function readSession(): StoredSession | null {
  const token = wx.getStorageSync(TOKEN_KEY) as string
  const user = wx.getStorageSync(USER_KEY) as User
  return token && user && user.userId ? { token, user } : null
}

export function clearSession() {
  wx.removeStorageSync(TOKEN_KEY)
  wx.removeStorageSync(USER_KEY)
}
