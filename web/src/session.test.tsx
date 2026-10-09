import { act, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { signInPath } from './session'
import { renderApp, unauthenticated } from './test/renderApp'
import { user } from './test/fixtures'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('signInPath', () => {
  it('carries the path and query as next', () => {
    expect(signInPath('/following')).toBe('/signin?next=%2Ffollowing')
    expect(signInPath('/games/nfl-1?tab=x')).toBe('/signin?next=%2Fgames%2Fnfl-1%3Ftab%3Dx')
  })

  it('leaves next out for the public screens', () => {
    expect(signInPath('/signin?next=%2Fsettings')).toBe('/signin')
    expect(signInPath('/signup')).toBe('/signin')
  })
})

describe('the sign-in guard', () => {
  it('guardSendsToSignIn', async () => {
    const app = renderApp('/following', { '/me': unauthenticated })
    await waitFor(() => expect(app.location()).toBe('/signin?next=%2Ffollowing'))
    expect(screen.queryByText('YOUR PLAYBOOK')).not.toBeInTheDocument()
  })

  it('noLoopOnSignIn', async () => {
    const app = renderApp('/signin?next=%2Fsettings', { '/me': unauthenticated })
    await waitFor(() => expect(app.calls('/me')).toBe(1))
    // Give a redirect or a retry every chance to happen.
    await act(() => new Promise((r) => setTimeout(r, 100)))
    expect(app.location()).toBe('/signin?next=%2Fsettings')
    expect(app.calls('/me')).toBe(1)
  })

  it('signedInRendersShell', async () => {
    const app = renderApp('/following')
    expect(await screen.findByRole('heading', { name: 'Following', level: 1 })).toBeInTheDocument()
    expect(screen.getByText('YOUR PLAYBOOK')).toBeInTheDocument()
    expect(screen.getByText('FIND YOUR GAME')).toBeInTheDocument()
    expect(screen.getByText(user.username)).toBeInTheDocument()
    expect(app.location()).toBe('/following')
    // Signed in: the socket opens.
    expect(app.sockets.all).toHaveLength(1)
  })

  it('an unknown path goes to Game day', async () => {
    const app = renderApp('/nope')
    await waitFor(() => expect(app.location()).toBe('/'))
    expect(await screen.findByRole('heading', { name: 'Game day', level: 1 })).toBeInTheDocument()
  })

  it('a server error at start shows the error box, not sign in', async () => {
    const app = renderApp('/settings', { '/me': { status: 500, body: 'oops' } })
    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong on our side')
    expect(app.location()).toBe('/settings')
    expect(app.sockets.all).toHaveLength(0)
  })

  it('a 401 from any later call sends to sign in and closes the socket', async () => {
    let signedIn = true
    const app = renderApp('/notifications', {
      '/me': () => (signedIn ? { status: 200, body: user } : unauthenticated),
      '/follows': unauthenticated,
    })
    await screen.findByText('YOUR PLAYBOOK')
    signedIn = false
    const { request } = await import('./api/client')
    await expect(request('/follows')).rejects.toThrow()
    await waitFor(() => expect(app.location()).toBe('/signin?next=%2Fnotifications'))
    expect(app.sockets.last().closedWith).toBe(1000)
  })

  it('a socket closed with 4401 sends to sign in', async () => {
    const app = renderApp('/settings')
    await screen.findByText('YOUR PLAYBOOK')
    act(() => app.sockets.last().open())
    act(() => app.sockets.last().serverClose(4401))
    await waitFor(() => expect(app.location()).toBe('/signin?next=%2Fsettings'))
    expect(app.root()).toHaveAttribute('data-connection', 'stopped')
  })
})
