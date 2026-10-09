import { Store, useStore } from './live/store'

export type Theme = 'light' | 'dark'

const KEY = 'theme'

// Storage can throw (blocked site data, some private windows); the theme then lasts the tab.
function stored(): Theme | null {
  try {
    const value = localStorage.getItem(KEY)
    return value === 'dark' || value === 'light' ? value : null
  } catch {
    return null
  }
}

function preferred(): Theme {
  const dark =
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-color-scheme: dark)').matches
  return dark ? 'dark' : 'light'
}

export const theme = new Store<Theme>(stored() ?? preferred())

export function applyTheme(t: Theme): void {
  document.documentElement.dataset.theme = t
  document
    .querySelector('meta[name="theme-color"]')
    ?.setAttribute('content', t === 'dark' ? '#141c20' : '#16794c')
}

export function setTheme(t: Theme): void {
  theme.set(t)
  applyTheme(t)
  try {
    localStorage.setItem(KEY, t)
  } catch {
    // Not remembered; still applied.
  }
}

export function useTheme(): Theme {
  return useStore(theme)
}
