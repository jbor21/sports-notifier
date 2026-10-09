import { ChevronLeft } from 'lucide-react'
import { useEffect } from 'react'
import { Link, useParams } from 'react-router'
import useSWR from 'swr'
import type { ApiError } from '../api/client'
import type { Game, GameDetail, Notice } from '../api/types'
import { Empty } from '../components/Empty'
import { FollowGameButton } from '../components/FollowGameButton'
import { hasNoScore, isLive, periodText } from '../components/GameCard'
import { LoadState } from '../components/LoadState'
import { NoticeItem } from '../components/NoticeItem'
import { TeamMark } from '../components/TeamMark'
import { timeIn } from '../dates'
import { gameStore, useLiveGame } from '../live/games'
import { isKnown } from '../live/knownTypes'
import { useKnownTypes, useSubscribeGames } from '../live/LiveProvider'
import { mergeNotices, pushed } from '../live/pushed'
import { useStore } from '../live/store'
import { useMe } from '../session'

function BackLink() {
  return (
    <Link className="back-link" to="/">
      <ChevronLeft size={16} /> Back to games
    </Link>
  )
}

export function GamePage() {
  const { id = '' } = useParams()
  const me = useMe()
  const timezone = me.data?.timezone ?? 'UTC'
  const detail = useSWR<GameDetail, ApiError>(`/games/${encodeURIComponent(id)}`)
  const live = useLiveGame(id)
  useSubscribeGames([id])

  useEffect(() => {
    if (detail.data) gameStore.accept(detail.data)
  }, [detail.data])

  // game_update carries no line score: when the store moves to another period or status than
  // the detail shows, ask for the detail again. A clock tick alone doesn't change the line score.
  const period = live?.game.period
  const status = live?.game.status
  const { data: shown, mutate } = detail
  useEffect(() => {
    if (!shown || period === undefined || status === undefined) return
    if (period !== shown.period || status !== shown.status) void mutate()
    // Only a change in the store's period or status should refetch; `shown` arriving must not.
  }, [period, status])

  if (detail.error?.status === 404)
    return (
      <>
        <BackLink />
        <Empty title="Game not found">It may have been moved or removed from the schedule.</Empty>
      </>
    )
  if (!detail.data)
    return (
      <>
        <BackLink />
        <LoadState
          error={detail.error}
          loading={detail.isLoading}
          retry={() => void detail.mutate()}
        />
      </>
    )

  const data = detail.data
  // The live copy wins when it is at least as new; the line score always comes from the detail.
  const game = live && live.game.version >= data.version ? live.game : data
  const liveNow = isLive(game.status)

  return (
    <>
      <BackLink />
      <div className="page-heading">
        <div>
          <div className="eyebrow">{game.leagueId} · GAME CENTER</div>
          <h1>Every play. Every point.</h1>
        </div>
        <FollowGameButton gameId={game.id} />
      </div>

      <section className="detail-score panel" data-version={game.version}>
        <span className={liveNow ? 'live-label' : 'game-state'}>{statusLine(game, timezone)}</span>
        <div className="matchup">
          {(['away', 'home'] as const).map((side) => (
            <div key={side}>
              <TeamMark team={game[side].team} size="large" />
              <h2>{game[side].team.displayName}</h2>
              {/* Keyed by the score, so a new score mounts fresh and slides in. */}
              <span
                key={game[side].score}
                className={liveNow ? 'detail-number live-score' : 'detail-number'}
              >
                {hasNoScore(game.status) ? '—' : game[side].score}
              </span>
            </div>
          ))}
        </div>
        <p>{[data.venue, data.broadcast].filter(Boolean).join(' · ')}</p>
      </section>

      <section className="panel line-score">
        <h2>Line score</h2>
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Team</th>
                {data.lineScore.map((p) => (
                  <th key={p.period}>{p.label}</th>
                ))}
                <th>Total</th>
              </tr>
            </thead>
            <tbody>
              {(['away', 'home'] as const).map((side) => (
                <tr key={side}>
                  <th>{game[side].team.displayName}</th>
                  {data.lineScore.map((p) => (
                    <td key={p.period}>{p[side]}</td>
                  ))}
                  <td>
                    <strong>{game[side].score}</strong>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <small>Period breakdown refreshes from the API; total scores update live.</small>
      </section>

      <RecentAlerts gameId={id} timezone={timezone} />
    </>
  )
}

function statusLine(game: Game, timezone: string): string {
  const period = periodText(game)
  if (isLive(game.status)) return `● LIVE · ${period}`
  if (game.status === 'scheduled') return `STARTS ${timeIn(game.startTime, timezone)}`
  if (game.status === 'final') return (game.periodLabel ?? 'Final').toUpperCase()
  return [game.status.toUpperCase(), period].filter(Boolean).join(' · ')
}

/** This game's history, plus what was pushed since (the history writer can lag the push). */
function RecentAlerts({ gameId, timezone }: { gameId: string; timezone: string }) {
  const history = useSWR<{ items: Notice[] }>(`/notifications?game=${encodeURIComponent(gameId)}`)
  const pushedList = useStore(pushed)
  const known = useKnownTypes()
  const items = mergeNotices(pushedList, history.data?.items ?? [], { game: gameId }).filter((n) =>
    isKnown(n, known),
  )
  return (
    <section className="panel">
      <h2>Recent game alerts</h2>
      {items.map((n) => (
        <NoticeItem key={n.id} notice={n} timezone={timezone} known={known} />
      ))}
      {items.length === 0 && (
        <Empty title="No recent alerts">Follow this game to catch its next big moment.</Empty>
      )}
    </section>
  )
}
