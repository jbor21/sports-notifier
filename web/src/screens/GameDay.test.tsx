import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Game, Notice } from '../api/types'
import { todayIn, dateStrip } from '../dates'
import { gameStore } from '../live/games'
import { addPushed, clearPushed } from '../live/pushed'
import { gameSummary, notification, user } from '../test/fixtures'
import { nfl, renderApp, type Reply, type Routes } from '../test/renderApp'

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  clearPushed()
})

// The game store outlives a test, so every test's games get ids of their own.
let seq = 0
function game(changes: Partial<Game> = {}): Game {
  return { ...structuredClone(gameSummary), id: `nfl-day${++seq}`, ...changes }
}

const scheduled = (startTime: string): Partial<Game> => ({
  status: 'scheduled',
  startTime,
  version: 0,
  period: null,
  periodLabel: null,
  clock: null,
})

const games = (...items: Game[]): Reply => ({
  status: 200,
  body: { date: todayIn(user.timezone), items },
})

function openDay(routes: Routes) {
  return renderApp('/', routes)
}

async function card(id: string): Promise<HTMLElement> {
  await waitFor(() => expect(document.querySelector(`[data-game-id="${id}"]`)).not.toBeNull())
  return document.querySelector(`[data-game-id="${id}"]`) as HTMLElement
}

