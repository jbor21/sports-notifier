/** Calls fn every ms until the returned function is called. */
export type Ticker = (ms: number, fn: () => void) => () => void

export interface WorkerLike {
  postMessage(message: unknown): void
  terminate(): void
  onmessage: ((e: MessageEvent) => void) | null
  onerror: ((e: ErrorEvent) => void) | null
}

function createPingWorker(): WorkerLike | null {
  if (typeof Worker === 'undefined') return null
  return new Worker(new URL('./pingWorker.ts', import.meta.url), { type: 'module' })
}

/**
 * Chrome runs a hidden tab's timers about once a minute after 5 minutes, which stretches a 25 s
 * ping past the server's 60 s idle close (#36: the socket cycled in a hidden tab). A dedicated
 * worker's timers aren't slowed that way, so the clock runs there; a page timer is the fallback.
 */
export function workerTicker(
  ms: number,
  fn: () => void,
  createWorker: () => WorkerLike | null = createPingWorker,
): () => void {
  let timer: ReturnType<typeof setInterval> | undefined
  let stopped = false
  const usePageTimer = () => (timer = setInterval(fn, ms))
  let worker: WorkerLike | null = null
  try {
    worker = createWorker()
  } catch {
    // A browser or policy that refuses the worker: the page timer still works, only slower when hidden.
  }
  if (worker) {
    const w = worker
    // A tick or an error can already be queued when stop() terminates the worker.
    w.onmessage = () => {
      if (!stopped) fn()
    }
    w.onerror = () => {
      if (stopped || timer !== undefined) return
      w.terminate()
      usePageTimer()
    }
    w.postMessage(ms)
  } else {
    usePageTimer()
  }
  return () => {
    stopped = true
    worker?.terminate()
    clearInterval(timer)
  }
}
