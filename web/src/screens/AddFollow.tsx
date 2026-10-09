import { Check, Search } from 'lucide-react'
import { useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import useSWR from 'swr'
import { request, type ApiError } from '../api/client'
import type { Follow, FollowKind, Game, League, Team } from '../api/types'
import { LoadState } from '../components/LoadState'
import { Modal } from '../components/Modal'
import { TeamMark, type TeamRef } from '../components/TeamMark'
import { todayIn } from '../dates'
import { useToasts } from '../live/LiveProvider'
import { useMe } from '../session'

const KINDS: Array<{ kind: FollowKind; label: string }> = [
  { kind: 'team', label: 'Teams' },
  { kind: 'league', label: 'Leagues' },
  { kind: 'game', label: 'Games' },
]

/** One thing to follow, whatever its kind. */
interface Choice {
  id: string
  name: string
  mark: TeamRef
}

const teamChoice = (t: Team): Choice => ({
  id: t.id,
  name: t.displayName,
  mark: {
    id: t.id,
    abbreviation: t.abbreviation,
    displayName: t.displayName,
    logoUrl: t.logoUrl,
    primaryColor: t.colors?.primary,
  },
})

const leagueChoice = (l: League): Choice => ({
  id: l.id,
  name: l.name,
  mark: { id: l.id, abbreviation: l.id, displayName: l.name, logoUrl: l.logoUrl },
})

const gameChoice = (g: Game): Choice => ({
  id: g.id,
  name: `${g.away.team.displayName} at ${g.home.team.displayName}`,
  mark: g.home.team,
})

/** Kind → league → a team, the league, or one of today's games → Follow → its switches. */
export function AddFollow({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate()
  const me = useMe()
  const { push } = useToasts()
  const [kind, setKind] = useState<FollowKind>('team')
  const [picked, setPicked] = useState('')
  const [query, setQuery] = useState('')
  const [pending, setPending] = useState<string | null>(null)
  const inFlight = useRef(false)

  const leagues = useSWR<{ items: League[] }, ApiError>('/leagues')
  const follows = useSWR<{ items: Follow[] }, ApiError>('/follows')
  const leagueId = picked || leagues.data?.items[0]?.id || ''
  const league = leagues.data?.items.find((l) => l.id === leagueId)
  const timezone = me.data?.timezone ?? 'UTC'

  const teams = useSWR<{ items: Team[] }, ApiError>(
    kind === 'team' && leagueId ? `/leagues/${encodeURIComponent(leagueId)}/teams` : null,
  )
  const gamesQuery = new URLSearchParams({
    league: leagueId,
    date: todayIn(timezone),
    tz: timezone,
  })
  const games = useSWR<{ items: Game[] }, ApiError>(
    kind === 'game' && leagueId ? `/games?${gamesQuery}` : null,
  )

  const source = kind === 'team' ? teams : kind === 'game' ? games : leagues
  const choices: Choice[] =
    kind === 'team'
      ? (teams.data?.items ?? []).map(teamChoice)
      : kind === 'game'
        ? (games.data?.items ?? []).map(gameChoice)
        : league
          ? [leagueChoice(league)]
          : []
  const q = query.trim().toLowerCase()
  const shown = choices.filter(
    (c) => c.name.toLowerCase().includes(q) || c.mark.abbreviation.toLowerCase().includes(q),
  )
  const followed = (id: string) =>
    follows.data?.items.some((f) => f.target.kind === kind && f.target.id === id) ?? false

  async function follow(choice: Choice) {
    // The ref stops a second click that lands before React re-renders the disabled button.
    if (inFlight.current) return
    inFlight.current = true
    setPending(choice.id)
    try {
      // 201 for a new follow, 200 for one that already exists: either way, open its switches.
      const created = await request<Follow>('/follows', {
        method: 'POST',
        body: { target: { kind, id: choice.id } },
      })
      await follows.mutate(
        (cur) => ({ items: [...(cur?.items ?? []).filter((f) => f.id !== created.id), created] }),
        { revalidate: false },
      )
      onClose()
      navigate(`/following/${encodeURIComponent(created.id)}`)
    } catch (err) {
      const text = err instanceof Error ? err.message : 'Could not follow that.'
      push({ id: `add-follow-${choice.id}`, kind: 'error', text })
    } finally {
      inFlight.current = false
      setPending(null)
    }
  }

  return (
    <Modal title="Find your sideline" onClose={onClose}>
      <p className="muted">Follow your favorites. Make every moment yours.</p>
      <div className="segmented" role="group" aria-label="What to follow">
        {KINDS.map(({ kind: k, label }) => (
          <button
            key={k}
            className={kind === k ? 'active' : ''}
            aria-pressed={kind === k}
            onClick={() => setKind(k)}
          >
            {label}
          </button>
        ))}
      </div>
      <label className="field-label">
        League
        <select value={leagueId} onChange={(e) => setPicked(e.target.value)}>
          {leagues.data?.items.map((l) => (
            <option key={l.id} value={l.id}>
              {l.id}
            </option>
          ))}
        </select>
      </label>
      <label className="search-input">
        <Search size={17} />
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search your favorites"
          aria-label="Search teams, leagues or games"
        />
      </label>
      <LoadState
        error={source.error}
        loading={source.isLoading}
        retry={() => void source.mutate()}
      />
      <div className="discover-list">
        {shown.map((c) => {
          const already = followed(c.id)
          return (
            <div className="discover-row" key={c.id}>
              <TeamMark team={c.mark} />
              <strong>{c.name}</strong>
              <button
                className={already ? 'follow-button is-following' : 'secondary'}
                aria-label={`${already ? 'Following' : 'Follow'} ${c.name}`}
                disabled={already || pending !== null}
                onClick={() => void follow(c)}
              >
                {already ? (
                  <>
                    <Check size={14} /> Following
                  </>
                ) : pending === c.id ? (
                  'Following…'
                ) : (
                  '+ Follow'
                )}
              </button>
            </div>
          )
        })}
        {kind === 'game' && games.data && choices.length === 0 && (
          <p className="muted">No {leagueId} games today.</p>
        )}
      </div>
    </Modal>
  )
}
