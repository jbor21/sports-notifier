import { ChevronRight } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import type { Game } from '../api/types'
import { timeIn } from '../dates'
import type { LiveGame } from '../live/games'
import { FollowGameButton } from './FollowGameButton'
import { TeamMark } from './TeamMark'

/** How long a score change keeps the card's ring and its "+N" chip (spec 7). */
export const FLASH_MS = 4000

const NOT_LIVE: readonly string[] = [
  'scheduled',
  'final',
  'delayed',
  'postponed',
  'canceled',
  'suspended',
]

/** In progress, or a status this UI doesn't know yet (openapi.yaml: treat it like in_progress). */
export function isLive(status: string): boolean {
  return !NOT_LIVE.includes(status)
}

/** Games without a score worth showing yet. */
export function hasNoScore(status: string): boolean {
  return status === 'scheduled' || status === 'postponed' || status === 'canceled'
}

/** `Q4 · 4:12`, `Half`, `Bot 7th`: exactly what the last update said; nothing ticks here. */
export function periodText(game: Pick<Game, 'periodLabel' | 'clock'>): string {
  return [game.periodLabel, game.clock].filter(Boolean).join(' · ')
}

type Delta = NonNullable<LiveGame['delta']>

/**
 * The last score change that put points up, while under 4 s old. Kept here rather than read
 * straight from the store, because a clock-only update a second later replaces the store's delta.
 */
export function useScoreFlash(live: LiveGame | undefined): Delta | null {
  const [flash, setFlash] = useState<{ at: number; delta: Delta } | null>(null)
  const at = live?.changedAt
  const delta = live?.delta
  useEffect(() => {
    if (at === undefined || !delta || (delta.home <= 0 && delta.away <= 0)) return
    if (Date.now() - at >= FLASH_MS) return
    setFlash({ at, delta })
  }, [at, delta])
  useEffect(() => {
    if (!flash) return
    const timer = setTimeout(() => setFlash(null), flash.at + FLASH_MS - Date.now())
    return () => clearTimeout(timer)
  }, [flash])
  return flash?.delta ?? null
}

export function GameCard({
  game,
  live,
  timezone,
}: {
  game: Game
  live: LiveGame | undefined
  timezone: string
}) {
  const navigate = useNavigate()
  const flash = useScoreFlash(live)
  const liveNow = isLive(game.status)
  const open = () => navigate(`/games/${encodeURIComponent(game.id)}`)
  const classes = ['game-card', liveNow && 'live-game', flash && 'changed'].filter(Boolean)

  return (
    <article className={classes.join(' ')} data-game-id={game.id}>
      <div className="card-top">
        <StatusLabel game={game} timezone={timezone} />
        <FollowGameButton gameId={game.id} small />
      </div>
      <button
        className="score-link"
        data-version={game.version}
        onClick={open}
        aria-label={`View ${game.away.team.displayName} at ${game.home.team.displayName}`}
      >
        {(['away', 'home'] as const).map((side) => {
          const { team, score, record } = game[side]
          const change = flash?.[side] ?? 0
          return (
            <div className="team-row" key={side}>
              <TeamMark team={team} />
              <div className="team-info">
                <strong>{team.displayName}</strong>
                <small>{record || team.abbreviation}</small>
              </div>
              {change > 0 && <span className="delta">{`+${change}`}</span>}
              {/* Keyed by the score, so a new score mounts fresh and its slide-in plays. */}
              <span key={score} className={change !== 0 ? 'score score-animate' : 'score'}>
                {hasNoScore(game.status) ? '—' : score}
              </span>
            </div>
          )
        })}
      </button>
      <div className="card-bottom">
        <span>
          {game.leagueId} ·{' '}
          {liveNow ? 'Live coverage' : game.status === 'scheduled' ? 'Upcoming' : 'Game center'}
        </span>
        <button onClick={open}>
          Game details <ChevronRight size={13} />
        </button>
      </div>
    </article>
  )
}

function StatusLabel({ game, timezone }: { game: Game; timezone: string }) {
  if (isLive(game.status))
    return (
      <span className="live-label">
        <span className="live-dot" /> LIVE <span className="clock">{periodText(game)}</span>
      </span>
    )
  if (game.status === 'scheduled')
    return <span className="game-state">{timeIn(game.startTime, timezone)}</span>
  if (game.status === 'final')
    return <span className="game-state">{(game.periodLabel ?? 'Final').toUpperCase()}</span>
  return <span className="game-state">{game.status.toUpperCase()}</span>
}
