import { Navigate, Outlet, Route, Routes, useLocation } from 'react-router'
import { LoadState } from './components/LoadState'
import { Shell } from './components/Shell'
import { AddFollow } from './screens/AddFollow'
import { FollowSettings } from './screens/FollowSettings'
import { Following } from './screens/Following'
import { GameDay } from './screens/GameDay'
import { GamePage } from './screens/GamePage'
import { Notifications } from './screens/Notifications'
import { Settings } from './screens/Settings'
import { SignIn } from './screens/SignIn'
import { SignUp } from './screens/SignUp'
import { signInPath, useMe } from './session'

export function AppRoutes() {
  return (
    <Routes>
      <Route element={<PublicLayout />}>
        <Route path="/signin" element={<SignIn />} />
        <Route path="/signup" element={<SignUp />} />
      </Route>
      <Route element={<RequireSignIn />}>
        <Route element={<Shell addFollow={(close) => <AddFollow onClose={close} />} />}>
          <Route index element={<GameDay />} />
          <Route path="/games/:id" element={<GamePage />} />
          <Route path="/following" element={<Following />} />
          <Route path="/following/:id" element={<FollowSettings />} />
          <Route path="/notifications" element={<Notifications />} />
          <Route path="/settings" element={<Settings />} />
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
