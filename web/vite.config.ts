import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

if (process.platform === 'win32') {
  const tempDir = resolve(process.cwd(), 'node_modules/.cache/esbuild')
  mkdirSync(tempDir, { recursive: true })
  process.env.TEMP = tempDir
  process.env.TMP = tempDir
}

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api/ai': {
        target: 'http://localhost:8000',
        changeOrigin: true,
      },
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./tests/setup.ts'],
    css: true,
  },
})
