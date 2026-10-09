import { describe, expect, it, vi } from 'vitest'
import type { AlertSetting, AlertType, Follow } from './api/types'
import { PatchQueue, switchesFor } from './follows'
import { follow, nflCatalog } from './test/fixtures'

const dailySummary: AlertType = {
  type: 'DAILY_SUMMARY',
  name: 'Daily summary',
  description: 'One message a day with every result.',
  category: 'summary',
  appliesTo: ['league'],
  defaults: { league: true },
  precision: 'exact',
  availability: 'available',
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (err: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

describe('switchesFor', () => {
  it('gives one row per catalog entry for the follow’s kind, in catalog order', () => {
    const rows = switchesFor(nflCatalog, follow)
    expect(rows.map((r) => r.alert.type)).toEqual(['TOUCHDOWN', 'CLOSE_LATE', 'RED_ZONE'])
    expect(rows[0]).toMatchObject({ setting: { enabled: true }, disabled: false })
    expect(rows[1]).toMatchObject({ setting: { enabled: true }, disabled: false })
  })

  it('a coming-soon alert is disabled and shown off', () => {
    const on: Follow = { ...follow, alerts: { ...follow.alerts, RED_ZONE: { enabled: true } } }
    const red = switchesFor(nflCatalog, on).find((r) => r.alert.type === 'RED_ZONE')!
    expect(red.disabled).toBe(true)
    expect(red.setting.enabled).toBe(false)
  })

  it('a missing setting takes the catalog default for this kind', () => {
    const bare: Follow = { ...follow, alerts: {} }
    expect(switchesFor(nflCatalog, bare).map((r) => r.setting.enabled)).toEqual([true, true, false])
    const game: Follow = { ...bare, target: { ...bare.target, kind: 'game' } }
    const noDefaults = nflCatalog.map((a) => ({ ...a, defaults: {} }))
    expect(switchesFor(noDefaults, game).map((r) => r.setting.enabled)).toEqual([
      false,
      false,
      false,
    ])
  })

  it('leaves out an alert this kind does not offer', () => {
    const catalog = [...nflCatalog, dailySummary]
    expect(switchesFor(catalog, follow).map((r) => r.alert.type)).not.toContain('DAILY_SUMMARY')
    const league: Follow = { ...follow, target: { kind: 'league', id: 'NFL', displayName: 'NFL' } }
    expect(switchesFor(catalog, league).map((r) => r.alert.type)).toContain('DAILY_SUMMARY')
  })
})

describe('PatchQueue', () => {
  it('sends one request at a time, in order, each with only its own type', async () => {
    const first = deferred<Follow>()
    const second = deferred<Follow>()
    const send = vi
      .fn<(body: { alerts: Record<string, AlertSetting> }) => Promise<Follow>>()
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise)
    const queue = new PatchQueue(send)

    const a = queue.enqueue('TOUCHDOWN', { enabled: false })
    const b = queue.enqueue('CLOSE_LATE', { enabled: false })
    await Promise.resolve()
    expect(send).toHaveBeenCalledTimes(1)
    expect(send).toHaveBeenLastCalledWith({ alerts: { TOUCHDOWN: { enabled: false } } })

    first.resolve(follow)
    await expect(a).resolves.toBe(follow)
    await vi.waitFor(() => expect(send).toHaveBeenCalledTimes(2))
    expect(send).toHaveBeenLastCalledWith({ alerts: { CLOSE_LATE: { enabled: false } } })
    second.resolve(follow)
    await expect(b).resolves.toBe(follow)
  })

  it('a failed request rejects its own promise and does not stop the next', async () => {
    const send = vi
      .fn<(body: { alerts: Record<string, AlertSetting> }) => Promise<Follow>>()
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce(follow)
    const queue = new PatchQueue(send)
    const a = queue.enqueue('TOUCHDOWN', { enabled: false })
    const b = queue.enqueue('CLOSE_LATE', { enabled: true })
    await expect(a).rejects.toThrow('boom')
    await expect(b).resolves.toBe(follow)
    expect(send).toHaveBeenCalledTimes(2)
  })
})
