import {
  ArrowUpRight,
  Bell,
  Check,
  ChevronRight,
  Plus,
  Radio,
  SlidersHorizontal,
  Star,
} from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import useSWR from 'swr'
import type { ApiError } from '../api/client'
import type { Follow, Game, League, Notice } from '../api/types'
import { DateStrip } from '../components/DateStrip'
import { Empty } from '../components/Empty'
import { GameCard, isLive } from '../components/GameCard'
import { LoadState } from '../components/LoadState'
import { NoticeItem } from '../components/NoticeItem'
import { useAddFollow } from '../components/Shell'
import { TeamMark } from '../components/TeamMark'
import { Toggle } from '../components/Toggle'
import { dateStrip, useToday } from '../dates'
import { gameStore, useLiveGames } from '../live/games'
import { isKnown } from '../live/knownTypes'
import { useKnownTypes, useSubscribeGames } from '../live/LiveProvider'
import { mergeNotices, pushed } from '../live/pushed'
import { useStore } from '../live/store'
import { useMe } from '../session'

/** The socket takes at most 100 games (websocket.md). */
const MAX_SUBSCRIBED = 100

/** Games not in the schedule (the simulator's) only show up on a refetch. */
const REFRESH_MS = 60_000

/** Leagues change with a deploy, not during a visit. */
const ONCE = { revalidateOnFocus: false, revalidateIfStale: false }

function gamesPath(q: { date: string; tz: string; league: string; following: boolean }): string {
  const params = new URLSearchParams({ date: q.date, tz: q.tz })
  if (q.league) params.set('league', q.league)
  if (q.following) params.set('following', 'true')
  return `/games?${params}`
}

const startOf = (g: Game) => Date.parse(g.startTime)

/** Live first, then soonest: the order the socket subscription keeps when it has to cut. */
function liveThenSoonest(a: Game, b: Game): number {
  const live = Number(isLive(b.status)) - Number(isLive(a.status))
  return live || startOf(a) - startOf(b)
}

export function GameDay() {
  const me = useMe()
  const timezone = me.data?.timezone ?? 'UTC'
  const today = useToday(timezone)
  const days = dateStrip(timezone)
  // null follows today, so a tab left open past midnight moves on with it.
  const [picked, setPicked] = useState<string | null>(null)
  const date = picked && days.includes(picked) ? picked : today
  const [league, setLeague] = useState('')
  const [following, setFollowing] = useState(false)
  const [liveOnly, setLiveOnly] = useState(false)
  const addFollow = useAddFollow()

  const leagues = useSWR<{ items: League[] }>('/leagues', ONCE)
  const list = useSWR<{ date: string; items: Game[] }, ApiError>(
    gamesPath({ date, tz: timezone, league, following }),
    { refreshInterval: REFRESH_MS },
  )
  const all = useLiveGames(list.data?.items)
  const shown = liveOnly ? all.filter((g) => isLive(g.status)) : all

  useSubscribeGames(
    [...shown]
      .sort(liveThenSoonest)
      .slice(0, MAX_SUBSCRIBED)
      .map((g) => g.id),
  )

  // League order from /leagues; a league it doesn't list yet goes last.
  const leagueIds = [
    ...new Set([...(leagues.data?.items ?? []).map((l) => l.id), ...shown.map((g) => g.leagueId)]),
  ].filter((id) => shown.some((g) => g.leagueId === id))
  const leagueName = (id: string) => leagues.data?.items.find((l) => l.id === id)?.name ?? ''

  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">YOUR FRONT ROW. EVERY GAME.</div>
          <h1>
            Game day<span aria-hidden="true">.</span>
          </h1>
          <p>The scores you need. The moments you don&apos;t want to miss.</p>
        </div>
        <button className="primary" onClick={addFollow}>
          <Plus size={16} /> Add a follow
        </button>
      </div>

      <div className="dashboard-columns">
        <div className="games-main">
          <HighlightBanner onAdd={addFollow} />

          <div className="league-tabs" role="tablist" aria-label="Filter by league">
            {[
              { id: '', label: 'All sports' },
              ...(leagues.data?.items ?? []).map((l) => ({ id: l.id, label: l.id })),
            ].map((tab) => (
              <button
                key={tab.id}
                role="tab"
                aria-selected={league === tab.id}
                className={league === tab.id ? 'active' : ''}
                onClick={() => setLeague(tab.id)}
              >
                {tab.label}
                {tab.id === '' && <span className="count">{list.data?.items.length ?? 0}</span>}
              </button>
            ))}
          </div>

          <div className="game-toolbar">
            <DateStrip
              days={days}
              today={today}
              value={date}
              onChange={(day) => setPicked(day === today ? null : day)}
            />
            <div className="toolbar-right">
              <label className="following-toggle">
                <Star size={14} /> Following
                <Toggle label="Only following" checked={following} onChange={setFollowing} />
              </label>
              <button
                aria-label="Show only live games"
                aria-pressed={liveOnly}
                className={
                  liveOnly ? 'icon-button filter-button active' : 'icon-button filter-button'
                }
                onClick={() => setLiveOnly(!liveOnly)}
              >
                <SlidersHorizontal size={17} />
              </button>
            </div>
          </div>

          <LoadState error={list.error} loading={list.isLoading} retry={() => void list.mutate()} />
          {!list.isLoading && !list.error && shown.length === 0 && (
            <Empty title="No games on the board">
              Try another date or league, or add a follow.
            </Empty>
          )}

          {leagueIds.map((id) => {
            const games = shown
              .filter((g) => g.leagueId === id)
              .sort((a, b) => startOf(a) - startOf(b))
            return (
              <section className="league-section" key={id}>
                <div className="section-heading">
                  <div>
                    <span className={`league-icon ${id.toLowerCase()}`}>{id}</span>
                    <h2>{id}</h2>
                    <span className="league-fullname">{leagueName(id)}</span>
                  </div>
                  <span>
                    {games.length} {games.length === 1 ? 'game' : 'games'}
                  </span>
                </div>
                <div className="game-grid">
                  {games.map((g) => (
                    <GameCard key={g.id} game={g} live={gameStore.get(g.id)} timezone={timezone} />
                  ))}
                </div>
              </section>
            )
          })}

          <p className="footnote">
            <span className="live-dot" /> Scores update automatically{' '}
            <span>All times in {timezone.replaceAll('_', ' ')}</span>
          </p>
        </div>
        <DashboardAside onAdd={addFollow} timezone={timezone} />
      </div>
    </>
  )
}

