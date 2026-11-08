import { wechatLogin } from '../api/auth'
import type { User } from '../api/types'
import { saveSession } from './storage'

export type WeChatFlowResult = { bound: true; user: User } | { bound: false; bindTicket: string }

export function loginWithWeChat(): Promise<WeChatFlowResult> {
  return new Promise((resolve, reject) => {
    wx.login({
      success: async ({ code }) => {
        try {
          const result = await wechatLogin(code)
          if (result.bound && result.token && result.user) {
            saveSession(result.token, result.user)
            resolve({ bound: true, user: result.user })
          } else if (!result.bound && result.bindTicket) {
            resolve({ bound: false, bindTicket: result.bindTicket })
          } else reject(new Error('微信登录响应无效'))
        } catch (error) { reject(error) }
      },
      fail: () => reject(new Error('无法获取微信登录凭证')),
    })
  })
}
