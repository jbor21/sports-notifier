import { useEffect, useRef, useState } from 'react'
import type { ConnectionInfo } from '../live/LiveProvider'

/**
 * A 4000 reconnects within about a second and the first backoffs finish within about 5 s, so
 * routine blips never show the banner; 10 s behind is when scores may really be stale.
 */
export const PAUSED_AFTER_MS = 10_000

/**
 * True once the socket has been down for 10 s: waiting, or connecting again after a wait or
 * after having been open. The very first connect never counts.
 */
export function usePaused({ state, connects }: Pick<ConnectionInfo, 'state' | 'connects'>) {
  const downSince = useRef<number | null>(null)
  const [paused, setPaused] = useState(false)

  useEffect(() => {
    if (state !== 'waiting' && state !== 'connecting') {
      downSince.current = null
      setPaused(false)
      return
    }
    if (downSince.current === null) {
      if (state === 'connecting' && connects === 0) return
      downSince.current = Date.now()
    }
    const left = downSince.current + PAUSED_AFTER_MS - Date.now()
    if (left <= 0) {
      setPaused(true)
      return
    }
    const timer = setTimeout(() => setPaused(true), left)
    return () => clearTimeout(timer)
  }, [state, connects])

  return paused
}

export function ReconnectBanner(conn: ConnectionInfo) {
  const paused = usePaused(conn)
  const [, setTick] = useState(0)

  // Redraw the countdown every second while it shows.
  useEffect(() => {
    if (!paused) return
    const timer = setInterval(() => setTick((n) => n + 1), 1000)
    return () => clearInterval(timer)
  }, [paused])

  if (!paused) return null
  const waiting = conn.state === 'waiting' && conn.retryAt !== null
  const seconds = waiting ? Math.max(0, Math.ceil((conn.retryAt! - Date.now()) / 1000)) : 0
  return (
    <div className="reconnect-banner" role="status">
      <span className="live-dot offline" />
      <span>
        {waiting
          ? `Live updates paused. Reconnecting in ${seconds} s; scores may be behind.`
          : 'Live updates paused. Reconnecting now; scores may be behind.'}
      </span>
      {waiting && <button onClick={() => conn.retryNow()}>Try now</button>}
    </div>
  )
}
