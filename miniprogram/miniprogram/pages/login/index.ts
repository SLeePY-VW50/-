import { developmentLogin } from '../../api/auth'
import { errorMessage } from '../../api/http'
import { isDevelopmentLoginVisible } from '../../config/index'
import { loginWithWeChat } from '../../auth/login-flow'
import { saveSession } from '../../auth/storage'

Page({
  data: { development: false, account: '', password: '', showPassword: false, loading: false, error: '' },
  onLoad() { this.setData({ development: isDevelopmentLoginVisible() }) },
  onAccount(event: WechatMiniprogram.Input) { this.setData({ account: event.detail.value }) },
  onPassword(event: WechatMiniprogram.Input) { this.setData({ password: event.detail.value }) },
  togglePassword() { this.setData({ showPassword: !this.data.showPassword }) },
  async wechat() {
    if (this.data.loading) return
    this.setData({ loading: true, error: '' })
    try {
      const result = await loginWithWeChat()
      if (result.bound) wx.switchTab({ url: '/pages/home/index' })
      else wx.navigateTo({ url: `/pages/bind/index?ticket=${encodeURIComponent(result.bindTicket)}` })
    } catch (error) { this.setData({ error: errorMessage(error) }) }
    finally { this.setData({ loading: false }) }
  },
  async developmentLogin() {
    if (!isDevelopmentLoginVisible() || !this.data.development || this.data.loading) return
    const account = this.data.account.trim()
    if (!account || !this.data.password) {
      this.setData({ error: '请输入学号和密码' })
      return
    }
    this.setData({ loading: true, error: '' })
    try {
      const result = await developmentLogin(account, this.data.password)
      saveSession(result.token, result.user)
      wx.switchTab({ url: '/pages/home/index' })
    } catch (error) { this.setData({ error: errorMessage(error) }) }
    finally { this.setData({ loading: false, password: '' }) }
  },
})
