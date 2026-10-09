import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { badge, setCount } from '../live/badge'
import { renderApp } from '../test/renderApp'
import { setTheme } from '../theme'

afterEach(() => {
  act(() => setCount(0))
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  setTheme('light')
  localStorage.clear()
})

describe('Shell', () => {
  it('hides the badge at 0 and shows the count on the Notifications link and the bell', async () => {
    renderApp('/')
    await screen.findByText('YOUR PLAYBOOK')
    expect(screen.queryAllByTestId('badge')).toHaveLength(0)

    act(() => setCount(4))
    const badges = screen.getAllByTestId('badge')
    expect(badges).toHaveLength(2)
    for (const b of badges) expect(b).toHaveTextContent(/^4$/)
    expect(badges.some((b) => b.closest('a')?.getAttribute('href') === '/notifications')).toBe(true)
    expect(badges.some((b) => b.closest('button')?.getAttribute('aria-label'))).toBe(true)
    expect(screen.getByRole('button', { name: 'Notifications, 4 unread' })).toBeInTheDocument()

    act(() => setCount(0))
    expect(screen.queryAllByTestId('badge')).toHaveLength(0)
  })

  it('takes the badge from hello and unread_count', async () => {
    const app = renderApp('/')
    await screen.findByText('YOUR PLAYBOOK')
    const socket = app.sockets.last()
    act(() => socket.open())
    act(() => socket.receive({ type: 'unread_count', count: 7 }))
    act(() => socket.receive({ type: 'hello', unreadCount: 2 }))
    expect(badge.get()).toBe(2)
    expect(screen.getAllByTestId('badge')[0]).toHaveTextContent('2')
  })

  it('puts the connection state and the open count on the root', async () => {
    const app = renderApp('/')
    await screen.findByText('YOUR PLAYBOOK')
    expect(app.root()).toHaveAttribute('data-connection', 'connecting')
    expect(app.root()).toHaveAttribute('data-connects', '0')
    expect(screen.getByText('Connecting')).toBeInTheDocument()

    act(() => app.sockets.last().open())
    expect(app.root()).toHaveAttribute('data-connection', 'open')
    expect(app.root()).toHaveAttribute('data-connects', '1')
    expect(screen.getByText('Connected')).toBeInTheDocument()

    act(() => app.sockets.last().serverClose(1006))
    expect(app.root()).toHaveAttribute('data-connection', 'waiting')
    expect(screen.getByText('Reconnecting')).toBeInTheDocument()
  })

  it('refetches what is on screen after a reconnect', async () => {
    const app = renderApp('/')
    await screen.findByText('YOUR PLAYBOOK')
    act(() => app.sockets.last().open())
    const before = app.calls('/me')
    act(() => app.sockets.last().serverClose(4000))
    await waitFor(() => expect(app.sockets.all).toHaveLength(2), { timeout: 3000 })
    act(() => app.sockets.last().open())
    await waitFor(() => expect(app.calls('/me')).toBeGreaterThan(before))
  })

  it('switches to dark mode and remembers it', async () => {
    renderApp('/')
    await screen.findByText('YOUR PLAYBOOK')
    fireEvent.click(screen.getByRole('button', { name: 'Switch to dark mode' }))
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(localStorage.getItem('theme')).toBe('dark')
    fireEvent.click(screen.getByRole('button', { name: 'Switch to light mode' }))
    expect(document.documentElement.dataset.theme).toBe('light')
    expect(localStorage.getItem('theme')).toBe('light')
  })

  it('still switches when storage is blocked', async () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    renderApp('/')
    await screen.findByText('YOUR PLAYBOOK')
    fireEvent.click(screen.getByRole('button', { name: 'Switch to dark mode' }))
    expect(document.documentElement.dataset.theme).toBe('dark')
  })

  it('shows the screen name in the top bar and marks the active link', async () => {
    renderApp('/notifications')
    await screen.findByText('YOUR PLAYBOOK')
    const links = screen.getAllByRole('link', { name: /Notifications/ })
    expect(links.some((l) => l.classList.contains('active'))).toBe(true)
    expect(screen.getByText('Overview')).toBeInTheDocument()
  })
})
