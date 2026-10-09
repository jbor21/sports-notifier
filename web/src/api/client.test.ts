import { afterEach, describe, expect, it, vi } from 'vitest'
import { request, setOnSignedOut, socketUrl } from './client'
import { ApiError } from './errors'
import { errorExamples } from '../test/fixtures'

function respond(status: number, body?: unknown, headers: Record<string, string> = {}) {
  const text = body === undefined ? null : typeof body === 'string' ? body : JSON.stringify(body)
  return vi.fn().mockResolvedValue(new Response(text, { status, headers }))
}

async function failure(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise
  } catch (e) {
    expect(e).toBeInstanceOf(ApiError)
    return e as ApiError
  }
  throw new Error('expected the request to fail')
}

afterEach(() => {
  vi.unstubAllGlobals()
  setOnSignedOut(() => {})
})

describe('request', () => {
  it('sends cookies and JSON', async () => {
    const fetch = respond(200, { id: 'u_1' })
    vi.stubGlobal('fetch', fetch)
    await request('/auth/login', { method: 'POST', body: { username: 'jacob' } })
    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/auth/login',
      expect.objectContaining({
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: '{"username":"jacob"}',
      }),
    )
  })

  it('builds the query and leaves out undefined values', async () => {
    const fetch = respond(200, { items: [] })
    vi.stubGlobal('fetch', fetch)
    await request('/games', { query: { date: '2026-10-08', league: undefined, following: true } })
    expect(fetch.mock.calls[0][0]).toBe('/api/v1/games?date=2026-10-08&following=true')
  })

  it('sends no Content-Type without a body', async () => {
    const fetch = respond(200, {})
    vi.stubGlobal('fetch', fetch)
    await request('/me')
    expect(fetch.mock.calls[0][1].headers).toBeUndefined()
  })

  it('returns undefined for 204', async () => {
    vi.stubGlobal('fetch', respond(204))
    expect(await request('/notifications/seen', { method: 'POST' })).toBeUndefined()
  })

  it('keeps the Error shape', async () => {
    vi.stubGlobal('fetch', respond(422, errorExamples.validation))
    const e = await failure(request('/auth/signup', { method: 'POST', body: {} }))
    expect(e.status).toBe(422)
    expect(e.code).toBe('validation_failed')
    expect(e.fields.password).toBe('too_short')
    expect(e.message).toBe('Password must be at least 8 characters.')
  })

  it('reads Retry-After', async () => {
    vi.stubGlobal('fetch', respond(429, errorExamples.rateLimited, { 'Retry-After': '30' }))
    const e = await failure(request('/auth/login', { method: 'POST', body: {} }))
    expect(e.code).toBe('rate_limited')
    expect(e.retryAfter).toBe(30)
  })

  it('turns a bare 500 into a plain message (#25)', async () => {
    vi.stubGlobal('fetch', respond(500, 'Internal server error', { 'Content-Type': 'text/plain' }))
    const e = await failure(request('/games'))
    expect(e.status).toBe(500)
    expect(e.code).toBe('server_error')
    expect(e.message).toBe('Something went wrong on our side. Try again.')
  })

  it("turns Traefik's 502 page into a plain message", async () => {
    vi.stubGlobal('fetch', respond(502, '<html><body>Bad Gateway</body></html>'))
    const e = await failure(request('/games'))
    expect(e.code).toBe('server_error')
  })

  it('reports a network failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    const e = await failure(request('/games'))
    expect(e.status).toBe(0)
    expect(e.code).toBe('network')
    expect(e.message).toBe("Can't reach Sideline. Check your connection.")
  })

  it('signs out on a 401, except from login', async () => {
    const signedOut = vi.fn()
    setOnSignedOut(signedOut)
    vi.stubGlobal('fetch', respond(401, errorExamples.unauthenticated))
    await failure(request('/me'))
    expect(signedOut).toHaveBeenCalledTimes(1)
    await failure(request('/auth/login', { method: 'POST', body: {} }))
    expect(signedOut).toHaveBeenCalledTimes(1)
  })
})

describe('socketUrl', () => {
  it('follows the page for a relative base', () => {
    expect(socketUrl('/api/v1', { protocol: 'https:', host: 'x.io' })).toBe('wss://x.io/api/v1/ws')
    expect(socketUrl('/api/v1', { protocol: 'http:', host: 'localhost:5173' })).toBe(
      'ws://localhost:5173/api/v1/ws',
    )
  })

  it('follows an absolute base', () => {
    expect(socketUrl('http://localhost:4010', { protocol: 'http:', host: 'x' })).toBe(
      'ws://localhost:4010/ws',
    )
    expect(socketUrl('https://api.example.com/api/v1', { protocol: 'http:', host: 'x' })).toBe(
      'wss://api.example.com/api/v1/ws',
    )
  })
})
