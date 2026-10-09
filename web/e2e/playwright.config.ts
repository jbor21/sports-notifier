import { defineConfig } from '@playwright/test'

// Two kinds of browser test, picked with --project:
// - pwa: two local builds and e2e/serve.mjs, no cluster (`pnpm test:pwa`, CI `web` job).
// - cluster: the real app on the laptop or CI k3d cluster at :8080 (`make e2e`).
export default defineConfig({
  testDir: '.',
  // Both pwa tests use port 4173, and the cluster test shares one simulator: one at a time.
  workers: 1,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  reporter: 'list',
  // web/test-results, whatever the working directory: CI uploads it on failure (traces).
  outputDir: '../test-results',
  use: {
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'pwa',
      testMatch: /pwa-update/,
      use: { browserName: 'chromium', baseURL: 'http://localhost:4173' },
    },
    {
      name: 'cluster',
      testMatch: /cluster/,
      use: { browserName: 'chromium', baseURL: 'http://localhost:8080', timezoneId: 'UTC' },
    },
  ],
})
