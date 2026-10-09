import type { AlertSetting, AlertType, Follow } from './api/types'

export interface SwitchRow {
  alert: AlertType
  setting: AlertSetting
  disabled: boolean
}

/**
 * A follow's switches, drawn from the league's catalog (never a hard-coded list): the entries
 * offered for this follow's kind, in catalog order. A coming-soon alert is disabled and shown
 * off whatever the follow says.
 */
export function switchesFor(catalog: AlertType[], follow: Follow): SwitchRow[] {
  const kind = follow.target.kind
  return catalog
    .filter((alert) => alert.appliesTo.includes(kind))
    .map((alert) => {
      const disabled = alert.availability !== 'available'
      const setting = follow.alerts[alert.type] ?? { enabled: alert.defaults[kind] ?? false }
      return { alert, setting: disabled ? { ...setting, enabled: false } : setting, disabled }
    })
}

/**
 * PATCHes for one follow, one at a time in the order they were made. Each replaces only its own
 * alert's setting, so two quick changes sent at once could land out of order and the older one
 * would win.
 */
export class PatchQueue {
  private tail: Promise<unknown> = Promise.resolve()

  constructor(
    private readonly send: (body: { alerts: Record<string, AlertSetting> }) => Promise<Follow>,
  ) {}

  enqueue(type: string, setting: AlertSetting): Promise<Follow> {
    const run = () => this.send({ alerts: { [type]: setting } })
    // The next one waits for this one to settle, failed or not.
    const result = this.tail.then(run, run)
    this.tail = result.catch(() => {})
    return result
  }
}
