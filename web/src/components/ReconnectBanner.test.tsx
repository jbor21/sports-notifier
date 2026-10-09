import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ConnectionState } from '../live/connection'
import { ReconnectBanner } from './ReconnectBanner'

const retryNow = vi.fn()

function banner(
  state: ConnectionState,
  connects = 1,
  retryAt: number | null = Date.now() + 30_000,
) {
  return <ReconnectBanner state={state} connects={connects} retryAt={retryAt} retryNow={retryNow} />
}

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
  retryNow.mockClear()
})

describe('ReconnectBanner', () => {
  it('appears after 10 s of waiting, with the countdown and Try now', () => {
    render(banner('waiting'))
    act(() => vi.advanceTimersByTime(9999))
    expect(screen.queryByText(/Reconnecting in/)).not.toBeInTheDocument()

    act(() => vi.advanceTimersByTime(1))
    expect(
      screen.getByText('Live updates paused. Reconnecting in 20 s; scores may be behind.'),
    ).toBeInTheDocument()

    act(() => vi.advanceTimersByTime(1000))
    expect(screen.getByText(/Reconnecting in 19 s/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Try now' }))
    expect(retryNow).toHaveBeenCalledOnce()
  })

  it('goes away once the socket is open again', () => {
    const { rerender } = render(banner('waiting'))
    act(() => vi.advanceTimersByTime(10_000))
    expect(screen.getByText(/Reconnecting in/)).toBeInTheDocument()
    rerender(banner('open', 2, null))
    expect(screen.queryByText(/Live updates paused/)).not.toBeInTheDocument()
  })

  it('keeps counting through the retries in between', () => {
    const { rerender } = render(banner('waiting'))
    act(() => vi.advanceTimersByTime(4000))
    rerender(banner('connecting', 1, null))
    act(() => vi.advanceTimersByTime(3000))
    rerender(banner('waiting', 1, Date.now() + 8000))
    act(() => vi.advanceTimersByTime(2999))
    expect(screen.queryByText(/Live updates paused/)).not.toBeInTheDocument()
    act(() => vi.advanceTimersByTime(1))
    expect(screen.getByText(/Reconnecting in 5 s/)).toBeInTheDocument()
  })

  it('starts the clock when connecting after having been open', () => {
    render(banner('connecting', 1, null))
    act(() => vi.advanceTimersByTime(10_000))
    expect(screen.getByText(/Live updates paused/)).toBeInTheDocument()
  })

  it('never shows for the first connect', () => {
    render(banner('connecting', 0, null))
    act(() => vi.advanceTimersByTime(20_000))
    expect(screen.queryByText(/Live updates paused/)).not.toBeInTheDocument()
  })

  it('never shows when signed out', () => {
    render(banner('signedOut'))
    act(() => vi.advanceTimersByTime(20_000))
    expect(screen.queryByText(/Live updates paused/)).not.toBeInTheDocument()
  })

  it('a short blip shows nothing', () => {
    const { rerender } = render(banner('waiting'))
    act(() => vi.advanceTimersByTime(2000))
    rerender(banner('open', 2, null))
    rerender(banner('waiting', 2))
    act(() => vi.advanceTimersByTime(9000))
    expect(screen.queryByText(/Live updates paused/)).not.toBeInTheDocument()
  })
})
