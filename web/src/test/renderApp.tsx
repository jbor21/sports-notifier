import { render, screen } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import { vi } from 'vitest'
import App from '../App'
import type { League } from '../api/types'
import { fakeSockets } from './fakeSocket'
import { nflCatalog, user } from './fixtures'

export interface Reply {
  status: number
  body?: unknown
  headers?: Record<string, string>
}

export const nfl: League = {
  id: 'NFL',
  name: 'National Football League',
  sport: 'football',
  periodName: 'quarter',
  regulationPeriods: 4,
}

export function json(reply: Reply): Response {
  const text = reply.body === undefined ? null : JSON.stringify(reply.body)
  return new Response(text, {
    status: reply.status,
    headers: { 'Content-Type': 'application/json', ...reply.headers },
  })
}

export const unauthenticated: Reply = {
  status: 401,
  body: { error: { code: 'unauthenticated', message: 'Sign in first.' } },
}

/** Paths under /api/v1, optionally with the query; the query-less key matches any query. */
export type Routes = Record<string, Reply | (() => Reply)>

/** A fetch that answers from a table of API paths; anything else is the API's 404. */
export function stubApi(routes: Routes = {}) {
  const table: Routes = {
    '/me': { status: 200, body: user },
    '/leagues': { status: 200, body: { items: [nfl] } },
    '/leagues/NFL/alerts': { status: 200, body: { items: nflCatalog } },
    ...routes,
  }
  const fetch = vi.fn(async (input: RequestInfo | URL) => {
    const url = new URL(String(input), 'http://localhost')
    const path = url.pathname.replace(/^\/api\/v1/, '')
    const entry = table[path + url.search] ?? table[path]
    const reply = typeof entry === 'function' ? entry() : entry
    return json(
      reply ?? { status: 404, body: { error: { code: 'not_found', message: 'Not found.' } } },
    )
  })
  vi.stubGlobal('fetch', fetch)
  return {
    fetch,
    /** How many times the API path (no query) was requested. */
    calls: (path: string) =>
      fetch.mock.calls.filter(
        ([input]) => new URL(String(input), 'http://localhost').pathname === '/api/v1' + path,
      ).length,
  }
}

function Where() {
  const location = useLocation()
  return <output data-testid="location">{location.pathname + location.search}</output>
}

/** The whole app at `path`, with the API stubbed and sockets the test drives. */
export function renderApp(path = '/', routes: Routes = {}) {
  const api = stubApi(routes)
  const sockets = fakeSockets()
  const utils = render(
    <MemoryRouter initialEntries={[path]}>
      <App createSocket={sockets.create} />
      <Where />
    </MemoryRouter>,
  )
  return {
    ...utils,
    ...api,
    sockets,
    location: () => screen.getByTestId('location').textContent,
    root: () => utils.container.querySelector('.app') as HTMLElement,
  }
}
