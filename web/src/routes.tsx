import { Navigate, Outlet, Route, Routes, useLocation } from 'react-router'
import { LoadState } from './components/LoadState'
import { Modal } from './components/Modal'
import { Shell } from './components/Shell'
import { signInPath, useMe } from './session'

// Placeholders until the screens land (Tasks 7 and 8): swap each for its screen's import.
function Placeholder({ title }: { title: string }) {
  return (
    <div className="page-heading">
      <div>
        <h1>{title}</h1>
      </div>
    </div>
  )
}

function AddFollowPlaceholder({ onClose }: { onClose: () => void }) {
  return (
    <Modal title="Add a follow" onClose={onClose}>
      <p>Follow a team, a league, or a game.</p>
    </Modal>
  )
}

export function AppRoutes() {
  return (
    <Routes>
      <Route element={<PublicLayout />}>
        <Route path="/signin" element={<Placeholder title="Sign in" />} />
        <Route path="/signup" element={<Placeholder title="Create an account" />} />
      </Route>
      <Route element={<RequireSignIn />}>
        <Route element={<Shell addFollow={(close) => <AddFollowPlaceholder onClose={close} />} />}>
          <Route index element={<Placeholder title="Game day" />} />
          <Route path="/games/:id" element={<Placeholder title="Game" />} />
          <Route path="/following" element={<Placeholder title="Following" />} />
          <Route path="/following/:id" element={<Placeholder title="Alert switches" />} />
          <Route path="/notifications" element={<Placeholder title="Notifications" />} />
          <Route path="/settings" element={<Placeholder title="Settings" />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

/** Sign in and sign up: no sidebar, since every link in it needs a session. */
function PublicLayout() {
  return (
    <main className="auth-page">
      <Outlet />
    </main>
  )
}

/** Every other screen needs a session: GET /me answering 401 means sign in first. */
function RequireSignIn() {
  const me = useMe()
  const location = useLocation()
  if (me.data && me.error?.status !== 401) return <Outlet />
  if (me.error && me.error.status !== 401)
    return (
      <main className="backend-error">
        <h1>Let&apos;s get connected.</h1>
        <p>We couldn&apos;t load your account.</p>
        <LoadState error={me.error} retry={() => void me.mutate()} />
      </main>
    )
  if (me.isLoading)
    return (
      <main className="main-content">
        <LoadState loading />
      </main>
    )
  return <Navigate to={signInPath(location.pathname + location.search)} replace />
}
