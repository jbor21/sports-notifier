import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Notice, Team } from '../api/types'
import { badge, setCount } from '../live/badge'
import { addPushed, clearPushed, pushed } from '../live/pushed'
import { notification } from '../test/fixtures'
import { renderApp, type Reply, type Routes } from '../test/renderApp'

let visibility: DocumentVisibilityState = 'visible'

beforeEach(() => {
  visibility = 'visible'
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    get: () => visibility,
  })
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  act(() => {
    clearPushed()
    setCount(0)
  })
})

const notice = (id: string, createdAt: string, text = `Alert ${id}`): Notice => ({
  ...structuredClone(notification),
  id,
  createdAt,
  headline: { type: 'TOUCHDOWN', text },
})

const page = (items: Notice[], nextCursor: string | null = null): Reply => ({
  status: 200,
  body: { items, nextCursor },
})

const bears: Team = {
  id: 'nfl-chi',
  leagueId: 'NFL',
  abbreviation: 'CHI',
  displayName: 'Chicago Bears',
}

function openNotifications(routes: Routes = {}) {
  return renderApp('/notifications', {
    '/notifications': page([notice('n1', '2026-10-04T20:00:00Z', 'Touchdown Bears!')]),
    '/notifications/seen': { status: 204 },
    '/leagues/NFL/teams': { status: 200, body: { items: [bears] } },
    ...routes,
  })
}

const seenCalls = (app: ReturnType<typeof openNotifications>) =>
  app.sent('/notifications/seen', 'POST').length

const shown = () => [...document.querySelectorAll('.notice-item')].map((n) => n.textContent)

