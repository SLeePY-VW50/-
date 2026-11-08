export const API_BASE_URL = 'http://127.0.0.1:8080'
export function isDevelopmentLoginVisible() {
  return wx.getAccountInfoSync().miniProgram.envVersion === 'develop'
}