/** v0's banner; the score in it is decoration, hidden from screen readers. */
function HighlightBanner({ onAdd }: { onAdd: () => void }) {
  return (
    <section className="highlight-banner">
      <div className="highlight-copy">
        <span className="highlight-kicker">
          <span className="live-dot" /> THE GAME IS ALWAYS ON
        </span>
        <h2>
          Less refreshing.
          <br />
          More celebrating.
        </h2>
        <p>Your teams, your alerts. We&apos;ll take it from here.</p>
        <button onClick={onAdd}>
          Make it your game <ArrowUpRight size={17} />
        </button>
      </div>
      <div className="banner-score" aria-hidden="true">
        <div className="mini-score-top">
          <Radio size={13} /> RIGHT NOW <span>Q4 · 4:12</span>
        </div>
        <div>
          <span className="mini-team">LV</span>
          <b>20</b>
          <span className="mini-versus">:</span>
          <b>17</b>
          <span className="mini-team bears">CHI</span>
        </div>
        <span className="mini-score-bottom">
          <Check size={12} /> You won&apos;t miss a moment
        </span>
      </div>
      <div className="field-lines" />
    </section>
  )
}

function followMark(f: Follow) {
  const initials = f.target.displayName
    .split(' ')
    .map((word) => word[0])
    .join('')
    .slice(0, 3)
  return {
    id: f.target.id,
    abbreviation: f.target.kind === 'league' ? f.leagueId : initials,
    displayName: f.target.displayName,
    logoUrl: f.target.logoUrl,
  }
}

function DashboardAside({ onAdd, timezone }: { onAdd: () => void; timezone: string }) {
  const navigate = useNavigate()
  const follows = useSWR<{ items: Follow[] }>('/follows')
  const history = useSWR<{ items: Notice[] }>('/notifications?limit=3')
  const pushedList = useStore(pushed)
  const known = useKnownTypes()
  const latest = mergeNotices(pushedList, history.data?.items ?? [], {})
    .filter((n) => isKnown(n, known))
    .slice(0, 3)
  const items = follows.data?.items ?? []

  return (
    <aside className="dashboard-aside">
      <section className="aside-card">
        <div className="aside-title">
          <h2>
            <Star size={17} /> Your sidelines
          </h2>
          <span>{items.length}</span>
        </div>
        <p className="aside-subtitle">A little closer to your favorites.</p>
        {items.slice(0, 4).map((f) => (
          <Link key={f.id} className="follow-row" to={`/following/${encodeURIComponent(f.id)}`}>
            <TeamMark team={followMark(f)} />
            <span>
              <strong>{f.target.displayName}</strong>
              <small>
                {f.leagueId} · {kindLabel(f.target.kind)} alerts
              </small>
            </span>
            <ChevronRight size={15} />
          </Link>
        ))}
        <button className="aside-add" onClick={onAdd}>
          + Discover teams &amp; leagues
        </button>
      </section>
      <section className="aside-card alerts-card">
        <div className="aside-title">
          <h2>
            <Bell size={17} /> Latest alerts
          </h2>
          <span className="live-label">
            <span className="live-dot" /> LIVE
          </span>
        </div>
        {latest.map((n) => (
          <NoticeItem key={n.id} notice={n} timezone={timezone} known={known} compact />
        ))}
        {latest.length === 0 && (
          <p className="aside-subtitle">Your next big moment will appear here.</p>
        )}
        <Link className="aside-add" to="/notifications">
          View all notifications <ChevronRight size={14} />
        </Link>
      </section>
      <div className="quiet-card">
        <span className="quiet-icon">
          <Bell size={21} />
        </span>
        <h3>
          All the action.
          <br />
          None of the noise.
        </h3>
        <p>
          Choose the moments that matter.
          <br />
          We&apos;ll handle the rest.
        </p>
        <button onClick={() => navigate('/following')}>
          Fine-tune your alerts <ArrowUpRight size={14} />
        </button>
      </div>
      <div className="aside-footer">
        BUILT FOR THE LOVE OF THE GAME.
        <br />
        <span>Sideline © 2026</span>
      </div>
    </aside>
  )
}

function kindLabel(kind: Follow['target']['kind']): string {
  return kind === 'league' ? 'League' : kind === 'team' ? 'Team' : 'Game'
}
