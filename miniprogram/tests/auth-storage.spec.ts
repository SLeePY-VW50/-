import { beforeEach, describe, expect, it, vi } from 'vitest'

describe('auth storage', () => {
  beforeEach(() => vi.resetModules())

  it('saves and clears only the token and public user fields', async () => {
    const values = new Map<string, unknown>()
    vi.stubGlobal('wx', {
      setStorageSync: (key: string, value: unknown) => values.set(key, value),
      getStorageSync: (key: string) => values.get(key) ?? '',
      removeStorageSync: (key: string) => values.delete(key),
    })
    const storage = await import('../miniprogram/auth/storage')
    const user = {
      userId: 1,
      account: '20240001',
      name: '演示学生',
      roles: ['STUDENT' as const],
      status: 'ACTIVE' as const,
      password: 'Lab@2026',
      openid: 'openid-secret',
      bindTicket: 'bind-ticket-secret',
      qrCode: 'raw-qr-secret',
    }

    storage.saveSession('jwt', user)

    expect(storage.readSession()).toEqual({
      token: 'jwt',
      user: {
        userId: 1,
        account: '20240001',
        name: '演示学生',
        roles: ['STUDENT'],
        status: 'ACTIVE',
      },
    })
    expect(values.has('campus.lab.token')).toBe(true)
    expect(values.has('campus.lab.user')).toBe(true)
    expect(values.size).toBe(2)
    expect(JSON.stringify([...values.values()])).not.toMatch(/Lab@2026|openid-secret|bind-ticket-secret|raw-qr-secret/)

    storage.clearSession()
    expect(storage.readSession()).toBeNull()
  })

  it('returns null when the stored session is incomplete', async () => {
    vi.stubGlobal('wx', {
      getStorageSync: (key: string) => key === 'campus.lab.token' ? 'jwt' : '',
    })
    const storage = await import('../miniprogram/auth/storage')

    expect(storage.readSession()).toBeNull()
  })
})
