import { restoreSession } from '../../auth/session'
import { clearSession, readSession } from '../../auth/storage'
import { isDevelopmentLoginVisible } from '../../config/index'
import type { Role, User } from '../../api/types'

const roleLabels: Record<Role, string> = {
  STUDENT: '学生',
  TEACHER: '教师',
  LAB_ADMIN: '实验室管理员',
  SYS_ADMIN: '系统管理员',
}

Page({
  data: { user: null as User | null, roleText: '', development: false, generation: 0 },
  async onShow() {
    const generation = this.data.generation + 1
    this.setData({ generation })
    if (await restoreSession() && generation === this.data.generation) {
      const user = readSession()?.user ?? null
      this.setData({
        user,
        development: isDevelopmentLoginVisible(),
        roleText: user?.roles.map(role => roleLabels[role]).join(' / ') ?? '',
      })
    }
  },
  reservations() {
    wx.switchTab({ url: '/pages/reservations/index' })
  },
  async logout() {
    const result = await wx.showModal({
      title: '退出登录',
      content: '退出后需要重新验证身份。',
      confirmText: '确认退出',
    })
    if (result.confirm) {
      const generation = this.data.generation + 1
      this.setData({ generation })
      clearSession()
      wx.reLaunch({ url: '/pages/login/index' })
    }
  },
})