describe('Notifications', () => {
  it('marks everything seen once on open and clears the badge', async () => {
    act(() => setCount(5))
    const app = openNotifications()
    expect(await screen.findByText('Touchdown Bears!')).toBeInTheDocument()
    await waitFor(() => expect(seenCalls(app)).toBe(1))
    await waitFor(() => expect(badge.get()).toBe(0))
    await new Promise((r) => setTimeout(r, 20))
    expect(seenCalls(app)).toBe(1)
    expect(screen.getByText('History is kept for 30 days')).toBeInTheDocument()
  })

  it('asks for 20 at a time, filtered by league and then team', async () => {
    const app = openNotifications()
    await screen.findByText('Touchdown Bears!')
    expect(app.sent('/notifications')[0].query.get('limit')).toBe('20')
    const teamSelect = screen.getByLabelText('Team')
    expect(teamSelect).toBeDisabled()

    fireEvent.change(screen.getByLabelText('League'), { target: { value: 'NFL' } })
    await waitFor(() =>
      expect(app.sent('/notifications').some((s) => s.query.get('league') === 'NFL')).toBe(true),
    )
    await waitFor(() => expect(teamSelect).toBeEnabled())
    await within(teamSelect).findByRole('option', { name: 'Chicago Bears' })
    fireEvent.change(teamSelect, { target: { value: 'nfl-chi' } })
    await waitFor(() =>
      expect(
        app
          .sent('/notifications')
          .some((s) => s.query.get('league') === 'NFL' && s.query.get('team') === 'nfl-chi'),
      ).toBe(true),
    )
  })

  it('Load older asks for the next cursor and goes away on the last page', async () => {
    const app = openNotifications({
      '/notifications?limit=20': page([notice('n1', '2026-10-04T20:00:00Z', 'Newest')], 'c2'),
      '/notifications?limit=20&cursor=c2': page([notice('n0', '2026-10-03T20:00:00Z', 'Older')]),
    })
    fireEvent.click(await screen.findByRole('button', { name: 'Load older alerts' }))
    expect(await screen.findByText('Older')).toBeInTheDocument()
    expect(app.sent('/notifications').some((s) => s.query.get('cursor') === 'c2')).toBe(true)
    expect(screen.queryByRole('button', { name: 'Load older alerts' })).not.toBeInTheDocument()
    expect(shown()[0]).toContain('Newest')
    expect(shown()[1]).toContain('Older')
  })

  it('a pushed notice goes on top without asking the server again', async () => {
    const app = openNotifications()
    await screen.findByText('Touchdown Bears!')
    const before = app.calls('/notifications')
    act(() => addPushed(notice('n2', '2026-10-04T20:05:00Z', 'Touchdown Raiders!')))
    expect(shown()[0]).toContain('Touchdown Raiders!')
    expect(shown()[1]).toContain('Touchdown Bears!')
    expect(app.calls('/notifications')).toBe(before)
  })

  it('a push while open marks it seen, at most once per 5 s', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const app = openNotifications()
    await screen.findByText('Touchdown Bears!')
    await waitFor(() => expect(seenCalls(app)).toBe(1))

    act(() => addPushed(notice('p1', '2026-10-04T20:05:00Z')))
    await waitFor(() => expect(seenCalls(app)).toBe(2))
    act(() => addPushed(notice('p2', '2026-10-04T20:05:01Z')))
    act(() => addPushed(notice('p3', '2026-10-04T20:05:02Z')))
    await act(() => vi.advanceTimersByTimeAsync(1000))
    expect(seenCalls(app)).toBe(2)

    // The pushes inside the window are covered by one call when it ends.
    await act(() => vi.advanceTimersByTimeAsync(4000))
    await waitFor(() => expect(seenCalls(app)).toBe(3))
    await act(() => vi.advanceTimersByTimeAsync(10_000))
    expect(seenCalls(app)).toBe(3)
  })

  it('a push while the tab is hidden does not mark it seen', async () => {
    const app = openNotifications()
    await screen.findByText('Touchdown Bears!')
    await waitFor(() => expect(seenCalls(app)).toBe(1))
    visibility = 'hidden'
    act(() => addPushed(notice('p1', '2026-10-04T20:05:00Z')))
    await new Promise((r) => setTimeout(r, 20))
    expect(seenCalls(app)).toBe(1)
  })

  it('skips a notice whose type it does not know', async () => {
    const mystery: Notice = {
      ...notice('n9', '2026-10-04T20:09:00Z'),
      headline: { type: 'MYSTERY', text: 'Something new.' },
    }
    openNotifications({
      '/notifications': page([mystery, notice('n1', '2026-10-04T20:00:00Z', 'Touchdown Bears!')]),
    })
    expect(await screen.findByText('Touchdown Bears!')).toBeInTheDocument()
    await waitFor(() => expect(shown()).toHaveLength(1))
    expect(screen.queryByText('Something new.')).not.toBeInTheDocument()
  })

  it('Clear history asks first, then deletes everything', async () => {
    let cleared = false
    const app = openNotifications({
      '/notifications': (sent) => {
        if (sent.method === 'DELETE') {
          cleared = true
          return { status: 204 }
        }
        return cleared ? page([]) : page([notice('n1', '2026-10-04T20:00:00Z', 'Touchdown Bears!')])
      },
    })
    await screen.findByText('Touchdown Bears!')
    act(() => addPushed(notice('p1', '2026-10-04T20:05:00Z', 'Pushed one')))
    act(() => setCount(2))
    fireEvent.click(screen.getByRole('button', { name: 'Clear history' }))
    const dialog = await screen.findByRole('dialog', { name: 'Clear notification history?' })
    expect(app.sent('/notifications', 'DELETE')).toHaveLength(0)
    fireEvent.click(within(dialog).getByRole('button', { name: 'Clear all history' }))
    expect(await screen.findByText("You're all caught up")).toBeInTheDocument()
    expect(app.sent('/notifications', 'DELETE')).toHaveLength(1)
    expect(shown()).toHaveLength(0)
    expect(pushed.get()).toEqual([])
    expect(badge.get()).toBe(0)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('an empty history says you are all caught up', async () => {
    openNotifications({ '/notifications': page([]) })
    expect(await screen.findByText("You're all caught up")).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Load older alerts' })).not.toBeInTheDocument()
  })
})
