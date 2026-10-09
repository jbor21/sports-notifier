import { Store } from './store'

/**
 * The unread badge: the count from the last count message, hello or unread_count, in whatever order
 * they arrive (#24). It can be off by one until the next "seen" (docs/api/websocket.md).
 */
export const badge = new Store<number>(0)

export function setCount(n: number): void {
  badge.set(n)
}

export function markSeenLocally(): void {
  badge.set(0)
}
