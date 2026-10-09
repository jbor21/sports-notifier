import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Follow, Game, GameDetail, Notice } from '../api/types'
import { gameStore } from '../live/games'
import { addPushed, clearPushed } from '../live/pushed'
import { follow, gameSummary, notification } from '../test/fixtures'
import { renderApp, type Routes } from '../test/renderApp'

afterEach(() => {
  vi.unstubAllGlobals()
  clearPushed()
})

// The game store outlives a test, so every test's game gets an id of its own.
let seq = 0
function detail(changes: Partial<GameDetail> = {}): GameDetail {
  return {
    ...structuredClone(gameSummary),
    id: `nfl-page${++seq}`,
    lineScore: [
      { period: 1, label: 'Q1', home: 7, away: 3 },
      { period: 2, label: 'Q2', home: 3, away: 10 },
      { period: 3, label: 'Q3', home: 7, away: 0 },
      { period: 4, label: 'Q4', home: 0, away: 7 },
    ],
    venue: 'Soldier Field',
    broadcast: 'FOX',
    ...changes,
  }
}

function openGame(g: GameDetail, routes: Routes = {}) {
  return renderApp(`/games/${g.id}`, { [`/games/${g.id}`]: { status: 200, body: g }, ...routes })
}

const noticeFor = (gameId: string, id: string, text: string, createdAt: string): Notice => ({
  ...structuredClone(notification),
  id,
  createdAt,
  headline: { type: 'TOUCHDOWN', text },
  game: { ...notification.game!, id: gameId },
})

describe('game page', () => {
  it('shows the score, line score, venue, and broadcast', async () => {
    const g = detail()
    openGame(g)
    expect(await screen.findByText('Soldier Field · FOX')).toBeInTheDocument()
    const table = screen.getByRole('table')
    for (const label of ['Q1', 'Q2', 'Q3', 'Q4', 'Total'])
      expect(within(table).getByRole('columnheader', { name: label })).toBeInTheDocument()
    const away = within(table).getByRole('row', { name: /Las Vegas Raiders/ })
    expect(
      within(away)
        .getAllByRole('cell')
        .map((c) => c.textContent),
    ).toEqual(['3', '10', '0', '7', '20'])
    const score = document.querySelector('[data-version]')!
    expect(score).toHaveAttribute('data-version', String(g.version))
    expect(within(score as HTMLElement).getByText('20')).toHaveClass('live-score')
    expect(screen.getByRole('link', { name: /Back to games/ })).toHaveAttribute('href', '/')
  })

  it('lists recent alerts: history for this game plus pushed ones', async () => {
    const g = detail()
    const fetched = noticeFor(g.id, 'a1', 'Field goal Bears.', '2026-10-04T19:00:00Z')
    addPushed(noticeFor(g.id, 'a2', 'Touchdown Raiders!', '2026-10-04T20:01:00Z'))
    addPushed(noticeFor('nfl-other', 'a3', 'Touchdown elsewhere.', '2026-10-04T20:02:00Z'))
    addPushed({
      ...noticeFor(g.id, 'a4', 'Something new.', '2026-10-04T20:03:00Z'),
      headline: { type: 'MYSTERY', text: 'Something new.' },
    })
    const app = openGame(g, {
      '/notifications': { status: 200, body: { items: [fetched], nextCursor: null } },
    })
    expect(await screen.findByText('Field goal Bears.')).toBeInTheDocument()
    expect(app.sent('/notifications')[0].query.get('game')).toBe(g.id)
    const items = [...document.querySelectorAll('.notice-item')].map((n) => n.textContent)
    expect(items).toHaveLength(2)
    expect(items[0]).toContain('Touchdown Raiders!')
    expect(items[1]).toContain('Field goal Bears.')
  })

  it('subscribes to this one game', async () => {
    const g = detail()
    const app = openGame(g)
    await screen.findByText('Soldier Field · FOX')
    act(() => app.sockets.last().open())
    await waitFor(() => {
      const subs = app.sockets
        .last()
        .sentMessages()
        .filter((m) => m.type === 'subscribe_games')
      expect(subs.at(-1)?.gameIds).toEqual([g.id])
    })
  })

  it('refetches the detail when the period changes, not on a clock tick', async () => {
    const g = detail()
    const app = openGame(g)
    await screen.findByText('Soldier Field · FOX')
    const path = `/games/${g.id}`
    expect(app.calls(path)).toBe(1)
    const summary: Game = { ...g }
    act(() => {
      gameStore.accept({ ...summary, version: g.version + 1, clock: '3:59' })
    })
    expect(await screen.findByText(/3:59/)).toBeInTheDocument()
    await act(() => new Promise((r) => setTimeout(r, 50)))
    expect(app.calls(path)).toBe(1)
    act(() => {
      gameStore.accept({
        ...summary,
        version: g.version + 2,
        period: 5,
        periodLabel: 'OT',
        clock: '10:00',
      })
    })
    await waitFor(() => expect(app.calls(path)).toBe(2))
  })

  it('a game the API does not know is "Game not found"', async () => {
    renderApp('/games/nfl-nope')
    expect(await screen.findByText('Game not found')).toBeInTheDocument()
  })

  it('follows and unfollows this game', async () => {
    const g = detail()
    const created: Follow = {
      ...structuredClone(follow),
      id: 'f_game',
      target: { kind: 'game', id: g.id, displayName: 'Las Vegas Raiders at Chicago Bears' },
    }
    let followed = false
    const app = openGame(g, {
      '/follows': (sent) => {
        if (sent.method === 'POST') {
          followed = true
          return { status: 201, body: created }
        }
        return { status: 200, body: { items: followed ? [created] : [] } }
      },
      '/follows/f_game': () => {
        followed = false
        return { status: 204 }
      },
    })
    const button = await screen.findByRole('button', { name: 'Follow game' })
    await waitFor(() => expect(button).toBeEnabled())
    fireEvent.click(button)
    expect(button).toBeDisabled()
    fireEvent.click(button)
    expect(await screen.findByRole('button', { name: 'Unfollow game' })).toBeInTheDocument()
    expect(app.sent('/follows', 'POST').map((s) => s.body)).toEqual([
      { target: { kind: 'game', id: g.id } },
    ])
    fireEvent.click(screen.getByRole('button', { name: 'Unfollow game' }))
    expect(await screen.findByRole('button', { name: 'Follow game' })).toBeInTheDocument()
    expect(app.sent('/follows/f_game', 'DELETE')).toHaveLength(1)
  })
})
