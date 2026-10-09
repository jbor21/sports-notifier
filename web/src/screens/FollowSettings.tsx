import { Bell, ChevronLeft, Trash2 } from 'lucide-react'
import { useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import useSWR, { useSWRConfig } from 'swr'
import { request, type ApiError } from '../api/client'
import type { AlertOption, AlertSetting, AlertType, Follow } from '../api/types'
import { Empty } from '../components/Empty'
import { LoadState } from '../components/LoadState'
import { Modal } from '../components/Modal'
import { Toggle } from '../components/Toggle'
import { PatchQueue, switchesFor, type SwitchRow } from '../follows'
import { useToasts } from '../live/LiveProvider'

const INNINGS = Array.from({ length: 9 }, (_, i) => i + 1)

function BackLink() {
  return (
    <Link className="back-link" to="/following">
      <ChevronLeft size={16} /> Your follows
    </Link>
  )
}

/** One follow's switches: each change is sent at once, alone, and rolled back if it fails. */
export function FollowSettings() {
  const { id = '' } = useParams()
  const path = `/follows/${encodeURIComponent(id)}`
  const follow = useSWR<Follow, ApiError>(path)
  const leagueId = follow.data?.leagueId
  const catalog = useSWR<{ items: AlertType[] }, ApiError>(
    leagueId ? `/leagues/${encodeURIComponent(leagueId)}/alerts` : null,
  )
  const { push } = useToasts()
  // Changes not yet answered, by type, drawn over the server's copy until their PATCH settles.
  const [pending, setPending] = useState<Record<string, AlertSetting>>({})
  const queue = useMemo(
    () => new PatchQueue((body) => request<Follow>(path, { method: 'PATCH', body })),
    [path],
  )

  function change(type: string, setting: AlertSetting) {
    setPending((cur) => ({ ...cur, [type]: setting }))
    // Only this change's own override is dropped: a newer one for the same type stays shown.
    const settle = () =>
      setPending((cur) => {
        if (cur[type] !== setting) return cur
        const { [type]: _, ...rest } = cur
        return rest
      })
    queue.enqueue(type, setting).then(
      (updated) => {
        void follow.mutate(updated, { revalidate: false })
        settle()
      },
      (err: unknown) => {
        settle()
        const text = err instanceof Error ? err.message : 'Could not change that alert.'
        push({ id: `patch-${id}-${type}`, kind: 'error', text })
      },
    )
  }

  if (follow.error?.status === 404)
    return (
      <>
        <BackLink />
        <Empty title="Follow not found">It may have been removed, or it expired.</Empty>
      </>
    )
  if (!follow.data)
    return (
      <>
        <BackLink />
        <LoadState
          error={follow.error}
          loading={follow.isLoading}
          retry={() => void follow.mutate()}
        />
      </>
    )

  const f = follow.data
  const shown: Follow = { ...f, alerts: { ...f.alerts, ...pending } }
  const rows = catalog.data ? switchesFor(catalog.data.items, shown) : []

  return (
    <>
      <BackLink />
      <div className="page-heading">
        <div>
          <div className="eyebrow">
            {f.leagueId} · {f.target.kind.toUpperCase()} ALERTS
          </div>
          <h1>{f.target.displayName}</h1>
          <p>You call the shots. Choose what brings you back to the game.</p>
        </div>
        <UnfollowButton follow={f} />
      </div>
      <section className="panel alert-settings">
        {!catalog.data && (
          <LoadState
            error={catalog.error}
            loading={catalog.isLoading}
            retry={() => void catalog.mutate()}
          />
        )}
        {rows.map((row) => (
          <AlertRow key={row.alert.type} row={row} onChange={(s) => change(row.alert.type, s)} />
        ))}
      </section>
    </>
  )
}

function AlertRow({
  row: { alert, setting, disabled },
  onChange,
}: {
  row: SwitchRow
  onChange: (setting: AlertSetting) => void
}) {
  const inningSets = (alert.options ?? []).filter((o) => o.kind === 'inning_set')
  return (
    <div className="alert-setting">
      <div className="setting-main">
        <span className="setting-icon">
          <Bell size={19} />
        </span>
        <div>
          <h3>
            {alert.name} {disabled && <span className="soon">Coming soon</span>}
          </h3>
          <p>{alert.description}</p>
          {alert.precision === 'from_snapshots' && (
            <small>Based on score snapshots; some moments may be missed.</small>
          )}
        </div>
        <Toggle
          label={alert.name}
          disabled={disabled}
          checked={setting.enabled}
          onChange={(enabled) => onChange({ ...setting, enabled })}
        />
      </div>
      {setting.enabled &&
        !disabled &&
        inningSets.map((option) => (
          <InningSet key={option.key} option={option} setting={setting} onChange={onChange} />
        ))}
    </div>
  )
}

function InningSet({
  option,
  setting,
  onChange,
}: {
  option: AlertOption
  setting: AlertSetting
  onChange: (setting: AlertSetting) => void
}) {
  const value = setting.options?.[option.key]
  const chosen = Array.isArray(value) ? (value as number[]) : []
  const toggle = (n: number) => {
    const next = chosen.includes(n) ? chosen.filter((v) => v !== n) : [...chosen, n]
    onChange({
      ...setting,
      options: { ...setting.options, [option.key]: next.sort((a, b) => a - b) },
    })
  }
  return (
    <fieldset className="innings">
      <legend>
        {option.label || 'Which innings'} <small>(none chosen means every inning)</small>
      </legend>
      {INNINGS.map((n) => (
        <label key={n}>
          <input
            type="checkbox"
            aria-label={`Inning ${n}`}
            checked={chosen.includes(n)}
            onChange={() => toggle(n)}
          />
          {n}
        </label>
      ))}
    </fieldset>
  )
}

function UnfollowButton({ follow }: { follow: Follow }) {
  const navigate = useNavigate()
  const { mutate } = useSWRConfig()
  const { push } = useToasts()
  const [asking, setAsking] = useState(false)
  const [busy, setBusy] = useState(false)

  async function unfollow() {
    setBusy(true)
    try {
      await request(`/follows/${encodeURIComponent(follow.id)}`, { method: 'DELETE' })
      await mutate<{ items: Follow[] }>(
        '/follows',
        (cur) => cur && { items: cur.items.filter((f) => f.id !== follow.id) },
        { revalidate: false },
      )
      navigate('/following')
    } catch (err) {
      setBusy(false)
      const text = err instanceof Error ? err.message : 'Could not unfollow.'
      push({ id: `unfollow-${follow.id}`, kind: 'error', text })
    }
  }

  return (
    <>
      <button className="secondary danger" onClick={() => setAsking(true)}>
        <Trash2 size={16} /> Unfollow
      </button>
      {asking && (
        <Modal title="Unfollow this favorite?" onClose={() => setAsking(false)}>
          <p>You will stop receiving alerts from this follow.</p>
          <div className="modal-actions">
            <button className="secondary" onClick={() => setAsking(false)}>
              Keep following
            </button>
            <button className="primary danger-bg" disabled={busy} onClick={() => void unfollow()}>
              Unfollow
            </button>
          </div>
        </Modal>
      )}
    </>
  )
}
