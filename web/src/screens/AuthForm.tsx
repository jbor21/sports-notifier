import { Bell, Radio, ShieldCheck } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { useSWRConfig } from 'swr'
import { ApiError, request } from '../api/client'
import type { User } from '../api/types'
import { Logo } from '../components/Logo'
import { forgetUserData, isUserKey } from '../session'
import { useSecondsLeft } from '../useSecondsLeft'

// The same rules as openapi.yaml's Credentials, so a bad value never costs a request.
const USERNAME = /^[a-z0-9_]{3,20}$/
const USERNAME_HINT = 'Use 3 to 20 lowercase letters, numbers, or _.'
const PASSWORD_HINT = 'Password must be 8 to 72 bytes in UTF-8.'

/** bcrypt ignores bytes past 72, so the API counts UTF-8 bytes, not characters. */
export function passwordBytes(password: string): number {
  return new TextEncoder().encode(password).length
}

/** `next` only when it is a path in this app; anything else (`//evil.example`) goes to Game day. */
function safeNext(next: string | null): string {
  if (!next || !next.startsWith('/') || next.startsWith('//') || next.startsWith('/\\')) return '/'
  return next
}

/** Whole seconds left until `until` (a Date.now() time), redrawn every second while above 0. */

type Field = 'username' | 'password'

/** v0's two-panel sign-in and sign-up. */
export function AuthForm({ mode }: { mode: 'signin' | 'signup' }) {
  const signup = mode === 'signup'
  const [params] = useSearchParams()
  const next = params.get('next')
  const navigate = useNavigate()
  const { mutate, cache } = useSWRConfig()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<Field, string>>>({})
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [retryUntil, setRetryUntil] = useState(0)
  const wait = useSecondsLeft(retryUntil)

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    const errors: Partial<Record<Field, string>> = {}
    if (!USERNAME.test(username)) errors.username = USERNAME_HINT
    const bytes = passwordBytes(password)
    if (bytes < 8 || bytes > 72) errors.password = PASSWORD_HINT
    setFieldErrors(errors)
    setError('')
    if (errors.username || errors.password) return

    setBusy(true)
    try {
      const user = await request<User>(signup ? '/auth/signup' : '/auth/login', {
        method: 'POST',
        body: signup
          ? { username, password, timezone: Intl.DateTimeFormat().resolvedOptions().timeZone }
          : { username, password },
      })
      // Whoever was signed in before (if anyone) leaves nothing behind for this user to see. mutate
      // also drops SWR's "fetched just now" record, so the new user's screens really ask again;
      // forgetUserData then removes the paged lists, which a mutate filter doesn't reach.
      await mutate(isUserKey, undefined)
      forgetUserData(cache)
      // A sign-out leaves /me cleared without asking again, so the new user goes in directly.
      await mutate('/me', user, { revalidate: false })
      navigate(safeNext(next), { replace: true })
    } catch (err) {
      if (!(err instanceof ApiError)) throw err
      const field = (['username', 'password'] as const).find((f) => f in err.fields)
      if (field) setFieldErrors({ [field]: err.message })
      else setError(err.message)
      if (err.status === 429 && err.retryAfter > 0)
        setRetryUntil(Date.now() + err.retryAfter * 1000)
    } finally {
      setBusy(false)
    }
  }

  const other = signup ? '/signin' : '/signup'
  const otherPath = next ? `${other}?next=${encodeURIComponent(next)}` : other
  const submitLabel = busy
    ? 'Please wait…'
    : wait > 0
      ? `Try again in ${wait} s`
      : signup
        ? 'Create account'
        : 'Sign in'

  return (
    <div className="auth-layout">
      <div className="auth-story">
        <Logo />
        <span className="eyebrow">THE BEST SEAT IS YOURS.</span>
        <h1>
          Your teams.
          <br />
          Every moment.
        </h1>
        <p>
          From the opening whistle to the final buzzer.
          <br />
          Stay close to the games you love.
        </p>
        <div className="auth-features">
          <span>
            <Radio /> Live scores, without the refresh
          </span>
          <span>
            <Bell /> Just the alerts you want
          </span>
          <span>
            <ShieldCheck /> Your own front row, everywhere
          </span>
        </div>
      </div>
      <div className="auth-form">
        <h2>{signup ? 'Join the sideline.' : 'Welcome back.'}</h2>
        <p>
          {signup
            ? 'Make every game a little more yours.'
            : 'Your next great sports moment is waiting.'}
        </p>
        <form onSubmit={(e) => void submit(e)} noValidate>
          <FieldRow
            id="auth-username"
            label="Username"
            error={fieldErrors.username}
            input={{
              value: username,
              onChange: setUsername,
              autoComplete: 'username',
              placeholder: 'your_username',
              type: 'text',
            }}
          />
          <FieldRow
            id="auth-password"
            label="Password"
            error={fieldErrors.password}
            input={{
              value: password,
              onChange: setPassword,
              autoComplete: signup ? 'new-password' : 'current-password',
              placeholder: 'At least 8 bytes',
              type: 'password',
            }}
          />
          {error && (
            <p role="alert" className="error-text">
              {error}
            </p>
          )}
          <button className="primary auth-submit" disabled={busy || wait > 0}>
            {submitLabel}
          </button>
        </form>
        <p>
          {signup ? 'Already part of the team?' : 'New around here?'}{' '}
          <Link className="text-button" to={otherPath}>
            {signup ? 'Sign in' : 'Create an account'}
          </Link>
        </p>
      </div>
    </div>
  )
}

function FieldRow({
  id,
  label,
  error,
  input,
}: {
  id: string
  label: string
  error?: string
  input: {
    value: string
    onChange: (value: string) => void
    autoComplete: string
    placeholder: string
    type: 'text' | 'password'
  }
}) {
  return (
    <div className="field-label">
      <label htmlFor={id}>{label}</label>
      <input
        id={id}
        name={id}
        type={input.type}
        value={input.value}
        onChange={(e) => input.onChange(e.target.value)}
        autoComplete={input.autoComplete}
        placeholder={input.placeholder}
        autoCapitalize="none"
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${id}-error` : undefined}
      />
      {error && (
        <small id={`${id}-error`} className="error-text">
          {error}
        </small>
      )}
    </div>
  )
}
