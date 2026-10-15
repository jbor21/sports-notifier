/** The live socket's ping clock (live/ticker.ts): told the interval once, then posts a tick each time. */
export {}

// A local type, not `/// <reference lib="webworker" />`: that would add worker globals to the whole
// app's type check (src/sw.ts is kept out of it for the same reason).
const scope = self as unknown as {
  onmessage: ((e: MessageEvent<number>) => void) | null
  postMessage(message: number): void
}

scope.onmessage = (e) => {
  setInterval(() => scope.postMessage(0), e.data)
}
