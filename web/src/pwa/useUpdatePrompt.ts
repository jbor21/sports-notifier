import { useEffect, useRef } from 'react'
import { useLocation } from 'react-router'
import { useToasts } from '../live/LiveProvider'
import { applyUpdate, applyUpdateAndGo, startUpdates, updateWaiting } from './register'

const TOAST_ID = 'sw-update'
let started = false

/**
 * "New version — Reload" when a deploy's service worker is waiting; left unanswered, the next
 * in-app navigation loads its target under the new version instead. Builds only: `vite` dev and
 * the unit tests never register a worker. Lives at the app root, so sign in has it too.
 */
export function useUpdatePrompt(
  enabled = import.meta.env.PROD && 'serviceWorker' in navigator,
): void {
  const { toasts, push } = useToasts()
  const location = useLocation()
  const latest = useRef({ push, unanswered: false })
  latest.current = { push, unanswered: toasts.some((t) => t.id === TOAST_ID) }

  useEffect(() => {
    if (!enabled || started) return
    started = true
    startUpdates({
      onNeedRefresh: () =>
        latest.current.push({
          id: TOAST_ID,
          kind: 'update',
          text: 'New version',
          action: { label: 'Reload', run: () => void applyUpdate() },
        }),
    })
  }, [enabled])

  const seen = useRef(location.key)
  const target = location.pathname + location.search
  useEffect(() => {
    if (!enabled || seen.current === location.key) return
    seen.current = location.key
    // The new route has rendered under the old code; load it again under the new one.
    if (latest.current.unanswered && updateWaiting()) void applyUpdateAndGo(target)
  }, [enabled, location.key, target])
}
