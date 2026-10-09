import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AlertType, Follow } from '../api/types'
import { follow, nflCatalog } from '../test/fixtures'
import { renderApp, type Reply, type Routes } from '../test/renderApp'

afterEach(() => {
  vi.unstubAllGlobals()
})

const path = `/follows/${follow.id}`

function openSettings(routes: Routes = {}) {
  return renderApp(`/following/${follow.id}`, { [path]: { status: 200, body: follow }, ...routes })
}

/** GET answers with the follow; PATCH applies the body (or answers `patch` when given). */
function followRoute(start: Follow, patch?: () => Reply) {
  let current = structuredClone(start)
  return (sent: { method: string; body: unknown }): Reply => {
    if (sent.method === 'PATCH') {
      if (patch) return patch()
      const { alerts } = sent.body as { alerts: Follow['alerts'] }
      current = { ...current, alerts: { ...current.alerts, ...alerts } }
    }
    if (sent.method === 'DELETE') return { status: 204 }
    return { status: 200, body: current }
  }
}

const mlbCatalog: AlertType[] = [
  {
    type: 'END_OF_INNING',
    name: 'End of inning',
    description: 'The score after each inning you choose.',
    category: 'game_flow',
    appliesTo: ['team', 'game'],
    defaults: { team: false, game: false },
    options: [{ key: 'innings', kind: 'inning_set', label: 'Which innings' }],
    precision: 'from_snapshots',
    availability: 'available',
  },
]

const mlbFollow: Follow = {
  id: 'f_mlb',
  target: { kind: 'team', id: 'mlb-chc', displayName: 'Chicago Cubs' },
  leagueId: 'MLB',
  alerts: { END_OF_INNING: { enabled: true } },
  expiresAt: null,
  createdAt: '2026-10-01T15:10:00Z',
}

describe('alert switches', () => {
  it('draws one row per catalog alert: name, description, switch', async () => {
    openSettings()
    expect(await screen.findByRole('heading', { name: 'Chicago Bears' })).toBeInTheDocument()
    for (const alert of nflCatalog) {
      expect(await screen.findByText(alert.description)).toBeInTheDocument()
      expect(screen.getByRole('switch', { name: alert.name })).toBeInTheDocument()
    }
    expect(screen.getByRole('switch', { name: 'Touchdown' })).toHaveAttribute(
      'aria-checked',
      'true',
    )
  })

  it('a coming-soon alert says so and its switch is off and disabled', async () => {
    openSettings()
    const red = await screen.findByRole('switch', { name: 'Red zone' })
    expect(red).toBeDisabled()
    expect(red).toHaveAttribute('aria-checked', 'false')
    expect(screen.getByText('Coming soon')).toBeInTheDocument()
  })

  it('a change sends only that type and shows at once', async () => {
    const app = openSettings({ [path]: followRoute(follow) })
    const touchdown = await screen.findByRole('switch', { name: 'Touchdown' })
    fireEvent.click(touchdown)
    expect(touchdown).toHaveAttribute('aria-checked', 'false')
    await waitFor(() => expect(app.sent(path, 'PATCH')).toHaveLength(1))
    expect(app.sent(path, 'PATCH')[0].body).toEqual({ alerts: { TOUCHDOWN: { enabled: false } } })
    await new Promise((r) => setTimeout(r, 20))
    expect(touchdown).toHaveAttribute('aria-checked', 'false')
  })

  it('a failed change flips back and shows an error toast', async () => {
    openSettings({
      [path]: followRoute(follow, () => ({ status: 500, body: 'oops' })),
    })
    const touchdown = await screen.findByRole('switch', { name: 'Touchdown' })
    fireEvent.click(touchdown)
    expect(touchdown).toHaveAttribute('aria-checked', 'false')
    await waitFor(() => expect(touchdown).toHaveAttribute('aria-checked', 'true'))
    expect(await screen.findByText(/Something went wrong on our side/)).toBeInTheDocument()
  })

  it('changes to one follow go one at a time, in order', async () => {
    const app = openSettings({ [path]: followRoute(follow) })
    fireEvent.click(await screen.findByRole('switch', { name: 'Touchdown' }))
    fireEvent.click(screen.getByRole('switch', { name: 'Close game late' }))
    await waitFor(() => expect(app.sent(path, 'PATCH')).toHaveLength(2))
    expect(app.sent(path, 'PATCH').map((s) => s.body)).toEqual([
      { alerts: { TOUCHDOWN: { enabled: false } } },
      { alerts: { CLOSE_LATE: { enabled: false } } },
    ])
    expect(screen.getByRole('switch', { name: 'Touchdown' })).toHaveAttribute(
      'aria-checked',
      'false',
    )
    expect(screen.getByRole('switch', { name: 'Close game late' })).toHaveAttribute(
      'aria-checked',
      'false',
    )
  })

  it('an inning set offers innings 1–9; choosing 3 sends it', async () => {
    const app = renderApp('/following/f_mlb', {
      '/follows/f_mlb': followRoute(mlbFollow),
      '/leagues/MLB/alerts': { status: 200, body: { items: mlbCatalog } },
    })
    const innings = await screen.findByRole('group', { name: /Which innings/ })
    expect(within(innings).getByText(/none chosen means every inning/)).toBeInTheDocument()
    const boxes = within(innings).getAllByRole('checkbox')
    expect(boxes.map((b) => b.getAttribute('aria-label'))).toEqual(
      Array.from({ length: 9 }, (_, i) => `Inning ${i + 1}`),
    )
    fireEvent.click(within(innings).getByRole('checkbox', { name: 'Inning 3' }))
    expect(within(innings).getByRole('checkbox', { name: 'Inning 3' })).toBeChecked()
    await waitFor(() => expect(app.sent('/follows/f_mlb', 'PATCH')).toHaveLength(1))
    expect(app.sent('/follows/f_mlb', 'PATCH')[0].body).toEqual({
      alerts: { END_OF_INNING: { enabled: true, options: { innings: [3] } } },
    })
  })

  it('a from-snapshots alert carries the note', async () => {
    renderApp('/following/f_mlb', {
      '/follows/f_mlb': { status: 200, body: mlbFollow },
      '/leagues/MLB/alerts': { status: 200, body: { items: mlbCatalog } },
    })
    expect(await screen.findByText(/some moments may be missed/)).toBeInTheDocument()
  })

  it('Unfollow asks first, then deletes and goes to Following', async () => {
    const app = openSettings({
      [path]: followRoute(follow),
      '/follows': { status: 200, body: { items: [] } },
    })
    fireEvent.click(await screen.findByRole('button', { name: 'Unfollow' }))
    const dialog = await screen.findByRole('dialog', { name: 'Unfollow this favorite?' })
    expect(app.sent(path, 'DELETE')).toHaveLength(0)
    fireEvent.click(within(dialog).getByRole('button', { name: 'Unfollow' }))
    await waitFor(() => expect(app.location()).toBe('/following'))
    expect(app.sent(path, 'DELETE')).toHaveLength(1)
  })

  it('a follow that is gone says so', async () => {
    renderApp('/following/f_gone')
    expect(await screen.findByText('Follow not found')).toBeInTheDocument()
  })
})
