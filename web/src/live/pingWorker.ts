/// <reference lib="webworker" />
/** The live socket's ping clock (live/ticker.ts): told the interval once, then posts a tick each time. */
export {}

declare const self: DedicatedWorkerGlobalScope

self.onmessage = (e: MessageEvent<number>) => {
  setInterval(() => self.postMessage(0), e.data)
}
