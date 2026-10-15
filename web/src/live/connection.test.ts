import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { backoffDelay, LiveConnection, type ConnectionEvents } from './connection'
import { fakeSockets } from '../test/fakeSocket'
import { gameSummary, notification } from '../test/fixtures'

const half = () => 0.5

function setup(events: Partial<ConnectionEvents> = {}) {
  const sockets = fakeSockets()
  const conn = new LiveConnection('ws://test/api/v1/ws', events, {
    createSocket: sockets.create,
    random: half,
  })
  return { conn, sockets }
}

function setVisibility(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state })
  document.dispatchEvent(new Event('visibilitychange'))
}

const pings = (sent: string[]) => sent.filter((s) => JSON.parse(s).type === 'ping').length

/** A ticker the test fires by hand; running() lists the intervals of the ones not yet stopped. */
function fakeTicker() {
  const live: { ms: number; fn: () => void }[] = []
  return {
    ticker: (ms: number, fn: () => void) => {
      const t = { ms, fn }
      live.push(t)
      return () => live.splice(live.indexOf(t), 1)
    },
    running: () => live.map((t) => t.ms),
    tick: () => live.forEach((t) => t.fn()),
  }
}

let conn: LiveConnection | undefined

beforeEach(() => {
  vi.useFakeTimers()
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' })
})

