import { useEffect, useSyncExternalStore } from 'react'
import type { Game } from '../api/types'

/** What the screen shows for one game, and the last score change (for the live look). */
export interface LiveGame {
  game: Game
  changedAt: number
  delta: { home: number; away: number } | null
}

/**
 * Every copy of a game, from HTTP or the socket, goes through accept(): the highest version wins.
 * A slow list refetch then can't undo a newer game_update, and a schedule copy (version 0) gives
 * way to the first live one (docs/api/websocket.md, "Ignore stale updates").
 */
export class GameStore {
  private games = new Map<string, LiveGame>()
  private listeners = new Set<() => void>()
  private tick = 0

  accept(game: Game, now: number = Date.now()): boolean {
    const held = this.games.get(game.id)
    if (held && game.version <= held.game.version) return false
    const delta = held ? scoreDelta(held.game, game) : null
    this.games.set(game.id, { game, changedAt: now, delta })
    this.tick++
    for (const fn of this.listeners) fn()
    return true
  }

  get(id: string): LiveGame | undefined {
    return this.games.get(id)
  }

  /** Changes on every accepted copy; lets a hook watch many games with one subscription. */
  version(): number {
    return this.tick
  }

  subscribe(fn: () => void): () => void {
    this.listeners.add(fn)
    return () => this.listeners.delete(fn)
  }
}

function scoreDelta(before: Game, after: Game): LiveGame['delta'] {
  const home = after.home.score - before.home.score
  const away = after.away.score - before.away.score
  return home === 0 && away === 0 ? null : { home, away }
}

export const gameStore = new GameStore()

export function useLiveGame(id: string): LiveGame | undefined {
  return useSyncExternalStore(
    (fn) => gameStore.subscribe(fn),
    () => gameStore.get(id),
  )
}

/** The HTTP list, each game replaced by the store's copy when that one is newer. */
export function useLiveGames(fromHttp: Game[] | undefined): Game[] {
  useSyncExternalStore(
    (fn) => gameStore.subscribe(fn),
    () => gameStore.version(),
  )
  useEffect(() => {
    for (const g of fromHttp ?? []) gameStore.accept(g)
  }, [fromHttp])
  return (fromHttp ?? []).map((g) => {
    const held = gameStore.get(g.id)?.game
    return held && held.version >= g.version ? held : g
  })
}
