import { describe, expect, it, vi } from 'vitest'

describe('mini program config', () => {
  it('uses the local backend and only exposes development login in develop builds', async () => {
    vi.resetModules()
    vi.stubGlobal('wx', {
      getAccountInfoSync: () => ({ miniProgram: { envVersion: 'develop' } }),
    })
    const config = await import('../miniprogram/config/index')
    expect(config.API_BASE_URL).toBe('http://127.0.0.1:8080')
    expect(config.isDevelopmentLoginVisible()).toBe(true)
    vi.stubGlobal('wx', {
      getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }),
    })
    expect(config.isDevelopmentLoginVisible()).toBe(false)
  })
})
