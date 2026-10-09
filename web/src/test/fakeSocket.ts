import type { SocketLike } from '../live/connection'

/** A WebSocket the test drives: it opens, receives, and closes only when told to. */
export class FakeSocket implements SocketLike {
  readyState = 0
  sent: string[] = []
  closedWith: number | undefined
  onopen: (() => void) | null = null
  onmessage: ((e: { data: string }) => void) | null = null
  onclose: ((e: { code: number; reason: string }) => void) | null = null
  onerror: (() => void) | null = null

  constructor(readonly url: string) {}

  send(data: string): void {
    if (this.readyState !== 1) throw new Error('send on a socket that is not open')
    this.sent.push(data)
  }

  close(code = 1000): void {
    this.readyState = 3
    this.closedWith = code
  }

  open(): void {
    this.readyState = 1
    this.onopen?.()
  }

  receive(message: unknown): void {
    this.onmessage?.({ data: typeof message === 'string' ? message : JSON.stringify(message) })
  }

  serverClose(code: number, reason = ''): void {
    this.readyState = 3
    this.onclose?.({ code, reason })
  }

  sentMessages(): Array<{ type: string; [k: string]: unknown }> {
    return this.sent.map((s) => JSON.parse(s))
  }
}

export function fakeSockets() {
  const all: FakeSocket[] = []
  return {
    all,
    create: (url: string) => {
      const s = new FakeSocket(url)
      all.push(s)
      return s
    },
    last: () => all[all.length - 1],
  }
}
