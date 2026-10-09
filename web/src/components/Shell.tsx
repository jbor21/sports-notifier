import {
  Bell,
  ChevronDown,
  Compass,
  LayoutGrid,
  Moon,
  Settings as SettingsIcon,
  Star,
  Sun,
} from 'lucide-react'
import { createContext, useContext, useState, type ReactNode } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router'
import { badge } from '../live/badge'
import { useConnection, type ConnectionInfo } from '../live/LiveProvider'
import { useStore } from '../live/store'
import { useMe } from '../session'
import { setTheme, useTheme } from '../theme'
import { Logo } from './Logo'
import { ReconnectBanner } from './ReconnectBanner'

const AddFollowContext = createContext<() => void>(() => {})

/** Opens the add-a-follow dialog (sidebar's "Explore & follow", Following's "Add a follow"). */
export function useAddFollow(): () => void {
  return useContext(AddFollowContext)
}

const links = [
  { to: '/', label: 'Game day', icon: LayoutGrid },
  { to: '/following', label: 'Following', icon: Star },
  { to: '/notifications', label: 'Notifications', icon: Bell },
]

function screenName(pathname: string): string {
  if (pathname === '/') return 'Game day'
  if (pathname.startsWith('/games/')) return 'Game'
  if (pathname.startsWith('/following')) return 'Following'
  if (pathname.startsWith('/notifications')) return 'Notifications'
  if (pathname.startsWith('/settings')) return 'Settings'
  return 'Your sideline'
}

function connectionLabel({ state, connects }: ConnectionInfo): string {
  if (state === 'open') return 'Connected'
  if (state === 'waiting') return 'Reconnecting'
  if (state === 'connecting') return connects > 0 ? 'Reconnecting' : 'Connecting'
  return 'Offline'
}

const navClass = ({ isActive }: { isActive: boolean }) =>
  isActive ? 'nav-link active' : 'nav-link'

/** v0's frame: sidebar on desktop, bottom tabs on a phone, top bar with the bell. */
export function Shell({ addFollow }: { addFollow: (close: () => void) => ReactNode }) {
  const me = useMe()
  const unread = useStore(badge)
  const conn = useConnection()
  const theme = useTheme()
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const [adding, setAdding] = useState(false)

  const username = me.data?.username ?? ''
  const initial = (username || 'S').slice(0, 1).toUpperCase()
  const dark = theme === 'dark'

  return (
    <AddFollowContext.Provider value={() => setAdding(true)}>
      <aside className="sidebar">
        <Logo />
        <span className="sidebar-label">YOUR PLAYBOOK</span>
        <nav>
          {links.map(({ to, label, icon: Icon }) => (
            <NavLink key={to} to={to} end={to === '/'} className={navClass}>
              <Icon size={19} />
              <span>{label}</span>
              {to === '/notifications' && unread > 0 && (
                <b className="unread" data-testid="badge">
                  {unread}
                </b>
              )}
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-divider" />
        <span className="sidebar-label">FIND YOUR GAME</span>
        <button className="nav-link" onClick={() => setAdding(true)}>
          <Compass size={19} />
          <span>Explore &amp; follow</span>
        </button>
        <div className="sidebar-bottom">
          <div className="connection-status">
            <span className={conn.state === 'open' ? 'live-dot' : 'live-dot offline'} />
            {connectionLabel(conn)}
            <span className="signal-bars" aria-hidden="true">
              ▂▄▆
            </span>
          </div>
          <NavLink to="/settings" className={navClass}>
            <SettingsIcon size={18} />
            <span>Settings</span>
          </NavLink>
          <div className="sidebar-account">
            <span className="avatar">{initial}</span>
            <div>
              <strong>{username}</strong>
              <small>Your personal sideline</small>
            </div>
            <button
              aria-label="Account settings"
              className="icon-button"
              onClick={() => navigate('/settings')}
            >
              <ChevronDown size={15} />
            </button>
          </div>
        </div>
      </aside>

      <div className="app-body">
        <header className="topbar">
          <div className="breadcrumb">
            <span>Overview</span>
            <ChevronDown size={12} />
            <span>{screenName(pathname)}</span>
          </div>
          <div className="topbar-actions">
            <button
              className="icon-button"
              aria-label={dark ? 'Switch to light mode' : 'Switch to dark mode'}
              onClick={() => setTheme(dark ? 'light' : 'dark')}
            >
              {dark ? <Sun size={18} /> : <Moon size={18} />}
            </button>
            <span className="topbar-divider" />
            <button
              className="icon-button topbar-bell"
              aria-label={`Notifications, ${unread} unread`}
              onClick={() => navigate('/notifications')}
            >
              <Bell size={18} />
              {unread > 0 && (
                <span className="bell-count" data-testid="badge">
                  {unread}
                </span>
              )}
            </button>
            <button
              className="avatar small"
              aria-label="Account"
              onClick={() => navigate('/settings')}
            >
              {initial}
            </button>
          </div>
        </header>

        <main className="main-content">
          <ReconnectBanner {...conn} />
          <Outlet />
        </main>

        <nav className="mobile-nav">
          {[...links, { to: '/settings', label: 'Settings', icon: SettingsIcon }].map(
            ({ to, label, icon: Icon }) => (
              <NavLink
                key={to}
                to={to}
                end={to === '/'}
                className={({ isActive }) => (isActive ? 'active' : '')}
              >
                <Icon size={20} />
                {label}
              </NavLink>
            ),
          )}
        </nav>
      </div>

      {adding && addFollow(() => setAdding(false))}
    </AddFollowContext.Provider>
  )
}
