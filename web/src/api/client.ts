import { ApiError, networkError, toApiError } from './errors'

/** /api/v1 against the real backend; any other base (an absolute URL) when built for one. */
export const API_BASE = (import.meta.env.VITE_API_BASE ?? '/api/v1').replace(/\/$/, '')

type Method = 'GET' | 'POST' | 'PATCH' | 'DELETE'
type Query = Record<string, string | number | boolean | undefined>

let onSignedOut: () => void = () => {}

/** Called once per 401: the session is gone (expired, or signed out in another tab). */
export function setOnSignedOut(fn: () => void): void {
  onSignedOut = fn
}

export async function request<T>(
  path: string,
  opts: { method?: Method; body?: unknown; query?: Query } = {},
): Promise<T> {
  const method = opts.method ?? 'GET'
  let res: Response
  try {
    res = await fetch(API_BASE + path + queryString(opts.query), {
      method,
      credentials: 'same-origin',
      headers: opts.body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
    })
  } catch {
    throw networkError()
  }
  if (!res.ok) {
    const error = await toApiError(res)
    // A 401 from login means a wrong password, not a lost session.
    if (res.status === 401 && path !== '/auth/login') onSignedOut()
    throw error
  }
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

/** For SWR: the key is the path, query included. */
export const fetcher = <T>(path: string): Promise<T> => request<T>(path)

export function socketUrl(
  base: string = API_BASE,
  loc: Pick<Location, 'protocol' | 'host'> = window.location,
): string {
  if (/^https?:\/\//.test(base)) return base.replace(/^http/, 'ws') + '/ws'
  return (loc.protocol === 'https:' ? 'wss://' : 'ws://') + loc.host + base + '/ws'
}

function queryString(query?: Query): string {
  if (!query) return ''
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined) params.set(key, String(value))
  }
  const s = params.toString()
  return s ? '?' + s : ''
}

export { ApiError }
