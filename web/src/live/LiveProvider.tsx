import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { useLocation, useNavigate } from 'react-router'
import useSWR, { useSWRConfig } from 'swr'
import { fetcher, request, setOnSignedOut, socketUrl } from '../api/client'
import type { AlertType, League, Notice } from '../api/types'
import { isPublicPath, signInPath, useMe } from '../session'
import { setCount } from './badge'
import { LiveConnection, type ConnectionState, type SocketLike } from './connection'
import { gameStore } from './games'
import { isKnown, knownTypes } from './knownTypes'
import { addPushed, clearPushed } from './pushed'

export type Toast =
  | { id: string; kind: 'notice'; notice: Notice }
  | { id: string; kind: 'error' | 'update'; text: string; action?: { label: string; run(): void } }

export interface ConnectionInfo {
  state: ConnectionState
  retryAt: number | null
  connects: number
  retryNow(): void
}

export interface ToastApi {
  toasts: Toast[]
  push(t: Toast): void
  dismiss(id: string): void
}

/** Notices and errors leave after this; an update toast waits for an answer. */
export const TOAST_MS = 6500

const ConnectionContext = createContext<ConnectionInfo>({
  state: 'stopped',
  retryAt: null,
  connects: 0,
  retryNow: () => {},
})
const SocketContext = createContext<LiveConnection | null>(null)
const KnownContext = createContext<Set<string> | null>(null)
const ToastContext = createContext<ToastApi>({ toasts: [], push: () => {}, dismiss: () => {} })

/**
 * Owns the live socket for the signed-in user and wires it to the stores: scores into the game
 * store, counts into the badge, known notifications into the pushed list and a toast. Also the
 * one place a lost session (any 401, or close 4401) turns into "go to sign in".
 */
export function LiveProvider({
  children,
  createSocket,
}: {
  children: ReactNode
  createSocket?: (url: string) => SocketLike
}) {
  const me = useMe()
  const navigate = useNavigate()
  const location = useLocation()
  const { mutate } = useSWRConfig()
  const toasts = useToastState()
  const known = useKnownTypesLoader()

  const here = useRef('')
  here.current = location.pathname + location.search

  const signOutLocally = useCallback(() => {
    // Forget the user (the socket closes with it) without asking /me again.
    void mutate('/me', undefined, { revalidate: false })
    // Already on sign in or sign up: stay, so `next` survives and nothing loops.
    const pathname = here.current.split('?')[0]
    if (!isPublicPath(pathname)) navigate(signInPath(here.current), { replace: true })
  }, [mutate, navigate])

  useEffect(() => {
    setOnSignedOut(signOutLocally)
    return () => setOnSignedOut(() => {})
  }, [signOutLocally])

  // The socket handlers are created once per connection; refs give them the current values.
  const latest = useRef({ known, push: toasts.push, mutate, signOutLocally })
  latest.current = { known, push: toasts.push, mutate, signOutLocally }

  const userId = me.data && me.error?.status !== 401 ? me.data.id : null
  const [conn, setConn] = useState<LiveConnection | null>(null)
  const [info, setInfo] = useState<Omit<ConnectionInfo, 'retryNow'>>({
    state: 'stopped',
    retryAt: null,
    connects: 0,
  })

  useEffect(() => {
    if (!userId) return
    const c = new LiveConnection(
      socketUrl(),
      {
        onState: (state, { retryAt, connects }) => setInfo({ state, retryAt, connects }),
        onHello: setCount,
        onUnreadCount: setCount,
        onGameUpdate: (g) => gameStore.accept(g),
        onNotification: (n) => {
          if (!isKnown(n, latest.current.known)) return
          addPushed(n)
          latest.current.push({ id: n.id, kind: 'notice', notice: n })
        },
        // Whatever is on screen may have missed updates while the socket was down.
        onReconnected: () => void latest.current.mutate(() => true),
        onSignedOut: () => latest.current.signOutLocally(),
      },
      { createSocket },
    )
    setConn(c)
    c.start()
    return () => {
      c.stop()
      setConn(null)
      // The next user starts with an empty badge and no one else's notifications.
      setCount(0)
      clearPushed()
    }
  }, [userId, createSocket])

  const connection = useMemo<ConnectionInfo>(
    () => ({ ...info, retryNow: () => conn?.retryNow() }),
    [info, conn],
  )

  return (
    <SocketContext.Provider value={conn}>
      <ConnectionContext.Provider value={connection}>
        <KnownContext.Provider value={known}>
          <ToastContext.Provider value={toasts}>{children}</ToastContext.Provider>
        </KnownContext.Provider>
      </ConnectionContext.Provider>
    </SocketContext.Provider>
  )
}

function useToastState(): ToastApi {
  const [toasts, setToasts] = useState<Toast[]>([])
  const timers = useRef(new Map<string, ReturnType<typeof setTimeout>>())

  const dismiss = useCallback((id: string) => {
    clearTimeout(timers.current.get(id))
    timers.current.delete(id)
    setToasts((list) => list.filter((t) => t.id !== id))
  }, [])

  const push = useCallback(
    (t: Toast) => {
      clearTimeout(timers.current.get(t.id))
      if (t.kind !== 'update')
        timers.current.set(
          t.id,
          setTimeout(() => dismiss(t.id), TOAST_MS),
        )
      setToasts((list) => [t, ...list.filter((x) => x.id !== t.id)])
    },
    [dismiss],
  )

  useEffect(() => {
    const pending = timers.current
    return () => pending.forEach(clearTimeout)
  }, [])

  return useMemo(() => ({ toasts, push, dismiss }), [toasts, push, dismiss])
}

/** Every league's catalog, fetched once; null until all have loaded (then nothing is skipped). */
function useKnownTypesLoader(): Set<string> | null {
  const once = { revalidateOnFocus: false, revalidateOnReconnect: false }
  const leagues = useSWR<{ items: League[] }>('/leagues', fetcher, once)
  const ids = leagues.data?.items.map((l) => l.id)
  const catalogs = useSWR(
    ids ? ['catalogs', ...ids] : null,
    ([, ...leagueIds]: string[]) =>
      Promise.all(
        leagueIds.map((id) =>
          request<{ items: AlertType[] }>(`/leagues/${encodeURIComponent(id)}/alerts`).then(
            (r) => r.items,
          ),
        ),
      ),
    once,
  )
  return useMemo(() => (catalogs.data ? knownTypes(catalogs.data) : null), [catalogs.data])
}

export function useConnection(): ConnectionInfo {
  return useContext(ConnectionContext)
}

/** "This screen shows these games": the socket sends their updates. Cleared on unmount. */
export function useSubscribeGames(ids: string[]): void {
  const conn = useContext(SocketContext)
  const key = JSON.stringify(ids)
  useEffect(() => {
    conn?.setGames(JSON.parse(key) as string[])
  }, [conn, key])
  // Separate from the effect above so a changed list replaces the old one without an empty
  // subscribe in between.
  useEffect(() => () => conn?.setGames([]), [conn])
}

export function useKnownTypes(): Set<string> | null {
  return useContext(KnownContext)
}

export function useToasts(): ToastApi {
  return useContext(ToastContext)
}
