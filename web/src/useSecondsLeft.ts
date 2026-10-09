import { useEffect, useState } from 'react'

/** Whole seconds until `until` (epoch ms), re-rendering once a second while above 0. */
export function useSecondsLeft(until: number): number {
  const [, setTick] = useState(0)
  const left = Math.max(0, Math.ceil((until - Date.now()) / 1000))
  const counting = left > 0
  useEffect(() => {
    if (!counting) return
    const timer = setInterval(() => setTick((n) => n + 1), 1000)
    return () => clearInterval(timer)
  }, [until, counting])
  return left
}