describe('Game day', () => {
  it('names each card’s link with both teams and their scores, for screen readers', async () => {
    const g = game()
    openDay({ '/games': games(g) })
    await card(g.id)
    const name = `${g.away.team.displayName} ${g.away.score}, ${g.home.team.displayName} ${g.home.score}`
    expect(screen.getByRole('button', { name: new RegExp(`^${name}`) })).toBeInTheDocument()
  })

  it('asks for today in the user’s timezone', async () => {
    const app = openDay({ '/games': games(game()) })
    await waitFor(() => expect(app.calls('/games')).toBeGreaterThan(0))
    const query = app.sent('/games')[0].query
    expect(query.get('date')).toBe(todayIn(user.timezone))
    expect(query.get('tz')).toBe(user.timezone)
    expect(query.has('league')).toBe(false)
    expect(query.has('following')).toBe(false)
  })

  it('shows 9 days and refetches the one clicked', async () => {
    const app = openDay({ '/games': games(game()) })
    const strip = dateStrip(user.timezone)
    await screen.findByRole('group', { name: 'Pick a day' })
    const days = document.querySelectorAll('[data-date]')
    expect([...days].map((d) => d.getAttribute('data-date'))).toEqual(strip)
    expect(document.querySelector(`[data-date="${strip[1]}"]`)).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    fireEvent.click(document.querySelector(`[data-date="${strip[3]}"]`)!)
    await waitFor(() =>
      expect(app.sent('/games').some((s) => s.query.get('date') === strip[3])).toBe(true),
    )
    expect(document.querySelector(`[data-date="${strip[3]}"]`)).toHaveAttribute(
      'aria-pressed',
      'true',
    )
  })

  it('the Following toggle asks for following=true', async () => {
    const app = openDay({ '/games': games(game()) })
    fireEvent.click(await screen.findByRole('switch', { name: 'Only following' }))
    await waitFor(() =>
      expect(app.sent('/games').some((s) => s.query.get('following') === 'true')).toBe(true),
    )
  })

  it('a league tab asks for that league', async () => {
    const app = openDay({ '/games': games(game()) })
    fireEvent.click(await screen.findByRole('tab', { name: 'NFL' }))
    await waitFor(() =>
      expect(app.sent('/games').some((s) => s.query.get('league') === 'NFL')).toBe(true),
    )
  })

  it('Live only hides a final game', async () => {
    const live = game()
    const final = game({ status: 'final', periodLabel: 'Final', clock: null })
    openDay({ '/games': games(final, live) })
    await card(final.id)
    fireEvent.click(screen.getByRole('button', { name: 'Show only live games' }))
    await waitFor(() => expect(document.querySelector(`[data-game-id="${final.id}"]`)).toBeNull())
    expect(document.querySelector(`[data-game-id="${live.id}"]`)).not.toBeNull()
  })

  it('groups games by league', async () => {
    const nba = { ...nfl, id: 'NBA', name: 'National Basketball Association' }
    const football = game()
    const basketball = game({ leagueId: 'NBA' })
    openDay({
      '/leagues': { status: 200, body: { items: [nfl, nba] } },
      '/games': games(basketball, football),
    })
    await card(football.id)
    const sections = [...document.querySelectorAll('.league-section')]
    expect(sections.map((s) => s.querySelector('h2')?.textContent)).toEqual(['NFL', 'NBA'])
    expect(within(sections[1] as HTMLElement).getByText(/National Basketball/)).toBeInTheDocument()
    expect(sections[1].querySelector(`[data-game-id="${basketball.id}"]`)).not.toBeNull()
  })

  it('subscribes the cards, live first, then soonest', async () => {
    const early = game({ ...scheduled('2026-10-04T13:00:00Z') })
    const final = game({ status: 'final', startTime: '2026-10-04T12:00:00Z', periodLabel: 'Final' })
    const live = game({ startTime: '2026-10-04T17:00:00Z' })
    const app = openDay({ '/games': games(early, final, live) })
    await card(live.id)
    act(() => app.sockets.last().open())
    await waitFor(() => {
      const subs = app.sockets
        .last()
        .sentMessages()
        .filter((m) => m.type === 'subscribe_games')
      expect(subs.at(-1)?.gameIds).toEqual([live.id, final.id, early.id])
    })
  })

  it('a score change shows +7 and the ring for 4 s', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const g = game()
    openDay({ '/games': games(g) })
    const el = await card(g.id)
    expect(el).toHaveClass('live-game')
    act(() => {
      gameStore.accept({ ...g, version: g.version + 1, away: { ...g.away, score: 27 } })
    })
    expect(within(el).getByText('+7')).toHaveClass('delta')
    expect(within(el).getByText('27')).toHaveClass('score-animate')
    expect(el).toHaveClass('changed')
    expect(el.querySelector('[data-version]')).toHaveAttribute(
      'data-version',
      String(g.version + 1),
    )
    act(() => vi.advanceTimersByTime(4000))
    expect(within(el).queryByText('+7')).not.toBeInTheDocument()
    expect(el).not.toHaveClass('changed')
  })

  it('a clock-only update right after a score keeps the chip', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const g = game()
    openDay({ '/games': games(g) })
    const el = await card(g.id)
    act(() => {
      gameStore.accept({ ...g, version: g.version + 1, away: { ...g.away, score: 27 } })
    })
    act(() => vi.advanceTimersByTime(1000))
    act(() => {
      gameStore.accept({
        ...g,
        version: g.version + 2,
        away: { ...g.away, score: 27 },
        clock: '3:58',
      })
    })
    expect(within(el).getByText('+7')).toBeInTheDocument()
    act(() => vi.advanceTimersByTime(3000))
    expect(within(el).queryByText('+7')).not.toBeInTheDocument()
  })

  it('shows the clock as given; it does not tick', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const g = game()
    openDay({ '/games': games(g) })
    const el = await card(g.id)
    expect(within(el).getByText('Q4 · 4:12')).toBeInTheDocument()
    await act(() => vi.advanceTimersByTimeAsync(61_000))
    expect(within(el).getByText('Q4 · 4:12')).toBeInTheDocument()
  })

  it('an unknown status renders like a live game', async () => {
    const g = game({ status: 'weather_hold' as Game['status'], periodLabel: 'Q2', clock: '8:00' })
    openDay({ '/games': games(g) })
    const el = await card(g.id)
    expect(el).toHaveClass('live-game')
    expect(within(el).getByText('Q2 · 8:00')).toBeInTheDocument()
    expect(within(el).getByText(/LIVE/)).toBeInTheDocument()
  })

  it('a scheduled game shows its start time in the user’s timezone', async () => {
    const g = game(scheduled('2026-10-04T17:00:00Z'))
    openDay({ '/games': games(g) })
    const el = await card(g.id)
    expect(within(el).getByText('1:00 PM')).toBeInTheDocument()
    expect(within(el).getAllByText('—')).toHaveLength(2)
  })

  it('an empty day says so', async () => {
    openDay({ '/games': games() })
    expect(await screen.findByText('No games on the board')).toBeInTheDocument()
  })

  it('a server error shows the error box with Try again', async () => {
    openDay({ '/games': { status: 500, body: 'oops' } })
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Something went wrong on our side')
    expect(within(alert).getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })

  it('refetches every 60 s', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const g = game()
    const app = openDay({ '/games': games(g) })
    await card(g.id)
    const before = app.calls('/games')
    await act(() => vi.advanceTimersByTimeAsync(55_000))
    expect(app.calls('/games')).toBe(before)
    await act(() => vi.advanceTimersByTimeAsync(5_000))
    await waitFor(() => expect(app.calls('/games')).toBe(before + 1))
  })

  it('latest alerts merge pushed notifications with history', async () => {
    const fetched: Notice = { ...notification, id: 'n-fetched', createdAt: '2026-10-04T20:00:00Z' }
    const fresh: Notice = {
      ...notification,
      id: 'n-pushed',
      createdAt: '2026-10-04T20:05:00Z',
      headline: { type: 'CLOSE_LATE', text: 'Close game late. Raiders 20, Bears 17' },
    }
    addPushed(fresh)
    openDay({
      '/games': games(game()),
      '/notifications': { status: 200, body: { items: [fetched], nextCursor: null } },
    })
    const aside = (await screen.findByRole('heading', { name: /Latest alerts/ })).closest(
      'section',
    ) as HTMLElement
    await waitFor(() => expect(within(aside).getByText(fetched.headline.text)).toBeInTheDocument())
    expect(within(aside).getByText(fresh.headline.text)).toBeInTheDocument()
  })
})
