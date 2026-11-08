import { currentUser } from '../api/auth'
import { readSession, saveSession } from './storage'

export async function restoreSession() {
  const stored = readSession()
  if (!stored) {
    wx.reLaunch({ url: '/pages/login/index' })
    return null
  }
  try {
    const user = await currentUser()
    if (readSession()?.token !== stored.token) return null
    saveSession(stored.token, user)
    return user
  } catch (error) {
    if ((error as { status?: number }).status !== 401) {
      wx.showToast({ title: '网络异常，请稍后重试', icon: 'none' })
      return stored.user
    }
    return null
  }
}
