import { useSyncExternalStore } from 'react'

/** A value React can watch: the live state outside React (badge, pushed notifications). */
export class Store<T> {
  private listeners = new Set<() => void>()

  constructor(private value: T) {}

  get(): T {
    return this.value
  }

  set(value: T): void {
    if (Object.is(value, this.value)) return
    this.value = value
    for (const fn of this.listeners) fn()
  }

  subscribe(fn: () => void): () => void {
    this.listeners.add(fn)
    return () => this.listeners.delete(fn)
  }
}

export function useStore<T>(s: Store<T>): T {
  return useSyncExternalStore(
    (fn) => s.subscribe(fn),
    () => s.get(),
  )
}
