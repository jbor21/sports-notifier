import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

type Register = typeof import('./register')

/** A worker: its state changes, and it takes messages. */
class FakeWorker extends EventTarget {
  state = 'installing'
  postMessage = vi.fn()

  become(state: string) {
    this.state = state
    this.dispatchEvent(new Event('statechange'))
  }
}

class FakeRegistration extends EventTarget {
  installing: FakeWorker | null = null
  waiting: FakeWorker | null = null
  update = vi.fn(() => Promise.resolve())

  /** The browser found a new sw.js: it installs, then waits behind the current one. */
  install(): FakeWorker {
    const worker = new FakeWorker()
    this.installing = worker
    this.dispatchEvent(new Event('updatefound'))
    this.installing = null
    this.waiting = worker
    worker.become('installed')
    return worker
  }
}

class FakeContainer extends EventTarget {
  controller: object | null = {}
  registration = new FakeRegistration()
  register = vi.fn(() => Promise.resolve(this.registration))
}

let container: FakeContainer
let visibility: DocumentVisibilityState
let nav: { reload: ReturnType<typeof vi.fn>; assign: ReturnType<typeof vi.fn> }

// Module state (the registration) is per import; each test gets a fresh one.
async function load(): Promise<Register> {
  vi.resetModules()
  return import('./register')
}

async function started(onNeedRefresh = vi.fn()): Promise<Register> {
  const pwa = await load()
  pwa.startUpdates({ onNeedRefresh })
  // register() resolves on a later microtask; let its .then run.
  for (let i = 0; i < 5; i++) await Promise.resolve()
  return pwa
}

beforeEach(() => {
  container = new FakeContainer()
  Object.defineProperty(navigator, 'serviceWorker', { configurable: true, value: container })
  visibility = 'visible'
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    get: () => visibility,
  })
  nav = { reload: vi.fn(), assign: vi.fn() }
  vi.stubGlobal('location', nav)
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  Reflect.deleteProperty(navigator, 'serviceWorker')
})

describe('startUpdates', () => {
  it('registers /sw.js', async () => {
    await started()
    expect(container.register).toHaveBeenCalledWith('/sw.js')
  })

  it('checksOnVisibleAndEvery30Min', async () => {
    vi.useFakeTimers()
    await started()
    const reg = container.registration
    expect(reg.update).not.toHaveBeenCalled()

    visibility = 'hidden'
    document.dispatchEvent(new Event('visibilitychange'))
    expect(reg.update).not.toHaveBeenCalled()

    visibility = 'visible'
    document.dispatchEvent(new Event('visibilitychange'))
    expect(reg.update).toHaveBeenCalledTimes(1)

    vi.advanceTimersByTime(30 * 60 * 1000 - 1)
    expect(reg.update).toHaveBeenCalledTimes(1)
    vi.advanceTimersByTime(1)
    expect(reg.update).toHaveBeenCalledTimes(2)
    vi.advanceTimersByTime(30 * 60 * 1000)
    expect(reg.update).toHaveBeenCalledTimes(3)
  })

  it('a failed check is ignored (offline)', async () => {
    vi.useFakeTimers()
    await started()
    container.registration.update.mockImplementation(() => Promise.reject(new Error('offline')))
    document.dispatchEvent(new Event('visibilitychange'))
    await Promise.resolve()
    expect(container.registration.update).toHaveBeenCalledTimes(1)
  })

  it('waitingWorkerCallsNeedRefresh', async () => {
    const onNeedRefresh = vi.fn()
    const pwa = await started(onNeedRefresh)
    expect(pwa.updateWaiting()).toBe(false)

    container.registration.install()
    expect(onNeedRefresh).toHaveBeenCalledTimes(1)
    expect(pwa.updateWaiting()).toBe(true)
  })

  it('a worker already waiting at start (installed by another tab) calls need refresh', async () => {
    container.registration.waiting = new FakeWorker()
    container.registration.waiting.state = 'installed'
    const onNeedRefresh = vi.fn()
    const pwa = await started(onNeedRefresh)
    expect(onNeedRefresh).toHaveBeenCalledTimes(1)
    expect(pwa.updateWaiting()).toBe(true)
  })

  it('the first install (no controller yet) is not an update', async () => {
    container.controller = null
    const onNeedRefresh = vi.fn()
    const pwa = await started(onNeedRefresh)
    container.registration.install()
    expect(onNeedRefresh).not.toHaveBeenCalled()
    expect(pwa.updateWaiting()).toBe(false)
  })
})

describe('applying an update', () => {
  it('applyUpdatePostsSkipWaiting', async () => {
    const pwa = await started()
    const worker = container.registration.install()

    const done = pwa.applyUpdate()
    expect(worker.postMessage).toHaveBeenCalledWith({ type: 'SKIP_WAITING' })
    expect(nav.reload).not.toHaveBeenCalled()

    container.dispatchEvent(new Event('controllerchange'))
    await done
    expect(nav.reload).toHaveBeenCalledTimes(1)
    expect(nav.assign).not.toHaveBeenCalled()
  })

  it('applyUpdateAndGoAssignsUrl', async () => {
    const pwa = await started()
    const worker = container.registration.install()

    const done = pwa.applyUpdateAndGo('/signup?x=1')
    expect(worker.postMessage).toHaveBeenCalledWith({ type: 'SKIP_WAITING' })
    expect(nav.assign).not.toHaveBeenCalled()

    container.dispatchEvent(new Event('controllerchange'))
    await done
    expect(nav.assign).toHaveBeenCalledWith('/signup?x=1')
    expect(nav.reload).not.toHaveBeenCalled()
  })

  it('a controller change nobody asked for (the first install claiming the tab) does not reload', async () => {
    container.controller = null
    await started()
    container.dispatchEvent(new Event('controllerchange'))
    expect(nav.reload).not.toHaveBeenCalled()
    expect(nav.assign).not.toHaveBeenCalled()
  })

  it('with nothing waiting any more (another tab took it), the page still reloads', async () => {
    const pwa = await started()
    await pwa.applyUpdate()
    expect(nav.reload).toHaveBeenCalledTimes(1)
  })

  it('a second controller change reloads only once', async () => {
    const pwa = await started()
    container.registration.install()
    const done = pwa.applyUpdate()
    container.dispatchEvent(new Event('controllerchange'))
    container.dispatchEvent(new Event('controllerchange'))
    await done
    expect(nav.reload).toHaveBeenCalledTimes(1)
  })
})