afterEach(() => {
  conn?.stop()
  conn = undefined
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('LiveConnection', () => {
  it('subscribes on open', () => {
    const onReconnected = vi.fn()
    const s = setup({ onReconnected })
    conn = s.conn
    conn.setGames(['a', 'b'])
    conn.start()
    expect(conn.state).toBe('connecting')
    s.sockets.last().open()
    expect(s.sockets.last().sentMessages()[0]).toEqual({
      type: 'subscribe_games',
      gameIds: ['a', 'b'],
    })
    expect(conn.state).toBe('open')
    expect(onReconnected).not.toHaveBeenCalled()
  })

  it('sends setGames while open only when the list changes, at most 100 ids', () => {
    const s = setup()
    conn = s.conn
    conn.start()
    s.sockets.last().open()
    const before = s.sockets.last().sent.length
    conn.setGames(['a'])
    conn.setGames(['a'])
    expect(s.sockets.last().sent.length).toBe(before + 1)
    conn.setGames(Array.from({ length: 150 }, (_, i) => `g${i}`))
    const last = s.sockets.last().sentMessages().at(-1)!
    expect((last.gameIds as string[]).length).toBe(100)
  })

  it('pings every 25 s', () => {
    const s = setup()
    conn = s.conn
    conn.start()
    s.sockets.last().open()
    vi.advanceTimersByTime(25000)
    expect(pings(s.sockets.last().sent)).toBe(1)
    vi.advanceTimersByTime(25000)
    expect(pings(s.sockets.last().sent)).toBe(2)
  })

  it('pings on the ticker it was given, not on the page timers Chrome slows in a hidden tab', () => {
    const ticks = fakeTicker()
    const sockets = fakeSockets()
    conn = new LiveConnection(
      'ws://test/api/v1/ws',
      {},
      { createSocket: sockets.create, ticker: ticks.ticker },
    )
    conn.start()
    sockets.last().open()
    expect(ticks.running()).toEqual([25000])
    vi.advanceTimersByTime(60000)
    expect(pings(sockets.last().sent)).toBe(0)
    ticks.tick()
    expect(pings(sockets.last().sent)).toBe(1)
  })

  it('stops the ticker when the socket closes and starts one per open', () => {
    const ticks = fakeTicker()
    const sockets = fakeSockets()
    conn = new LiveConnection(
      'ws://test/api/v1/ws',
      {},
      {
        createSocket: sockets.create,
        random: half,
        ticker: ticks.ticker,
      },
    )
    conn.start()
    sockets.last().open()
    sockets.last().serverClose(1006)
    expect(ticks.running()).toEqual([])
    vi.advanceTimersByTime(1500)
    sockets.last().open()
    expect(ticks.running()).toEqual([25000])
    conn.stop()
    expect(ticks.running()).toEqual([])
  })

  it('pings at once when the tab becomes visible while open', () => {
    const s = setup()
    conn = s.conn
    conn.start()
    s.sockets.last().open()
    setVisibility('hidden')
    expect(pings(s.sockets.last().sent)).toBe(0)
    setVisibility('visible')
    expect(pings(s.sockets.last().sent)).toBe(1)
  })

  it('stops on 4401 and reports signed out', () => {
    const onSignedOut = vi.fn()
    const s = setup({ onSignedOut })
    conn = s.conn
    conn.start()
    s.sockets.last().open()
    s.sockets.last().serverClose(4401)
    expect(onSignedOut).toHaveBeenCalledTimes(1)
    expect(conn.state).toBe('signedOut')
    vi.advanceTimersByTime(60000)
    expect(s.sockets.all.length).toBe(1)
  })

  it('retries after jitter only on 4000', () => {
    const onState = vi.fn()
    const s = setup({ onState })
    conn = s.conn
    conn.start()
    s.sockets.last().open()
    const now = Date.now()
    s.sockets.last().serverClose(4000)
    expect(conn.state).toBe('waiting')
    expect(onState).toHaveBeenLastCalledWith('waiting', { retryAt: now + 500, connects: 1 })
    vi.advanceTimersByTime(499)
    expect(s.sockets.all.length).toBe(1)
    vi.advanceTimersByTime(1)
    expect(s.sockets.all.length).toBe(2)
  })

  it('backs off 1, 2, 4, 8, 16, 30, 30 s plus jitter', () => {
    const s = setup()
    conn = s.conn
    conn.start()
    const delays = [1500, 2500, 4500, 8500, 16500, 30500, 30500]
    for (const delay of delays) {
      const count = s.sockets.all.length
      s.sockets.last().serverClose(1006)
      vi.advanceTimersByTime(delay - 1)
      expect(s.sockets.all.length).toBe(count)
      vi.advanceTimersByTime(1)
      expect(s.sockets.all.length).toBe(count + 1)
    }
  })

  it('keeps the jitter between 0 and 1 s', () => {
    expect(backoffDelay(0, () => 0)).toBe(1000)
    expect(backoffDelay(0, () => 0.999)).toBe(1999)
    expect(backoffDelay(10, () => 0)).toBe(30000)
  })

  it.each([1013, 1000])('backs off on %i', (code) => {
    const s = setup()
    conn = s.conn
    conn.start()
    s.sockets.last().open()
    s.sockets.last().serverClose(code)
    expect(conn.state).toBe('waiting')
    vi.advanceTimersByTime(1499)
    expect(s.sockets.all.length).toBe(1)
    vi.advanceTimersByTime(1)
    expect(s.sockets.all.length).toBe(2)
  })

  it('resets the backoff after 60 s open', () => {
    const s = setup()
    conn = s.conn
    conn.start()
    s.sockets.last().serverClose(1006)
    vi.advanceTimersByTime(1500)
    s.sockets.last().serverClose(1006)
    vi.advanceTimersByTime(2500)
    s.sockets.last().open()
    vi.advanceTimersByTime(60000)
    s.sockets.last().serverClose(1006)
    const count = s.sockets.all.length
    vi.advanceTimersByTime(1500)
    expect(s.sockets.all.length).toBe(count + 1)
  })

  it('re-subscribes and asks for a refetch after a reconnect', () => {
    const info = vi.spyOn(console, 'info').mockImplementation(() => {})
    const onReconnected = vi.fn()
    const s = setup({ onReconnected })
    conn = s.conn
    conn.setGames(['g1'])
    conn.start()
    s.sockets.last().open()
    s.sockets.last().serverClose(1006)
    vi.advanceTimersByTime(1500)
    s.sockets.last().open()
    expect(s.sockets.last().sentMessages()[0]).toEqual({ type: 'subscribe_games', gameIds: ['g1'] })
    expect(onReconnected).toHaveBeenCalledTimes(1)
    expect(conn.connects).toBe(2)
    expect(info).toHaveBeenCalledWith('[live] reconnected (1)')
  })

  it('retries at once when the browser comes online', () => {
    const s = setup()
    conn = s.conn
    conn.start()
    s.sockets.last().serverClose(1006)
    expect(s.sockets.all.length).toBe(1)
    window.dispatchEvent(new Event('online'))
    expect(s.sockets.all.length).toBe(2)
  })

  it('retries at once when the tab becomes visible while waiting', () => {
    const s = setup()
    conn = s.conn
    conn.start()
    s.sockets.last().serverClose(1006)
    setVisibility('visible')
    expect(s.sockets.all.length).toBe(2)
  })

  it('drops garbage and unknown messages', () => {
    const events = {
      onHello: vi.fn(),
      onNotification: vi.fn(),
      onUnreadCount: vi.fn(),
      onGameUpdate: vi.fn(),
    }
    const s = setup(events)
    conn = s.conn
    conn.start()
    s.sockets.last().open()
    s.sockets.last().receive('not json')
    s.sockets.last().receive({ type: 'mystery' })
    s.sockets.last().receive({ type: 'game_update' })
    s.sockets.last().receive({ type: 'unread_count', count: 'four' })
    for (const fn of Object.values(events)) expect(fn).not.toHaveBeenCalled()
    expect(conn.state).toBe('open')
  })

  it('dispatches messages in whatever order they come (#24)', () => {
    const order: string[] = []
    const s = setup({
      onNotification: () => order.push('notification'),
      onUnreadCount: (n) => order.push(`unread ${n}`),
      onHello: (n) => order.push(`hello ${n}`),
      onGameUpdate: (g) => order.push(`game ${g.version}`),
    })
    conn = s.conn
    conn.start()
    s.sockets.last().open()
    s.sockets.last().receive({ type: 'notification', notification })
    s.sockets.last().receive({ type: 'unread_count', count: 4 })
    s.sockets.last().receive({ type: 'hello', userId: 'u', serverTime: 'x', unreadCount: 3 })
    s.sockets.last().receive({ type: 'game_update', game: gameSummary })
    expect(order).toEqual(['notification', 'unread 4', 'hello 3', 'game 57'])
  })

  it('gives up on a socket stuck connecting after 10 s and backs off', () => {
    const s = setup()
    conn = s.conn
    conn.start()
    vi.advanceTimersByTime(9999)
    expect(s.sockets.last().closedWith).toBeUndefined()
    vi.advanceTimersByTime(1)
    expect(s.sockets.last().closedWith).toBeDefined()
    expect(conn.state).toBe('waiting')
    vi.advanceTimersByTime(1500)
    expect(s.sockets.all.length).toBe(2)
  })

  it('does not time out a socket that opened', () => {
    const s = setup()
    conn = s.conn
    conn.start()
    s.sockets.last().open()
    vi.advanceTimersByTime(20000)
    expect(conn.state).toBe('open')
    expect(s.sockets.all.length).toBe(1)
  })

  it('stays stopped after stop()', () => {
    const s = setup()
    conn = s.conn
    conn.start()
    s.sockets.last().open()
    conn.stop()
    expect(s.sockets.last().closedWith).toBe(1000)
    expect(conn.state).toBe('stopped')
    vi.advanceTimersByTime(60000)
    window.dispatchEvent(new Event('online'))
    expect(s.sockets.all.length).toBe(1)
  })
})
