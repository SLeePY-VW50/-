import { wechatBind } from '../../api/auth'
import { errorMessage } from '../../api/http'
import { saveSession } from '../../auth/storage'

Page({
  data: { ticket: '', account: '', password: '', showPassword: false, loading: false, error: '' },
  onLoad(query: Record<string, string>) { this.setData({ ticket: query.ticket ?? '' }) },
  onAccount(event: WechatMiniprogram.Input) { this.setData({ account: event.detail.value }) },
  onPassword(event: WechatMiniprogram.Input) { this.setData({ password: event.detail.value }) },
  togglePassword() { this.setData({ showPassword: !this.data.showPassword }) },
  async bind() {
    if (this.data.loading) return
    const account = this.data.account.trim()
    if (!this.data.ticket) {
      this.setData({ error: '绑定凭证无效，请重新登录' })
      return
    }
    if (!account || !this.data.password) {
      this.setData({ error: '请输入学号和密码' })
      return
    }
    this.setData({ loading: true, error: '' })
    try {
      const result = await wechatBind(this.data.ticket, account, this.data.password)
      saveSession(result.token, result.user)
      wx.switchTab({ url: '/pages/home/index' })
    } catch (error) { this.setData({ error: errorMessage(error) }) }
    finally { this.setData({ loading: false, password: '' }) }
  },
})
