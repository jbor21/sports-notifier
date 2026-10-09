import { beforeEach, describe, expect, it } from 'vitest'
import { addPushed, clearPushed, matchesFilter, mergeNotices, pushed } from './pushed'
import type { Notice } from '../api/types'
import { notification } from '../test/fixtures'

const notice = (id: string, createdAt: string, changes: Partial<Notice> = {}): Notice => ({
  ...structuredClone(notification),
  id,
  createdAt,
  ...changes,
})

beforeEach(() => clearPushed())

describe('pushed notifications', () => {
  it('keeps each id once, newest first', () => {
    addPushed(notice('a', '2026-10-04T20:00:00Z'))
    addPushed(notice('b', '2026-10-04T20:01:00Z'))
    addPushed(notice('a', '2026-10-04T20:00:00Z'))
    expect(pushed.get().map((n) => n.id)).toEqual(['b', 'a'])
  })

  it('filters by league, team, and game', () => {
    const n = notification
    expect(matchesFilter(n, {})).toBe(true)
    expect(matchesFilter(n, { league: 'NFL' })).toBe(true)
    expect(matchesFilter(n, { league: 'NBA' })).toBe(false)
    expect(matchesFilter(n, { team: 'nfl-chi' })).toBe(true)
    expect(matchesFilter(n, { team: 'nfl-lv' })).toBe(true)
    expect(matchesFilter(n, { team: 'nfl-kc' })).toBe(false)
    expect(matchesFilter(n, { game: 'nfl-401872948' })).toBe(true)
    expect(matchesFilter(n, { game: 'nfl-1' })).toBe(false)
  })

  it('merges with fetched pages: one copy per id, the fetched one, newest first', () => {
    const pushedX = notice('x', '2026-10-04T20:05:00Z', { headline: { type: 'GOAL', text: 'old' } })
    const fetchedX = notice('x', '2026-10-04T20:05:00Z')
    const y = notice('y', '2026-10-04T20:09:00Z')
    const z = notice('z', '2026-10-04T20:01:00Z', { leagueId: 'NBA' })
    const merged = mergeNotices([y, pushedX, z], [fetchedX], { league: 'NFL' })
    expect(merged.map((n) => n.id)).toEqual(['y', 'x'])
    expect(merged[1]).toBe(fetchedX)
  })

  it('empties on clear', () => {
    addPushed(notice('a', '2026-10-04T20:00:00Z'))
    clearPushed()
    expect(pushed.get()).toEqual([])
  })
})
