import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { SWRConfig } from 'swr'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Notice } from '../api/types'
import { clearPushed, pushed } from '../live/pushed'
import { LiveProvider, useToasts } from '../live/LiveProvider'
import { notification } from '../test/fixtures'
import { renderApp, stubApi, unauthenticated } from '../test/renderApp'
import { Toasts } from './Toasts'

function notice(i: number, type = 'TOUCHDOWN'): Notice {
  return {
    ...notification,
    id: `${notification.id}:${i}`,
    headline: { type, text: `Touchdown #${i}` },
  }
}

/** The toasts only: Game day's "Latest alerts" lists the same pushes. */
const stack = () => within(document.querySelector('.toast-stack') as HTMLElement)

/** Signed in on Game day with the socket open and every league's catalog loaded. */
async function openApp() {
  const app = renderApp('/')
  await screen.findByText('YOUR PLAYBOOK')
  await waitFor(() => expect(app.calls('/leagues/NFL/alerts')).toBe(1))
  await act(() => new Promise((r) => setTimeout(r, 0)))
  const socket = app.sockets.last()
  act(() => socket.open())
  return { ...app, socket }
}

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  clearPushed()
})

describe('notification toasts', () => {
  it('a burst of ten shows the newest three, newest on top, and keeps all ten', async () => {
    const { socket } = await openApp()
    vi.useFakeTimers()
    act(() => {
      for (let i = 1; i <= 10; i++)
        socket.receive({ type: 'notification', notification: notice(i) })
    })
    const shown = stack()
      .getAllByText(/^Touchdown #\d+$/)
      .map((el) => el.textContent)
    expect(shown).toEqual(['Touchdown #10', 'Touchdown #9', 'Touchdown #8'])
    expect(pushed.get()).toHaveLength(10)
    expect(stack().getAllByText('SIDELINE · LIVE ALERT')).toHaveLength(3)
    expect(document.querySelector('.toast-stack')).toHaveAttribute('aria-live', 'polite')
  })

  it('each toast goes after 6.5 s', async () => {
    const { socket } = await openApp()
    vi.useFakeTimers()
    act(() => socket.receive({ type: 'notification', notification: notice(1) }))
    act(() => vi.advanceTimersByTime(3000))
    act(() => socket.receive({ type: 'notification', notification: notice(2) }))
    act(() => vi.advanceTimersByTime(3499))
    expect(stack().getByText('Touchdown #1')).toBeInTheDocument()
    act(() => vi.advanceTimersByTime(1))
    expect(stack().queryByText('Touchdown #1')).not.toBeInTheDocument()
    expect(stack().getByText('Touchdown #2')).toBeInTheDocument()
    act(() => vi.advanceTimersByTime(3000))
    expect(stack().queryByText('Touchdown #2')).not.toBeInTheDocument()
  })

  it('clicking a toast opens its game', async () => {
    const app = await openApp()
    act(() => app.socket.receive({ type: 'notification', notification: notice(1) }))
    fireEvent.click(stack().getByText('Touchdown #1'))
    expect(app.location()).toBe(`/games/${notification.game!.id}`)
    expect(stack().queryByText('Touchdown #1')).not.toBeInTheDocument()
  })

  it('the same push twice is one toast', async () => {
    const { socket } = await openApp()
    act(() => socket.receive({ type: 'notification', notification: notice(1) }))
    act(() => socket.receive({ type: 'notification', notification: notice(1) }))
    expect(stack().getAllByText('Touchdown #1')).toHaveLength(1)
    expect(pushed.get()).toHaveLength(1)
  })

  it('skips a notification whose headline type no catalog knows', async () => {
    const { socket } = await openApp()
    act(() => socket.receive({ type: 'notification', notification: notice(1, 'MYSTERY') }))
    expect(stack().queryByText('Touchdown #1')).not.toBeInTheDocument()
    expect(pushed.get()).toHaveLength(0)
  })

  it('can be dismissed', async () => {
    const { socket } = await openApp()
    act(() => socket.receive({ type: 'notification', notification: notice(1) }))
    fireEvent.click(screen.getByRole('button', { name: 'Dismiss alert' }))
    expect(stack().queryByText('Touchdown #1')).not.toBeInTheDocument()
  })
})

function Pusher() {
  const { push } = useToasts()
  return (
    <>
      <button onClick={() => push({ id: 'e1', kind: 'error', text: 'Could not save.' })}>
        error
      </button>
      <button
        onClick={() =>
          push({
            id: 'u1',
            kind: 'update',
            text: 'New version',
            action: { label: 'Reload', run: reload },
          })
        }
      >
        update
      </button>
    </>
  )
}
const reload = vi.fn()

describe('error and update toasts', () => {
  it('an error toast times out; an update toast stays and runs its action', () => {
    stubApi({ '/me': unauthenticated })
    vi.useFakeTimers()
    render(
      <SWRConfig value={{ provider: () => new Map() }}>
        <MemoryRouter>
          <LiveProvider>
            <Pusher />
            <Toasts />
          </LiveProvider>
        </MemoryRouter>
      </SWRConfig>,
    )
    fireEvent.click(screen.getByText('error'))
    fireEvent.click(screen.getByText('update'))
    expect(screen.getByText('Could not save.')).toBeInTheDocument()
    expect(screen.getByText('New version')).toBeInTheDocument()

    act(() => vi.advanceTimersByTime(60_000))
    expect(screen.queryByText('Could not save.')).not.toBeInTheDocument()
    expect(screen.getByText('New version')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Reload' }))
    expect(reload).toHaveBeenCalledOnce()
    expect(screen.queryByText('New version')).not.toBeInTheDocument()
  })
})
