import type { Game, Notice } from '../api/types'

/** The live socket's rules from docs/api/websocket.md: pings, close codes, backoff, re-subscribe. */

export type ConnectionState = 'connecting' | 'open' | 'waiting' | 'signedOut' | 'stopped'

export interface SocketLike {
  readyState: number
  send(data: string): void
  close(code?: number, reason?: string): void
  onopen: (() => void) | null
  onmessage: ((e: { data: string }) => void) | null
  onclose: ((e: { code: number; reason: string }) => void) | null
  onerror: (() => void) | null
}

export interface ConnectionEvents {
  onState(state: ConnectionState, info: { retryAt: number | null; connects: number }): void
  onHello(unreadCount: number): void
  onNotification(n: Notice): void
  onUnreadCount(count: number): void
  onGameUpdate(g: Game): void
  onReconnected(): void
  onSignedOut(): void
}

const OPEN = 1
const PING_MS = 25_000
const RESET_AFTER_MS = 60_000
const MAX_BACKOFF_MS = 30_000
const MAX_GAMES = 100
/** A socket still not open after this is given up on (a half-open network after sleep, a stuck proxy). */
const CONNECT_TIMEOUT_MS = 10_000

/** 1, 2, 4, 8, 16, 30, 30… s, plus 0–1 s so a thousand tabs don't all come back in the same instant. */
export function backoffDelay(attempt: number, random: () => number): number {
  return Math.min(1000 * 2 ** attempt, MAX_BACKOFF_MS) + Math.floor(random() * 1000)
}

export class LiveConnection {
  private socket: SocketLike | null = null
  private games: string[] = []
  private sentGames: string | null = null
  private attempt = 0
  private retryAt: number | null = null
  private retryTimer: ReturnType<typeof setTimeout> | undefined
  private resetTimer: ReturnType<typeof setTimeout> | undefined
  private pingTimer: ReturnType<typeof setInterval> | undefined
  private connectTimer: ReturnType<typeof setTimeout> | undefined
  private readonly createSocket: (url: string) => SocketLike
  private readonly random: () => number
  private _state: ConnectionState = 'stopped'
  private _connects = 0

  constructor(
    private readonly url: string,
    private readonly events: Partial<ConnectionEvents>,
    deps: { createSocket?: (url: string) => SocketLike; random?: () => number } = {},
  ) {
    this.createSocket = deps.createSocket ?? ((u) => new WebSocket(u) as unknown as SocketLike)
    this.random = deps.random ?? Math.random
  }

  get state(): ConnectionState {
    return this._state
  }

  /** Opens so far; above 1 means the socket has reconnected. */
  get connects(): number {
    return this._connects
  }

  start(): void {
    window.addEventListener('online', this.onOnline)
    document.addEventListener('visibilitychange', this.onVisibility)
    this.connect()
  }

  stop(): void {
    window.removeEventListener('online', this.onOnline)
    document.removeEventListener('visibilitychange', this.onVisibility)
    this.clearTimers()
    clearTimeout(this.retryTimer)
    const socket = this.socket
    this.socket = null
    if (socket) {
      socket.onopen = socket.onmessage = socket.onclose = socket.onerror = null
      socket.close(1000)
    }
    this.setState('stopped')
  }

  /** "These are the games I am showing." Replaces the previous list; sent only when it changed. */
  setGames(ids: string[]): void {
    this.games = ids.slice(0, MAX_GAMES)
    if (this._state === 'open') this.sendGames(false)
  }

  retryNow(): void {
    if (this._state !== 'waiting') return
    clearTimeout(this.retryTimer)
    this.connect()
  }

  private connect(): void {
    this.retryAt = null
    this.setState('connecting')
    const socket = this.createSocket(this.url)
    this.socket = socket
    socket.onopen = () => this.opened()
    socket.onmessage = (e) => this.received(e.data)
    socket.onclose = (e) => this.closed(e.code)
    // The browser always follows an error with a close, which does the retrying.
    socket.onerror = null
    // Without this, a socket stuck in CONNECTING would never close, and nothing would retry.
    this.connectTimer = setTimeout(() => {
      socket.onopen = socket.onmessage = socket.onclose = null
      socket.close()
      this.closed(1006)
    }, CONNECT_TIMEOUT_MS)
  }

  private opened(): void {
    clearTimeout(this.connectTimer)
    this._connects++
    this.setState('open')
    this.sendGames(true)
    this.pingTimer = setInterval(() => this.send({ type: 'ping' }), PING_MS)
    // Only a connection that stays up resets the backoff; one that drops right away keeps growing it.
    this.resetTimer = setTimeout(() => (this.attempt = 0), RESET_AFTER_MS)
    if (this._connects > 1) {
      console.info(`[live] reconnected (${this._connects - 1})`)
      this.events.onReconnected?.()
    }
  }

  private closed(code: number): void {
    this.clearTimers()
    this.socket = null
    this.sentGames = null
    if (code === 4401) {
      this.setState('signedOut')
      this.events.onSignedOut?.()
      return
    }
    // 4000: this gateway is shutting down, another is ready now. Only the jitter, to spread the herd.
    const delay =
      code === 4000 ? Math.floor(this.random() * 1000) : backoffDelay(this.attempt++, this.random)
    this.retryAt = Date.now() + delay
    this.setState('waiting')
    this.retryTimer = setTimeout(() => this.connect(), delay)
  }

  private received(data: string): void {
    let msg: Record<string, unknown>
    try {
      msg = JSON.parse(data)
    } catch {
      return
    }
    if (!msg || typeof msg !== 'object') return
    switch (msg.type) {
      case 'hello':
        if (typeof msg.unreadCount === 'number') this.events.onHello?.(msg.unreadCount)
        break
      case 'unread_count':
        if (typeof msg.count === 'number') this.events.onUnreadCount?.(msg.count)
        break
      case 'notification':
        if (msg.notification && typeof msg.notification === 'object')
          this.events.onNotification?.(msg.notification as Notice)
        break
      case 'game_update': {
        const game = msg.game as Game | undefined
        if (game && typeof game === 'object' && typeof game.version === 'number')
          this.events.onGameUpdate?.(game)
        break
      }
      default:
        // pong, and any type this UI doesn't know yet.
        break
    }
  }

  private sendGames(force: boolean): void {
    const key = this.games.join(',')
    if (!force && key === this.sentGames) return
    this.sentGames = key
    this.send({ type: 'subscribe_games', gameIds: this.games })
  }

  private send(msg: object): void {
    if (this.socket?.readyState === OPEN) this.socket.send(JSON.stringify(msg))
  }

  private setState(state: ConnectionState): void {
    this._state = state
    this.events.onState?.(state, { retryAt: this.retryAt, connects: this._connects })
  }

  private clearTimers(): void {
    clearTimeout(this.connectTimer)
    clearInterval(this.pingTimer)
    clearTimeout(this.resetTimer)
  }

  private readonly onOnline = () => this.retryNow()

  private readonly onVisibility = () => {
    if (document.visibilityState !== 'visible') return
    if (this._state === 'waiting') this.retryNow()
    // Chrome runs timers in a tab hidden over 5 minutes about once a minute, which can stretch the
    // 25 s ping past the server's 60 s idle close; a ping on return lets the server hear from us now.
    else if (this._state === 'open') this.send({ type: 'ping' })
  }
}
