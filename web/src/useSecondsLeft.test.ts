import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useSecondsLeft } from './useSecondsLeft'

afterEach(() => vi.useRealTimers())

describe('useSecondsLeft', () => {
  it('counts down once a second to 0, then stops', () => {
    vi.useFakeTimers()
    const until = Date.now() + 30_000
    const { result } = renderHook(() => useSecondsLeft(until))
    expect(result.current).toBe(30)
    act(() => vi.advanceTimersByTime(1000))
    expect(result.current).toBe(29)
    act(() => vi.advanceTimersByTime(29_000))
    expect(result.current).toBe(0)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('is 0 with nothing to wait for', () => {
    const { result } = renderHook(() => useSecondsLeft(0))
    expect(result.current).toBe(0)
  })
})
