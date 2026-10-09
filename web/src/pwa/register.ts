// The service worker's update flow (spec 8), with the plain Service Worker API.
//
// A deploy ships a new sw.js. The browser installs it next to the running one, and it waits there
// (sw.ts never skips waiting by itself), so a tab never switches code under the user. The page
// decides when: Reload on the toast, or the next in-app navigation.

const CHECK_EVERY_MS = 30 * 60 * 1000

let registration: ServiceWorkerRegistration | null = null
let leaving = false

/** Registers /sw.js and watches for a new version; `onNeedRefresh` when one is waiting. */
export function startUpdates(deps: { onNeedRefresh(): void }): void {
  const container = navigator.serviceWorker
  // Only an update has a controller to replace; the very first install just takes the tab.
  const isUpdate = () => container.controller !== null

  void container.register('/sw.js').then(
    (reg) => {
      registration = reg
      // Installed earlier (by another tab, or before a reload) and still waiting.
      if (reg.waiting && isUpdate()) deps.onNeedRefresh()

      reg.addEventListener('updatefound', () => {
        const worker = reg.installing
        worker?.addEventListener('statechange', () => {
          if (worker.state === 'installed' && isUpdate()) deps.onNeedRefresh()
        })
      })

      // A tab left open for hours rarely checks on its own.
      const check = () => void reg.update().catch(() => {})
      document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible') check()
      })
      setInterval(check, CHECK_EVERY_MS)
    },
    () => {},
  )
}

/** A new version is installed and waiting for this page to let it in. */
export function updateWaiting(): boolean {
  return !!registration?.waiting && navigator.serviceWorker.controller !== null
}

/** Lets the waiting version in, then reloads under it. */
export function applyUpdate(): Promise<void> {
  return activateThen(() => location.reload())
}

/** Lets the waiting version in, then loads `url` under it (a full page load, not a route change). */
export function applyUpdateAndGo(url: string): Promise<void> {
  return activateThen(() => location.assign(url))
}

function activateThen(go: () => void): Promise<void> {
  const waiting = registration?.waiting
  if (!waiting) {
    // Another tab let it in already; this page is the old code, so load fresh anyway.
    go()
    return Promise.resolve()
  }
  return new Promise((resolve) => {
    navigator.serviceWorker.addEventListener('controllerchange', function onChange() {
      navigator.serviceWorker.removeEventListener('controllerchange', onChange)
      if (!leaving) {
        leaving = true
        go()
      }
      resolve()
    })
    waiting.postMessage({ type: 'SKIP_WAITING' })
  })
}
