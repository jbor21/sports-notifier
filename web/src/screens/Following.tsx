import { ChevronRight, Plus } from 'lucide-react'
import { Link } from 'react-router'
import useSWR from 'swr'
import type { ApiError } from '../api/client'
import type { Follow } from '../api/types'
import { Empty } from '../components/Empty'
import { LoadState } from '../components/LoadState'
import { useAddFollow } from '../components/Shell'

function AddButton() {
  const addFollow = useAddFollow()
  return (
    <button className="primary" onClick={addFollow}>
      <Plus size={16} /> Add a follow
    </button>
  )
}

function alertsOn(f: Follow): string {
  const n = Object.values(f.alerts).filter((a) => a.enabled).length
  return n === 1 ? '1 alert on' : `${n} alerts on`
}

export function Following() {
  const follows = useSWR<{ items: Follow[] }, ApiError>('/follows')
  const items = follows.data?.items ?? []

  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">YOUR TEAMS. YOUR TERMS.</div>
          <h1>
            Your sidelines<span>.</span>
          </h1>
          <p>Keep your favorites close, and your alerts just right.</p>
        </div>
        <AddButton />
      </div>
      {!follows.data && (
        <LoadState
          error={follows.error}
          loading={follows.isLoading}
          retry={() => void follows.mutate()}
        />
      )}
      <div className="follows-grid">
        {items.map((f) => (
          <Link
            key={f.id}
            className="panel follow-panel"
            to={`/following/${encodeURIComponent(f.id)}`}
          >
            <span className={`league-icon ${f.leagueId.toLowerCase()}`}>{f.leagueId}</span>
            <div>
              <h2>{f.target.displayName}</h2>
              <p>
                {f.target.kind} follow · {alertsOn(f)}
              </p>
            </div>
            <ChevronRight size={20} />
          </Link>
        ))}
      </div>
      {follows.data && items.length === 0 && (
        <Empty title="Find your people">Add a team, league, or game to get started.</Empty>
      )}
    </>
  )
}
