/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const mock = (process.env.VITE_MOCK ?? env.VITE_MOCK) === '1'
  return {
    plugins: [react()],
    server: {
      proxy: {
        // The app always calls its own origin; the proxy decides where /api/v1 goes.
        '/api/v1': mock
          ? {
              // Prism has no /api/v1 prefix and never sets a session cookie, so strip the
              // prefix and send one.
              target: 'http://localhost:4010',
              rewrite: (p: string) => p.replace(/^\/api\/v1/, ''),
              headers: { cookie: 'sid=mock' },
            }
          : { target: 'http://localhost:8080', ws: true, changeOrigin: false },
      },
    },
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: ['src/test/setup.ts'],
      include: ['src/**/*.test.{ts,tsx}'],
    },
  }
})
