import { useEffect, useState } from 'react'

// Every date and time is in the user's timezone from /me, never the browser's (spec 5.8).

/** The calendar day `now` falls on in `tz`, as `YYYY-MM-DD`. */
export function todayIn(tz: string, now: Date = new Date()): string {
  // en-CA writes dates as YYYY-MM-DD.
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: tz,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(now)
}

/** The days /games serves: yesterday through 7 days ahead, in `tz`. */
export function dateStrip(tz: string, now: Date = new Date()): string[] {
  const today = todayIn(tz, now)
  return Array.from({ length: 9 }, (_, i) => addDays(today, i - 1))
}

/** A clock time such as `1:00 PM`. */
export function timeIn(iso: string, tz: string): string {
  return (
    new Intl.DateTimeFormat('en-US', { timeZone: tz, hour: 'numeric', minute: '2-digit' })
      .format(new Date(iso))
      // Newer ICU puts a narrow no-break space before AM/PM.
      .replace(/\u202f/g, ' ')
  )
}

/** Today in `tz`, re-derived every minute so a tab left open past midnight moves on. */
export function useToday(tz: string): string {
  // The state only wakes the component when the day changes (React skips an equal value); the
  // value itself is derived on every render, so a timezone change shows at once.
  const [, setToday] = useState(() => todayIn(tz))
  useEffect(() => {
    const timer = setInterval(() => setToday(todayIn(tz)), 60_000)
    return () => clearInterval(timer)
  }, [tz])
  return todayIn(tz)
}

/** `YYYY-MM-DD` plus `days`, by calendar (noon UTC, so no daylight-saving edge can move it). */
export function addDays(date: string, days: number): string {
  const d = new Date(`${date}T12:00:00Z`)
  d.setUTCDate(d.getUTCDate() + days)
  return d.toISOString().slice(0, 10)
}

/** A `YYYY-MM-DD` day written out, e.g. `{ weekday: 'short' }` → `Thu`. */
export function formatDay(date: string, options: Intl.DateTimeFormatOptions): string {
  return new Intl.DateTimeFormat('en-US', { ...options, timeZone: 'UTC' }).format(
    new Date(`${date}T12:00:00Z`),
  )
}
