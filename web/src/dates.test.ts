import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { dateStrip, timeIn, todayIn, useToday } from './dates'

afterEach(() => {
  vi.useRealTimers()
})

describe('the user timezone, not the browser one', () => {
  // Review Focus 2: 02:00 UTC is still the evening before in Los Angeles.
  const now = new Date('2026-10-08T02:00:00Z')

  it('today is the user’s calendar day', () => {
    expect(todayIn('America/Los_Angeles', now)).toBe('2026-10-07')
    expect(todayIn('UTC', now)).toBe('2026-10-08')
  })

  it('the strip runs from yesterday to 7 days ahead', () => {
    const strip = dateStrip('America/Los_Angeles', now)
    expect(strip).toHaveLength(9)
    expect(strip[0]).toBe('2026-10-06')
    expect(strip[1]).toBe('2026-10-07')
    expect(strip[8]).toBe('2026-10-14')
  })

  it('crosses month and year ends', () => {
    const strip = dateStrip('UTC', new Date('2026-12-28T12:00:00Z'))
    expect(strip[0]).toBe('2026-12-27')
    expect(strip[5]).toBe('2027-01-01')
  })

  it('shows a time in the user’s timezone', () => {
    expect(timeIn('2026-10-04T17:00:00Z', 'America/New_York')).toBe('1:00 PM')
    expect(timeIn('2026-10-04T17:00:00Z', 'America/Los_Angeles')).toBe('10:00 AM')
  })
})

describe('useToday', () => {
  // Review Focus 3: a tab left open across midnight moves to the new day.
  it('moves to the next day on the minute tick after midnight', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-08T23:59:30Z'))
    const { result } = renderHook(() => useToday('UTC'))
    expect(result.current).toBe('2026-10-08')
    act(() => vi.advanceTimersByTime(60_000))
    expect(result.current).toBe('2026-10-09')
  })

  it('follows a change of timezone at once', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-08T02:00:00Z'))
    const { result, rerender } = renderHook(({ tz }) => useToday(tz), {
      initialProps: { tz: 'UTC' },
    })
    expect(result.current).toBe('2026-10-08')
    rerender({ tz: 'America/Los_Angeles' })
    expect(result.current).toBe('2026-10-07')
  })
})
