import { useEffect } from 'react'
import { SWRConfig } from 'swr'
import { ApiError, fetcher } from './api/client'
import { Toasts } from './components/Toasts'
import { usePaused } from './components/ReconnectBanner'
import type { SocketLike } from './live/connection'
import { LiveProvider, useConnection } from './live/LiveProvider'
import { AppRoutes } from './routes'
import { applyTheme, useTheme } from './theme'

// A 4xx won't change by asking again (and a 401 already went to sign in); 5xx and network may.
const shouldRetryOnError = (err: Error) =>
  !(err instanceof ApiError && err.status >= 400 && err.status < 500)

/** `createSocket` lets tests drive the socket; the browser's WebSocket otherwise. */
export default function App({ createSocket }: { createSocket?: (url: string) => SocketLike }) {
  return (
    <SWRConfig value={{ fetcher, shouldRetryOnError, provider: () => new Map() }}>
      <LiveProvider createSocket={createSocket}>
        <Root />
      </LiveProvider>
    </SWRConfig>
  )
}

function Root() {
  const conn = useConnection()
  const paused = usePaused(conn)
  const theme = useTheme()

  useEffect(() => applyTheme(theme), [theme])

  // `stale` greys live labels and scores while the reconnect banner shows.
  return (
    <div
      className={paused ? 'app stale' : 'app'}
      data-connection={conn.state}
      data-connects={conn.connects}
    >
      <AppRoutes />
      <Toasts />
    </div>
  )
}
