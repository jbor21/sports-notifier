/// <reference lib="webworker" />
// The service worker. Built by vite-plugin-pwa (injectManifest) into dist/sw.js, with the list of
// built files injected at self.__WB_MANIFEST. Type-checked on its own (tsconfig.sw.json): the
// worker scope's types clash with the DOM's.
import { createHandlerBoundToURL, precacheAndRoute, type PrecacheEntry } from 'workbox-precaching'
import { NavigationRoute, registerRoute } from 'workbox-routing'

declare const self: ServiceWorkerGlobalScope & { __WB_MANIFEST: Array<PrecacheEntry | string> }

precacheAndRoute(self.__WB_MANIFEST)

// Every app route is the same index.html; the API (and the socket under it) and /health never go
// through the cache.
registerRoute(
  new NavigationRoute(createHandlerBoundToURL('/index.html'), {
    denylist: [/^\/api\//, /^\/health$/],
  }),
)

// A new version waits until the page says so (the "New version" toast, or the next in-app link).
self.addEventListener('message', (event) => {
  if ((event.data as { type?: unknown } | null)?.type === 'SKIP_WAITING') void self.skipWaiting()
})

// The first install takes the open tab at once, so it works offline without a second load.
// Later versions only activate through SKIP_WAITING above.
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()))
