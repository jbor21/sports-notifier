import type { Notice } from '../api/types'
import { Store } from './store'

export interface NoticeFilter {
  league?: string
  team?: string
  game?: string
}

/**
 * Notifications pushed over the socket this session, newest first. History lists merge them in
 * rather than refetching, because the history writer can lag the push by a second or two.
 */
export const pushed = new Store<Notice[]>([])

export function addPushed(n: Notice): void {
  const list = pushed.get()
  if (list.some((p) => p.id === n.id)) return
  pushed.set([n, ...list])
}

export function clearPushed(): void {
  pushed.set([])
}

export function matchesFilter(n: Notice, f: NoticeFilter): boolean {
  if (f.league && n.leagueId !== f.league) return false
  if (f.team && n.game?.home.team.id !== f.team && n.game?.away.team.id !== f.team) return false
  if (f.game && n.game?.id !== f.game) return false
  return true
}

/** The pushed ones that match, plus the fetched pages: one copy per id (the fetched one), newest first. */
export function mergeNotices(pushedList: Notice[], fetched: Notice[], f: NoticeFilter): Notice[] {
  const byId = new Map<string, Notice>()
  for (const n of pushedList) if (matchesFilter(n, f)) byId.set(n.id, n)
  for (const n of fetched) byId.set(n.id, n)
  return [...byId.values()].sort((a, b) => b.createdAt.localeCompare(a.createdAt))
}
