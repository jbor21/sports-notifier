import { Trash2 } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import useSWR from 'swr'
import useSWRInfinite from 'swr/infinite'
import { request, type ApiError } from '../api/client'
import type { League, Notice, Team } from '../api/types'
import { Empty } from '../components/Empty'
import { LoadState } from '../components/LoadState'
import { Modal } from '../components/Modal'
import { NoticeItem } from '../components/NoticeItem'
import { markSeenLocally } from '../live/badge'
import { isKnown } from '../live/knownTypes'
import { useConnection, useKnownTypes, useToasts } from '../live/LiveProvider'
import { clearPushed, mergeNotices, pushed } from '../live/pushed'
import { useStore } from '../live/store'
import { useMe } from '../session'

interface Page {
  items: Notice[]
  nextCursor: string | null
}

const PAGE_SIZE = 20
/** While the page is open, pushes mark it seen at most this often. */
const SEEN_EVERY_MS = 5000

function markSeen(): void {
  void request('/notifications/seen', { method: 'POST' }).then(markSeenLocally, () => {})
}

/**
 * "Seen" once on open; then, while the page is open and visible, again after a push so the badge
 * doesn't count what the user is looking at. A push inside the 5 s window waits for its end.
 */
function useMarkSeen(): void {
  const opened = useRef(false)
  useEffect(() => {
    if (!opened.current) {
      opened.current = true
      markSeen()
    }

    let last = 0
    let timer: ReturnType<typeof setTimeout> | undefined
    let missed = false
    const send = () => {
      timer = undefined
      last = Date.now()
      markSeen()
    }
    const onPush = () => {
      if (document.visibilityState !== 'visible') {
        missed = true
        return
      }
      if (timer) return
      const wait = last + SEEN_EVERY_MS - Date.now()
      if (wait <= 0) send()
      else timer = setTimeout(send, wait)
    }
    let count = pushed.get().length
    const unsubscribe = pushed.subscribe(() => {
      const now = pushed.get().length
      if (now > count) onPush()
      count = now
    })
    // Pushes that came while the tab was hidden are seen once the user is back.
    const onVisible = () => {
      if (document.visibilityState === 'visible' && missed) {
        missed = false
        onPush()
      }
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      unsubscribe()
      clearTimeout(timer)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [])
}

export function Notifications() {
  const me = useMe()
  const timezone = me.data?.timezone ?? 'UTC'
  const known = useKnownTypes()
  const pushedList = useStore(pushed)
  const { push } = useToasts()
  const [league, setLeague] = useState('')
  const [team, setTeam] = useState('')
  const [asking, setAsking] = useState(false)
  const [clearing, setClearing] = useState(false)
  useMarkSeen()

  const leagues = useSWR<{ items: League[] }, ApiError>('/leagues')
  const teams = useSWR<{ items: Team[] }, ApiError>(
    league ? `/leagues/${encodeURIComponent(league)}/teams` : null,
  )

  const params = new URLSearchParams({ limit: String(PAGE_SIZE) })
  if (league) params.set('league', league)
  if (team) params.set('team', team)
  const base = `/notifications?${params}`
  const history = useSWRInfinite<Page, ApiError>((index, previous: Page | null) => {
    if (index === 0) return base
    if (!previous?.nextCursor) return null
    return `${base}&cursor=${encodeURIComponent(previous.nextCursor)}`
  })

  // The app's reconnect refetch skips paged lists, so this one asks again itself.
  const { connects } = useConnection()
  const firstConnects = useRef(connects)
  const { mutate } = history
  useEffect(() => {
    if (connects > Math.max(firstConnects.current, 1)) void mutate()
  }, [connects, mutate])

  const fetched = history.data?.flatMap((p) => p.items) ?? []
  const items = mergeNotices(pushedList, fetched, {
    league: league || undefined,
    team: team || undefined,
  }).filter((n) => isKnown(n, known))
  const more = history.data?.at(-1)?.nextCursor
  const loadingMore = history.size > (history.data?.length ?? 0)

  async function clearAll() {
    setClearing(true)
    try {
      await request('/notifications', { method: 'DELETE' })
      clearPushed()
      markSeenLocally()
      await history.mutate([{ items: [], nextCursor: null }], { revalidate: false })
      setAsking(false)
    } catch (err) {
      const text = err instanceof Error ? err.message : 'Could not clear your history.'
      push({ id: 'clear-history', kind: 'error', text })
    } finally {
      setClearing(false)
    }
  }

  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">NEVER MISS YOUR MOMENT</div>
          <h1>
            Notifications<span>.</span>
          </h1>
          <p>The big plays. The close calls. All in one place.</p>
        </div>
        <button className="secondary" onClick={() => setAsking(true)} disabled={!items.length}>
          <Trash2 size={16} /> Clear history
        </button>
      </div>
      <section className="panel notifications-panel">
        <div className="notification-filters">
          <label>
            League
            <select
              value={league}
              onChange={(e) => {
                setLeague(e.target.value)
                setTeam('')
              }}
            >
              <option value="">All leagues</option>
              {leagues.data?.items.map((l) => (
                <option key={l.id} value={l.id}>
                  {l.id}
                </option>
              ))}
            </select>
          </label>
          <label>
            Team
            <select value={team} disabled={!league} onChange={(e) => setTeam(e.target.value)}>
              <option value="">All teams</option>
              {teams.data?.items.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.displayName}
                </option>
              ))}
            </select>
          </label>
          <span>History is kept for 30 days</span>
        </div>
        {!history.data && (
          <LoadState
            error={history.error}
            loading={history.isLoading}
            retry={() => void history.mutate()}
          />
        )}
        {items.map((n) => (
          <NoticeItem key={n.id} notice={n} timezone={timezone} known={known} />
        ))}
        {history.data && items.length === 0 && (
          <Empty title="You're all caught up">
            Your next unforgettable moment will be right here.
          </Empty>
        )}
        {more && (
          <button
            className="secondary load-more"
            disabled={loadingMore}
            onClick={() => void history.setSize(history.size + 1)}
          >
            Load older alerts
          </button>
        )}
      </section>
      {asking && (
        <Modal title="Clear notification history?" onClose={() => setAsking(false)}>
          <p>
            This removes all your notifications, not just this filter. Your follows will stay the
            same.
          </p>
          <div className="modal-actions">
            <button className="secondary" onClick={() => setAsking(false)}>
              Cancel
            </button>
            <button
              className="primary danger-bg"
              disabled={clearing}
              onClick={() => void clearAll()}
            >
              Clear all history
            </button>
          </div>
        </Modal>
      )}
    </>
  )
}
