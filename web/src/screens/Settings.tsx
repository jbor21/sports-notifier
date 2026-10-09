import { Check, LogOut } from 'lucide-react'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { useSWRConfig } from 'swr'
import { request } from '../api/client'
import type { User } from '../api/types'
import { Toggle } from '../components/Toggle'
import { useToasts } from '../live/LiveProvider'
import { forgetUserData, useMe } from '../session'
import { setTheme, useTheme } from '../theme'

/** The user's zone first, then every zone the browser knows. */
function zonesWith(current: string): string[] {
  return [...new Set([current, 'UTC', ...Intl.supportedValuesOf('timeZone')])]
}

export function Settings() {
  const me = useMe()
  const navigate = useNavigate()
  const { cache, mutate } = useSWRConfig()
  const { push } = useToasts()
  const theme = useTheme()
  const saved = me.data?.timezone ?? 'UTC'
  const [timezone, setTimezone] = useState(saved)
  const [done, setDone] = useState(false)
  const [busy, setBusy] = useState(false)
  const signedOut = useRef(false)

  // /me is forgotten only once this screen has gone: navigations render as transitions, so
  // clearing it first would let the sign-in guard redirect ahead of us, with ?next=/settings.
  useEffect(
    () => () => {
      if (signedOut.current) void mutate('/me', undefined, { revalidate: false })
    },
    [mutate],
  )

  async function save(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    setBusy(true)
    try {
      const user = await request<User>('/me', { method: 'PATCH', body: { timezone } })
      await mutate('/me', user, { revalidate: false })
      setDone(true)
    } catch (err) {
      const text = err instanceof Error ? err.message : 'Could not save your timezone.'
      push({ id: 'settings-timezone', kind: 'error', text })
    } finally {
      setBusy(false)
    }
  }

  async function signOut() {
    setBusy(true)
    try {
      await request('/auth/logout', { method: 'POST' })
    } catch (err) {
      setBusy(false)
      const text = err instanceof Error ? err.message : 'Could not sign out.'
      push({ id: 'settings-signout', kind: 'error', text })
      return
    }
    forgetUserData(cache)
    signedOut.current = true
    navigate('/signin', { replace: true })
  }

  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">MAKE YOURSELF AT HOME</div>
          <h1>
            Settings<span>.</span>
          </h1>
          <p>A few little things to make Sideline yours.</p>
        </div>
      </div>

      <section className="panel settings-panel">
        <h2>Account</h2>
        <div className="settings-row">
          <div>
            <h3>Username</h3>
            <p>{me.data?.username}</p>
          </div>
          <button className="secondary" disabled={busy} onClick={() => void signOut()}>
            <LogOut size={15} /> Sign out
          </button>
        </div>
        <form onSubmit={(e) => void save(e)}>
          <label className="field-label">
            Your timezone
            <select
              value={timezone}
              onChange={(e) => {
                setTimezone(e.target.value)
                setDone(false)
              }}
            >
              {zonesWith(saved).map((z) => (
                <option key={z} value={z}>
                  {z}
                </option>
              ))}
            </select>
          </label>
          <p className="muted">Game times and your daily schedule use this timezone.</p>
          <button disabled={busy} className="primary">
            {done ? (
              <>
                <Check size={16} /> Saved
              </>
            ) : (
              'Save changes'
            )}
          </button>
        </form>
      </section>

      <section className="panel settings-panel">
        <h2>Appearance</h2>
        <div className="settings-row">
          <div>
            <h3>Dark mode</h3>
            <p>Easier on the eyes for night games.</p>
          </div>
          <Toggle
            label="Dark mode"
            checked={theme === 'dark'}
            onChange={(dark) => setTheme(dark ? 'dark' : 'light')}
          />
        </div>
      </section>

      <section className="panel settings-panel">
        <h2>Sideline, on your home screen</h2>
        <p className="muted">
          Install from your browser menu using “Install app” or “Add to Home Screen”. Live alerts
          arrive while Sideline is open; background push comes in a later version.
        </p>
        <p className="muted">Version {import.meta.env.VITE_BUILD_ID ?? 'dev'}</p>
      </section>
    </>
  )
}
