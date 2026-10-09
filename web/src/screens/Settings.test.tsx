import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { follow, user } from '../test/fixtures'
import { renderApp, unauthenticated, type Routes } from '../test/renderApp'
import { setTheme } from '../theme'

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
  setTheme('light')
  localStorage.clear()
})

function openSettings(routes: Routes = {}) {
  return renderApp('/settings', routes)
}

describe('Settings', () => {
  it('shows the username, and the current timezone first in the list', async () => {
    openSettings()
    expect(await screen.findByText(user.username, { selector: 'p' })).toBeInTheDocument()
    const select = screen.getByLabelText('Your timezone') as HTMLSelectElement
    expect(select.value).toBe(user.timezone)
    expect(select.options[0].value).toBe(user.timezone)
    expect(select.options.length).toBeGreaterThan(100)
  })

  it('saves the timezone with PATCH /me', async () => {
    const app = openSettings({
      '/me': (sent) =>
        sent.method === 'PATCH'
          ? { status: 200, body: { ...user, ...(sent.body as object) } }
          : { status: 200, body: user },
    })
    const select = await screen.findByLabelText('Your timezone')
    fireEvent.change(select, { target: { value: 'America/Chicago' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(await screen.findByRole('button', { name: 'Saved' })).toBeInTheDocument()
    expect(app.sent('/me', 'PATCH').map((s) => s.body)).toEqual([{ timezone: 'America/Chicago' }])
  })

  it('switches dark mode', async () => {
    openSettings()
    const dark = await screen.findByRole('switch', { name: 'Dark mode' })
    expect(dark).toHaveAttribute('aria-checked', 'false')
    fireEvent.click(dark)
    expect(dark).toHaveAttribute('aria-checked', 'true')
    expect(document.documentElement.dataset.theme).toBe('dark')
  })

  it('shows the build id and how to install the app', async () => {
    vi.stubEnv('VITE_BUILD_ID', 'test-build')
    openSettings()
    expect(await screen.findByText('Version test-build')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Sideline, on your home screen' })).toBeVisible()
  })

  it('signs out: POST /auth/logout, then sign in', async () => {
    let signedIn = true
    const app = openSettings({
      '/me': () => (signedIn ? { status: 200, body: user } : unauthenticated),
      '/auth/logout': () => {
        signedIn = false
        return { status: 204 }
      },
    })
    fireEvent.click(await screen.findByRole('button', { name: 'Sign out' }))
    await waitFor(() => expect(app.location()).toBe('/signin'))
    expect(app.sent('/auth/logout', 'POST')).toHaveLength(1)
    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('the next user does not see the last user’s follows', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let signedIn = true
    let follows = [follow]
    let current = user
    const app = renderApp('/following', {
      '/me': () => (signedIn ? { status: 200, body: current } : unauthenticated),
      '/auth/logout': () => {
        signedIn = false
        return { status: 204 }
      },
      '/auth/login': () => {
        signedIn = true
        follows = []
        current = { ...user, id: 'u_other', username: 'other' }
        return { status: 200, body: current }
      },
      '/follows': () => ({ status: 200, body: { items: follows } }),
    })
    expect(await screen.findByText('Chicago Bears')).toBeInTheDocument()
    fireEvent.click(screen.getAllByRole('link', { name: /Settings/ })[0])
    fireEvent.click(await screen.findByRole('button', { name: 'Sign out' }))
    await waitFor(() => expect(app.location()).toBe('/signin'))
    // SWR shares a request made in the last 2 s instead of asking again; a real sign-in takes longer.
    await act(() => vi.advanceTimersByTimeAsync(2100))

    fireEvent.change(await screen.findByLabelText('Username'), { target: { value: 'other' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'abcdefgh9' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    await waitFor(() => expect(app.location()).toBe('/'))

    // Any render of the old list, however brief, would be caught here.
    let leaked = false
    const watch = new MutationObserver(() => {
      if (document.body.textContent?.includes('Chicago Bears')) leaked = true
    })
    watch.observe(document.body, { subtree: true, childList: true, characterData: true })
    fireEvent.click(screen.getAllByRole('link', { name: /Following/ })[0])
    expect(await screen.findByText('Find your people')).toBeInTheDocument()
    watch.disconnect()
    expect(leaked).toBe(false)
    expect(app.sent('/follows')).toHaveLength(2)
  })
})
