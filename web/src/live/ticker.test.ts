import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { workerTicker, type WorkerLike } from './ticker'

function fakeWorker() {
  const w = {
    posted: [] as unknown[],
    terminated: false,
    onmessage: null as (() => void) | null,
    onerror: null as (() => void) | null,
    postMessage: (m: unknown) => void w.posted.push(m),
    terminate: () => void (w.terminated = true),
  }
  return w satisfies WorkerLike
}

beforeEach(() => vi.useFakeTimers())
afterEach(() => vi.useRealTimers())

describe('workerTicker', () => {
  it('ticks when the worker posts, and runs no page timer of its own', () => {
    const worker = fakeWorker()
    const fn = vi.fn()
    workerTicker(25000, fn, () => worker)
    expect(worker.posted).toEqual([25000])
    vi.advanceTimersByTime(60000)
    expect(fn).not.toHaveBeenCalled()
    worker.onmessage!()
    expect(fn).toHaveBeenCalledTimes(1)
  })

  it('terminates the worker when stopped', () => {
    const worker = fakeWorker()
    const stop = workerTicker(25000, vi.fn(), () => worker)
    stop()
    expect(worker.terminated).toBe(true)
  })

  it.each([
    ['there are no workers', () => null],
    [
      'the worker cannot be created',
      () => {
        throw new Error('blocked')
      },
    ],
  ])('uses a page timer when %s', (_, createWorker) => {
    const fn = vi.fn()
    const stop = workerTicker(25000, fn, createWorker)
    vi.advanceTimersByTime(25000)
    expect(fn).toHaveBeenCalledTimes(1)
    stop()
    vi.advanceTimersByTime(25000)
    expect(fn).toHaveBeenCalledTimes(1)
  })

  it('switches to a page timer when the worker fails to load', () => {
    const worker = fakeWorker()
    const fn = vi.fn()
    const stop = workerTicker(25000, fn, () => worker)
    worker.onerror!()
    expect(worker.terminated).toBe(true)
    vi.advanceTimersByTime(25000)
    expect(fn).toHaveBeenCalledTimes(1)
    stop()
    vi.advanceTimersByTime(25000)
    expect(fn).toHaveBeenCalledTimes(1)
  })
})
