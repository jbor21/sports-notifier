import { describe, expect, it } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { GameStore, gameStore, useLiveGames } from './games'
import type { Game } from '../api/types'
import { gameSummary } from '../test/fixtures'

const at = (version: number, changes: Partial<Game> = {}): Game => ({
  ...structuredClone(gameSummary),
  version,
  ...changes,
})

describe('GameStore', () => {
  it('keeps a higher version', () => {
    const s = new GameStore()
    expect(s.accept(at(57))).toBe(true)
    expect(s.accept(at(58))).toBe(true)
    expect(s.get(gameSummary.id)!.game.version).toBe(58)
  })

  it('drops equal and lower versions', () => {
    const s = new GameStore()
    s.accept(at(57, { clock: '4:12' }))
    expect(s.accept(at(57, { clock: '0:01' }))).toBe(false)
    expect(s.accept(at(56))).toBe(false)
    expect(s.get(gameSummary.id)!.game.clock).toBe('4:12')
  })

  it('replaces a schedule copy (version 0) with the first live update', () => {
    const s = new GameStore()
    s.accept(at(0, { status: 'scheduled' }))
    expect(s.accept(at(1))).toBe(true)
    expect(s.get(gameSummary.id)!.game.status).toBe('in_progress')
  })

  it('records the score change and when it happened', () => {
    const s = new GameStore()
    const away = gameSummary.away
    s.accept(at(57, { away: { ...away, score: 13 } }), 1000)
    s.accept(at(58, { away: { ...away, score: 20 } }), 2000)
    expect(s.get(gameSummary.id)).toMatchObject({ changedAt: 2000, delta: { home: 0, away: 7 } })
  })

  it('records no change when only the clock moved', () => {
    const s = new GameStore()
    s.accept(at(57))
    s.accept(at(58, { clock: '3:58' }))
    expect(s.get(gameSummary.id)!.delta).toBeNull()
  })

  it('keeps a status it does not know', () => {
    const s = new GameStore()
    s.accept(at(57, { status: 'weather_hold' as Game['status'] }))
    expect(s.get(gameSummary.id)!.game.status).toBe('weather_hold')
  })

  it('tells listeners about accepted copies only', () => {
    const s = new GameStore()
    let calls = 0
    s.subscribe(() => calls++)
    s.accept(at(57))
    s.accept(at(57))
    expect(calls).toBe(1)
  })
})

describe('useLiveGames', () => {
  it('cannot let an older HTTP copy undo a newer socket update', () => {
    gameStore.accept(at(60, { id: 'nfl-http-vs-socket' }))
    const http = [at(58, { id: 'nfl-http-vs-socket' })]
    const { result } = renderHook(() => useLiveGames(http))
    expect(result.current[0].version).toBe(60)
  })

  it('shows socket updates as they arrive', () => {
    const http = [at(5, { id: 'nfl-live-hook' })]
    const { result } = renderHook(() => useLiveGames(http))
    expect(result.current[0].version).toBe(5)
    act(() => {
      gameStore.accept(at(6, { id: 'nfl-live-hook' }))
    })
    expect(result.current[0].version).toBe(6)
  })
})
