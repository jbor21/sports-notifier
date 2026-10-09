import { Bell, BellRing } from 'lucide-react'
import { useState } from 'react'
import useSWR from 'swr'
import { ApiError, request } from '../api/client'
import type { Follow } from '../api/types'
import { useToasts } from '../live/LiveProvider'

/** Follow or unfollow one game; the state comes from GET /follows (one request for every card). */
export function FollowGameButton({ gameId, small = false }: { gameId: string; small?: boolean }) {
  const follows = useSWR<{ items: Follow[] }, ApiError>('/follows')
  const { push } = useToasts()
  const [busy, setBusy] = useState(false)
  const existing = follows.data?.items.find(
    (f) => f.target.kind === 'game' && f.target.id === gameId,
  )
  // Until /follows answers, a click could follow a game already followed (harmless: 200) or
  // offer Follow for one that is; waiting avoids the wrong label.
  const unknown = !follows.data && !follows.error

  async function toggle() {
    setBusy(true)
    try {
      if (existing) {
        await request(`/follows/${encodeURIComponent(existing.id)}`, { method: 'DELETE' })
        await follows.mutate(
          (cur) => cur && { items: cur.items.filter((f) => f.id !== existing.id) },
          { revalidate: false },
        )
      } else {
        const created = await request<Follow>('/follows', {
          method: 'POST',
          body: { target: { kind: 'game', id: gameId } },
        })
        await follows.mutate(
          (cur) => ({
            items: [...(cur?.items ?? []).filter((f) => f.id !== created.id), created],
          }),
          { revalidate: false },
        )
      }
    } catch (err) {
      const text = err instanceof Error ? err.message : 'Could not change this follow.'
      push({ id: `follow-${gameId}`, kind: 'error', text })
    } finally {
      setBusy(false)
    }
  }

  return (
    <button
      className={existing ? 'follow-button is-following' : 'follow-button'}
      aria-label={existing ? 'Unfollow game' : 'Follow game'}
      disabled={busy || unknown}
      onClick={() => void toggle()}
    >
      {existing ? <BellRing size={15} /> : <Bell size={15} />}
      {!small && (existing ? 'Following' : 'Follow game')}
    </button>
  )
}
