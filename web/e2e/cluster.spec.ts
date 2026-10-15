// The UI end to end against the real cluster (Phase 3b spec, 9.4): sign up, follow the Chiefs,
// see the simulator's touchdown arrive, survive a gateway restart mid-game, read history, sign out.
// Run with `make e2e` after `make cluster up` (CI runs it after `make smoke`).
import { execFileSync } from 'node:child_process'
import { resolve } from 'node:path'
import { expect, test, type Page } from '@playwright/test'

const KUBE_CONTEXT = process.env.KUBE_CONTEXT || 'k3d-sports-notifier'
const repoRoot = resolve(process.cwd(), '..')

function kubectl(...args: string[]): string {
  return execFileSync('kubectl', ['--context', KUBE_CONTEXT, ...args], { encoding: 'utf8' })
}

async function version(page: Page): Promise<number> {
  return Number(await page.locator('section.detail-score').getAttribute('data-version'))
}

async function connects(page: Page): Promise<number> {
  return Number(await page.locator('.app').getAttribute('data-connects'))
}

test('sign up, follow, live touchdown, gateway restart, history, sign out', async ({ page }) => {
  test.setTimeout(8 * 60 * 1000)
  const username = `e2e_${String(Math.floor(Math.random() * 1e8)).padStart(8, '0')}`

  // 1. Sign up through the form.
  await page.goto('/signup')
  await page.getByLabel('Username').fill(username)
  await page.getByLabel('Password').fill('e2e-password-1')
  await page.getByRole('button', { name: 'Create account' }).click()
  await expect(page).toHaveURL(/\/$/)

  // 2. Follow the Chiefs through the dialog; their switches come from the catalog.
  await page.getByRole('button', { name: 'Explore & follow' }).click()
  const dialog = page.getByRole('dialog', { name: 'Find your sideline' })
  await dialog.getByRole('button', { name: 'Teams' }).click()
  await dialog.getByRole('combobox').selectOption('NFL')
  await dialog.getByLabel('Search teams, leagues or games').fill('Kansas')
  await dialog.getByRole('button', { name: 'Follow Kansas City Chiefs' }).click()
  await expect(page).toHaveURL(/\/following\/[^/]+$/)
  await expect(page.getByRole('switch', { name: 'Touchdown' })).toHaveAttribute(
    'aria-checked',
    'true',
  )
  await expect(page.getByText('Coming soon').first()).toBeVisible()
  await expect(page.locator('[role=switch][disabled]').first()).toBeVisible()

  // 3. Game day, with the socket open before any play is sent.
  await page.getByRole('link', { name: 'Game day' }).first().click()
  await expect(page.locator('.app')).toHaveAttribute('data-connection', 'open', { timeout: 30_000 })
  //    The ping clock runs in a worker (#36: a hidden tab's own timers are too slow for the
  //    server's 60 s idle close). It must load from the gateway under its CSP, and run.
  await expect
    .poll(
      () =>
        page
          .workers()
          .find((w) => w.url().includes('pingWorker'))
          ?.url(),
      {
        timeout: 10_000,
      },
    )
    .toBeTruthy()
  const pingWorker = page.workers().find((w) => w.url().includes('pingWorker'))!
  expect(await pingWorker.evaluate(() => typeof self.postMessage)).toBe('function')

  // 4. One wave of games, 5 s per step: the NFL game lasts about 4 minutes, so a gateway restart
  //    (20–40 s) can't outlast it. Nothing reloads the page from here on, so no toast is lost.
  kubectl(
    'delete',
    'jobs',
    '-l',
    'app.kubernetes.io/name=simulator',
    '--cascade=foreground',
    '--wait=true',
    '--ignore-not-found',
  )
  execFileSync(resolve(repoRoot, 'scripts/simulate.sh'), ['SIM_WAVES=1', 'SIM_INTERVAL_MS=5000'], {
    env: { ...process.env, KUBE_CONTEXT },
    stdio: ['ignore', 'pipe', 'inherit'],
  })

  // 5. The touchdown arrives as a toast, and the badge counts it.
  const touchdown = page.locator('.toast-stack .toast').filter({ hasText: 'Touchdown' }).first()
  await expect(touchdown).toBeVisible({ timeout: 90_000 })
  const badge = page.getByTestId('badge').first()
  await expect(badge).toHaveText(/^[1-9]\d*$/)

  // 6. The toast opens the game page in the app (no reload); a game_update raises the version.
  await touchdown.locator('.toast-content').click()
  await expect(page).toHaveURL(/\/games\/nfl-sim[^/]+$/)
  const gameId = decodeURIComponent(page.url().split('/games/')[1])
  await expect(page.locator('section.detail-score')).toHaveAttribute('data-version', /\d+/)
  const v1 = await version(page)
  await expect.poll(() => version(page), { timeout: 60_000 }).toBeGreaterThan(v1)

  // 7. Restart the gateway mid-game: the tab gets 4000, reconnects to the new pod, re-subscribes.
  const before = await connects(page)
  kubectl('rollout', 'restart', 'deployment/gateway')
  kubectl('rollout', 'status', 'deployment/gateway', '--timeout=180s')
  await expect.poll(() => connects(page), { timeout: 120_000 }).toBeGreaterThan(before)
  await expect(page.locator('.app')).toHaveAttribute('data-connection', 'open', { timeout: 60_000 })

  // 8. The rest of the test needs the game still going; say so plainly if it isn't. Just after a
  //    rollout, Traefik can answer 504 for a few seconds (requests still routed to the old pod),
  //    so ask until the gateway answers.
  let status = ''
  await expect
    .poll(
      async () => {
        const r = await page.request.get(`/api/v1/games/${encodeURIComponent(gameId)}`)
        if (r.ok()) status = (await r.json()).status
        return r.status()
      },
      { timeout: 60_000 },
    )
    .toBe(200)
  expect(
    status,
    'the game ended during the gateway restart; the e2e timing assumption is broken',
  ).toBe('in_progress')

  // 9. Updates flow again after the reconnect.
  const v2 = await version(page)
  await expect.poll(() => version(page), { timeout: 60_000 }).toBeGreaterThan(v2)

  // 10. Back on Game day the game is listed as live.
  await page.getByRole('link', { name: 'Back to games' }).click()
  const card = page.locator(`article[data-game-id="${gameId}"]`)
  await expect(card).toContainText('LIVE', { timeout: 30_000 })

  // 11. History has the touchdown; opening it clears the badge, and a reload keeps the session.
  await page.getByRole('link', { name: 'Notifications' }).first().click()
  await expect(page.locator('.notice-item').filter({ hasText: 'Touchdown' }).first()).toBeVisible({
    timeout: 30_000,
  })
  await expect(page.getByTestId('badge')).toHaveCount(0)
  await page.reload()
  await expect(page).toHaveURL(/\/notifications$/)
  await expect(page.locator('.notice-item').first()).toBeVisible({ timeout: 30_000 })
  await expect(page.getByTestId('badge')).toHaveCount(0)

  // 12. Sign out.
  await page.getByRole('link', { name: 'Settings' }).first().click()
  await page.getByRole('button', { name: 'Sign out' }).click()
  await expect(page).toHaveURL(/\/signin$/)
})
