import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { user } from '../test/fixtures'
import { renderApp, unauthenticated, type Reply, type Routes } from '../test/renderApp'

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

/** Signed out until the form's call succeeds; /me answers for the new session after that. */
function signedOutApp(path: string, authPath: string, reply: Reply, routes: Routes = {}) {
  let signedIn = false
  return renderApp(path, {
    '/me': () => (signedIn ? { status: 200, body: user } : unauthenticated),
    [authPath]: () => {
      if (reply.status < 300) signedIn = true
      return reply
    },
    ...routes,
  })
}

async function fill(username: string, password: string) {
  fireEvent.change(await screen.findByLabelText('Username'), { target: { value: username } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: password } })
}

const created: Reply = { status: 201, body: user }
const browserZone = Intl.DateTimeFormat().resolvedOptions().timeZone

describe('sign up', () => {
  it('sends the username, password, and the browser timezone, then goes to next', async () => {
    const app = signedOutApp('/signup?next=%2Fsettings', '/auth/signup', created)
    await fill('jacob_9', 'abcdefgh9')
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }))
    await waitFor(() => expect(app.location()).toBe('/settings'))
    expect(app.sent('/auth/signup', 'POST')[0].body).toEqual({
      username: 'jacob_9',
      password: 'abcdefgh9',
      timezone: browserZone,
    })
  })

  it('goes to Game day without a next', async () => {
    const app = signedOutApp('/signup', '/auth/signup', created)
    await fill('jacob', 'abcdefgh9')
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }))
    await waitFor(() => expect(app.location()).toBe('/'))
  })

  it('refuses a password under 8 bytes without a request', async () => {
    const app = signedOutApp('/signup', '/auth/signup', created)
    await fill('jacob', 'abcdefg')
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }))
    expect(await screen.findByText(/8 to 72 bytes/)).toBeInTheDocument()
    expect(app.calls('/auth/signup')).toBe(0)
  })

  it('counts bytes in UTF-8: 36 ü (72 bytes) pass, 37 do not', async () => {
    const app = signedOutApp('/signup', '/auth/signup', created)
    await fill('jacob', 'ü'.repeat(37))
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }))
    expect(await screen.findByText(/8 to 72 bytes/)).toBeInTheDocument()
    expect(app.calls('/auth/signup')).toBe(0)

    await fill('jacob', 'ü'.repeat(36))
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }))
    await waitFor(() => expect(app.calls('/auth/signup')).toBe(1))
  })

  it('refuses capitals in the username', async () => {
    const app = signedOutApp('/signup', '/auth/signup', created)
    await fill('Jacob', 'abcdefgh9')
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }))
    expect(await screen.findByText(/lowercase letters/)).toBeInTheDocument()
    expect(app.calls('/auth/signup')).toBe(0)
  })

  it('shows the server’s message for a taken username', async () => {
    const taken: Reply = {
      status: 409,
      body: { error: { code: 'username_taken', message: 'That username is taken.' } },
    }
    const app = signedOutApp('/signup', '/auth/signup', taken)
    await fill('jacob', 'abcdefgh9')
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }))
    expect(await screen.findByText('That username is taken.')).toBeInTheDocument()
    expect(app.location()).toBe('/signup')
  })

  it('shows a field error from the server under its field', async () => {
    const invalid: Reply = {
      status: 422,
      body: {
        error: {
          code: 'validation_failed',
          message: 'Password must be at least 8 characters.',
          fields: { password: 'too_short' },
        },
      },
    }
    signedOutApp('/signup', '/auth/signup', invalid)
    await fill('jacob', 'abcdefgh9')
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }))
    expect(await screen.findByText('Password must be at least 8 characters.')).toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toHaveAttribute('aria-invalid', 'true')
  })

  it('links back to sign in, keeping next', async () => {
    const app = signedOutApp('/signup?next=%2Fsettings', '/auth/signup', created)
    fireEvent.click(await screen.findByRole('link', { name: 'Sign in' }))
    expect(app.location()).toBe('/signin?next=%2Fsettings')
  })
})

describe('sign in', () => {
  it('signs in and goes to next', async () => {
    const app = signedOutApp('/signin?next=%2Fsettings', '/auth/login', {
      status: 200,
      body: user,
    })
    await fill('jacob', 'abcdefgh9')
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    await waitFor(() => expect(app.location()).toBe('/settings'))
    expect(app.sent('/auth/login', 'POST')[0].body).toEqual({
      username: 'jacob',
      password: 'abcdefgh9',
    })
  })

  it('a wrong password stays on sign in with the message', async () => {
    const app = signedOutApp('/signin', '/auth/login', {
      status: 401,
      body: { error: { code: 'invalid_credentials', message: 'Wrong username or password.' } },
    })
    await fill('jacob', 'abcdefgh9')
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByText('Wrong username or password.')).toBeInTheDocument()
    expect(app.location()).toBe('/signin')
  })

  it('429 disables the button with a countdown from Retry-After', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    signedOutApp('/signin', '/auth/login', {
      status: 429,
      headers: { 'Retry-After': '30' },
      body: {
        error: { code: 'rate_limited', message: 'Too many attempts. Try again in a minute.' },
      },
    })
    await fill('jacob', 'abcdefgh9')
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    // The clock also moves with real time here (shouldAdvanceTime), so read the count rather than
    // assume no second has passed yet on a slow machine.
    const button = await screen.findByRole('button', { name: /^Try again in \d+ s$/ })
    expect(button).toBeDisabled()
    const left = Number(button.textContent!.match(/\d+/)![0])
    expect(left).toBeGreaterThan(25)
    expect(left).toBeLessThanOrEqual(30)
    // The countdown re-renders once a second from when its timer started, which on a slow runner
    // can be just after the read above: wait for the drop rather than expect it on one exact tick.
    act(() => vi.advanceTimersByTime(1000))
    await waitFor(() => {
      const after = screen.getByRole('button', { name: /^Try again in \d+ s$/ })
      expect(Number(after.textContent!.match(/\d+/)![0])).toBeLessThan(left)
      expect(after).toBeDisabled()
    })
    act(() => vi.advanceTimersByTime(30_000))
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled()
  })

  it('links to sign up, keeping next', async () => {
    const app = signedOutApp('/signin?next=%2Fsettings', '/auth/login', created)
    fireEvent.click(await screen.findByRole('link', { name: 'Create an account' }))
    expect(app.location()).toBe('/signup?next=%2Fsettings')
  })

  it('ignores a next that leaves the app', async () => {
    const app = signedOutApp('/signin?next=%2F%2Fevil.example', '/auth/login', {
      status: 200,
      body: user,
    })
    await fill('jacob', 'abcdefgh9')
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    await waitFor(() => expect(app.location()).toBe('/'))
  })
})
