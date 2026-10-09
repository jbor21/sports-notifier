import { useState, type CSSProperties } from 'react'
import type { Game } from '../api/types'

export type TeamRef = Game['home']['team']

/** The team's logo on a tint of its colour; the abbreviation when there is no logo or it fails. */
export function TeamMark({ team, size = 'normal' }: { team: TeamRef; size?: 'normal' | 'large' }) {
  const [broken, setBroken] = useState(false)
  const style = { '--team': team.primaryColor || '#516879' } as CSSProperties
  return (
    <span className={`team-mark ${size}`} style={style}>
      {team.logoUrl && !broken ? (
        <img src={team.logoUrl} alt="" onError={() => setBroken(true)} />
      ) : (
        <span>{team.abbreviation}</span>
      )}
    </span>
  )
}
