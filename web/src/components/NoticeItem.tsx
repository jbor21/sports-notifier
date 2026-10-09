import { ArrowUpRight, Bell } from 'lucide-react'
import { useNavigate } from 'react-router'
import type { Notice } from '../api/types'
import { timeIn } from '../dates'
import { isKnown, knownExtras } from '../live/knownTypes'

/** One notification: what happened, when, and the alerts merged into it. Unknown types: nothing. */
export function NoticeItem({
  notice,
  timezone,
  known,
  compact = false,
}: {
  notice: Notice
  timezone: string
  known: Set<string> | null
  compact?: boolean
}) {
  const navigate = useNavigate()
  if (!isKnown(notice, known)) return null
  const { headline } = notice
  const touchdown = headline.type === 'TOUCHDOWN'
  return (
    <button
      className={compact ? 'notice-item compact' : 'notice-item'}
      onClick={() => {
        if (notice.game) navigate(`/games/${encodeURIComponent(notice.game.id)}`)
      }}
    >
      <span className={touchdown ? 'notice-symbol orange' : 'notice-symbol'}>
        {touchdown ? <ArrowUpRight size={17} /> : <Bell size={16} />}
      </span>
      <span className="notice-copy">
        <span className="notice-meta">
          {notice.leagueId}
          <span>·</span>
          {timeIn(notice.createdAt, timezone)}
        </span>
        <strong>{typeName(headline.type)}</strong>
        <span>{headline.text}</span>
        {!compact &&
          knownExtras(notice, known).map((extra, i) => (
            <small key={`${extra.type}-${i}`}>{extra.text}</small>
          ))}
      </span>
    </button>
  )
}

/** `FIELD_GOAL` → `field goal` (the CSS capitalizes it). */
function typeName(type: string): string {
  return type.replaceAll('_', ' ').toLowerCase()
}
