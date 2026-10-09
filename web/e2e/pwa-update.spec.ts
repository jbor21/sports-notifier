import { expect, test, type Page } from '@playwright/test'
import { spawn, type ChildProcess } from 'node:child_process'
import { cp, mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

// Spec 9.3: a tab opened on build one picks up build two, by the toast or by the next in-app link.
// `pnpm test:pwa` builds both into dist-pwa/ first.

const builds = join(import.meta.dirname, '..', 'dist-pwa')
const PORT = 4173

let folder: string
let server: ChildProcess

async function serve(build: 'one' | 'two') {
  await rm(folder, { recursive: true, force: true })
  await cp(join(builds, build), folder, { recursive: true })
}

async function waitForServer() {
  for (let i = 0; i < 100; i++) {
    try {
      await fetch(`http://localhost:${PORT}/`)
      return
    } catch {
      await new Promise((r) => setTimeout(r, 100))
    }
  }
  throw new Error('serve.mjs did not start')
}

test.beforeEach(async () => {
  folder = await mkdtemp(join(tmpdir(), 'sideline-pwa-'))
  await serve('one')
  server = spawn('node', [join(import.meta.dirname, 'serve.mjs'), folder, String(PORT)], {
    stdio: 'inherit',
  })
  await waitForServer()
})

test.afterEach(async () => {
  server.kill()
  await new Promise((r) => server.once('exit', r))
  await rm(folder, { recursive: true, force: true })
})

/** The CSP the gateway sends must not block anything the build needs. */
function watchCsp(page: Page): string[] {
  const violations: string[] = []
  page.on('console', (msg) => {
    if (/Content Security Policy/i.test(msg.text())) violations.push(msg.text())
  })
  return violations
}

const buildId = (page: Page) => page.locator('meta[name="build-id"]')

/** Build one, controlled by its service worker, on the sign-in screen (no backend). */
async function openBuildOne(page: Page) {
  await page.goto('/')
  await page.waitForFunction(() => navigator.serviceWorker.controller !== null)
  await expect(buildId(page)).toHaveAttribute('content', 'one')
  await expect(page.getByRole('link', { name: 'Create an account' })).toBeVisible()
}

/** Deploy build two and ask the browser to check, as the tab does when it becomes visible. */
async function deployBuildTwo(page: Page) {
  await serve('two')
  await page.evaluate(() => navigator.serviceWorker.getRegistration().then((r) => r?.update()))
  await expect(page.getByText('New version')).toBeVisible()
}

test('Reload on the "New version" toast loads the new build', async ({ page }) => {
  const csp = watchCsp(page)
  await openBuildOne(page)
  await deployBuildTwo(page)

  await page.getByRole('button', { name: 'Reload' }).click()
  await expect(buildId(page)).toHaveAttribute('content', 'two')
  expect(csp).toEqual([])
})

test('with the toast left alone, the next in-app link loads the new build there', async ({
  page,
}) => {
  const csp = watchCsp(page)
  await openBuildOne(page)
  await deployBuildTwo(page)

  await page.getByRole('link', { name: 'Create an account' }).click()
  await expect(page).toHaveURL(/\/signup(\?|$)/)
  await expect(buildId(page)).toHaveAttribute('content', 'two')
  expect(csp).toEqual([])
})
