/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'

// index.html's <meta name="build-id" content="%VITE_BUILD_ID%"> needs a value; Settings shows the same.
process.env.VITE_BUILD_ID ||= 'dev'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const mock = (process.env.VITE_MOCK ?? env.VITE_MOCK) === '1'
  return {
    plugins: [
      react(),
      VitePWA({
        strategies: 'injectManifest',
        srcDir: 'src',
        filename: 'sw.ts',
        registerType: 'prompt',
        // src/pwa/register.ts registers the worker: nothing is injected into index.html (the CSP
        // allows no inline script). Off in `vite` dev; only builds have a service worker.
        injectRegister: false,
        devOptions: { enabled: false },
        manifest: {
          name: 'Sideline',
          short_name: 'Sideline',
          description: 'Your teams. Every moment.',
          display: 'standalone',
          start_url: '/',
          scope: '/',
          theme_color: '#16794c',
          background_color: '#f7f8fa',
          icons: [
            { src: '/pwa-192x192.png', sizes: '192x192', type: 'image/png' },
            { src: '/pwa-512x512.png', sizes: '512x512', type: 'image/png' },
            {
              src: '/maskable-512x512.png',
              sizes: '512x512',
              type: 'image/png',
              purpose: 'maskable',
            },
          ],
        },
      }),
    ],
    build: {
      // Vite inlines small files as data: URLs; the gateway's CSP has font-src 'self' (no data:),
      // so a small font subset would be blocked. Fonts always ship as files.
      assetsInlineLimit: (file: string) => (/\.woff2?$/.test(file) ? false : undefined),
    },
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
