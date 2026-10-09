import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Follow, Team } from '../api/types'
import { todayIn } from '../dates'
import { follow, gameSummary, user } from '../test/fixtures'
import { renderApp, type Reply, type Routes } from '../test/renderApp'

afterEach(() => {
  vi.unstubAllGlobals()
})

const team = (abbr: string, location: string, name: string): Team => ({
  id: `nfl-${abbr.toLowerCase()}`,
  leagueId: 'NFL',
  abbreviation: abbr,
  location,
  name,
  displayName: `${location} ${name}`,
})

const teams: Team[] = [
  team('CHI', 'Chicago', 'Bears'),
  team('KC', 'Kansas City', 'Chiefs'),
  team('LV', 'Las Vegas', 'Raiders'),
]

const created: Follow = { ...structuredClone(follow), id: 'f_new' }

function openFollowing(routes: Routes = {}) {
  return renderApp('/following', {
    '/follows': { status: 200, body: { items: [] } },
    '/leagues/NFL/teams': { status: 200, body: { items: teams } },
    ...routes,
  })
}

/** POST answers `reply`; GET lists nothing (as if followed in another tab, for the 200). */
function follows(reply: Reply): Routes[string] {
  return (sent) => (sent.method === 'POST' ? reply : { status: 200, body: { items: [] } })
}

async function openDialog() {
  fireEvent.click(await screen.findByRole('button', { name: 'Add a follow' }))
  return screen.findByRole('dialog', { name: 'Find your sideline' })
}

describe('Following', () => {
  it('lists follows with their kind, league, and how many alerts are on', async () => {
    const game: Follow = {
      ...structuredClone(follow),
      id: 'f_game',
      target: { kind: 'game', id: 'nfl-1', displayName: 'Las Vegas Raiders at Chicago Bears' },
      alerts: { TOUCHDOWN: { enabled: true }, CLOSE_LATE: { enabled: false } },
    }
    openFollowing({ '/follows': { status: 200, body: { items: [follow, game] } } })
    const bears = (await screen.findByText('Chicago Bears')).closest('a')!
    expect(bears).toHaveAttribute('href', `/following/${follow.id}`)
    expect(bears).toHaveTextContent('NFL')
    expect(bears).toHaveTextContent(/team follow · 7 alerts on/i)
    const raiders = screen.getByText('Las Vegas Raiders at Chicago Bears').closest('a')!
    expect(raiders).toHaveTextContent(/game follow · 1 alert on/i)
  })

  it('with no follows, says how to start', async () => {
    openFollowing()
    expect(await screen.findByText('Find your people')).toBeInTheDocument()
  })
})

describe('add a follow', () => {
  it('Team → NFL → search → Follow posts the team and opens its switches (201)', async () => {
    const app = openFollowing({ '/follows': follows({ status: 201, body: created }) })
    const dialog = await openDialog()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Teams' }))
    fireEvent.change(within(dialog).getByLabelText('League'), { target: { value: 'NFL' } })
    await within(dialog).findByText('Las Vegas Raiders')
    fireEvent.change(within(dialog).getByLabelText('Search teams, leagues or games'), {
      target: { value: 'chi' },
    })
    expect(within(dialog).getByText('Chicago Bears')).toBeInTheDocument()
    expect(within(dialog).getByText('Kansas City Chiefs')).toBeInTheDocument()
    expect(within(dialog).queryByText('Las Vegas Raiders')).not.toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Follow Chicago Bears' }))
    await waitFor(() => expect(app.location()).toBe('/following/f_new'))
    expect(app.sent('/follows', 'POST').map((s) => s.body)).toEqual([
      { target: { kind: 'team', id: 'nfl-chi' } },
    ])
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('an existing follow (200) opens its switches too', async () => {
    const app = openFollowing({ '/follows': follows({ status: 200, body: follow }) })
    const dialog = await openDialog()
    fireEvent.click(await within(dialog).findByRole('button', { name: 'Follow Chicago Bears' }))
    await waitFor(() => expect(app.location()).toBe(`/following/${follow.id}`))
  })

  it('two fast clicks on Follow send one request (disabled while in flight)', async () => {
    const app = openFollowing({ '/follows': follows({ status: 201, body: created }) })
    const dialog = await openDialog()
    const button = await within(dialog).findByRole('button', { name: 'Follow Chicago Bears' })
    fireEvent.click(button)
    expect(button).toBeDisabled()
    fireEvent.click(button)
    await waitFor(() => expect(app.location()).toBe('/following/f_new'))
    expect(app.sent('/follows', 'POST')).toHaveLength(1)
  })

  it('shows Following on what is already followed', async () => {
    openFollowing({ '/follows': { status: 200, body: { items: [follow] } } })
    const dialog = await openDialog()
    const bears = await within(dialog).findByRole('button', { name: 'Following Chicago Bears' })
    expect(bears).toBeDisabled()
    expect(within(dialog).getByRole('button', { name: 'Follow Kansas City Chiefs' })).toBeEnabled()
  })

  it('League kind lists the league itself', async () => {
    const app = openFollowing({ '/follows': follows({ status: 201, body: created }) })
    const dialog = await openDialog()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Leagues' }))
    fireEvent.click(
      await within(dialog).findByRole('button', { name: 'Follow National Football League' }),
    )
    await waitFor(() => expect(app.sent('/follows', 'POST')).toHaveLength(1))
    expect(app.sent('/follows', 'POST')[0].body).toEqual({ target: { kind: 'league', id: 'NFL' } })
  })

  it('Game kind lists today’s games of that league', async () => {
    const app = openFollowing({
      '/follows': follows({ status: 201, body: created }),
      '/games': { status: 200, body: { date: todayIn(user.timezone), items: [gameSummary] } },
    })
    const dialog = await openDialog()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Games' }))
    const name = 'Las Vegas Raiders at Chicago Bears'
    fireEvent.click(await within(dialog).findByRole('button', { name: `Follow ${name}` }))
    const query = app.sent('/games')[0].query
    expect(query.get('league')).toBe('NFL')
    expect(query.get('date')).toBe(todayIn(user.timezone))
    expect(query.get('tz')).toBe(user.timezone)
    await waitFor(() => expect(app.sent('/follows', 'POST')).toHaveLength(1))
    expect(app.sent('/follows', 'POST')[0].body).toEqual({
      target: { kind: 'game', id: gameSummary.id },
    })
  })

  it('opens from the sidebar’s Explore & follow', async () => {
    openFollowing()
    fireEvent.click(await screen.findByRole('button', { name: 'Explore & follow' }))
    expect(await screen.findByRole('dialog', { name: 'Find your sideline' })).toBeInTheDocument()
  })
})
