import { afterEach, beforeEach, vi } from 'vitest'
import { config } from '@vue/test-utils'

config.global.stubs = {
  teleport: true,
}

beforeEach(() => {
  sessionStorage.clear()
  window.history.replaceState({}, '', '/')
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.clearAllMocks()
})
