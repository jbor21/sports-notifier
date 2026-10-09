import '@fontsource-variable/dm-sans'
import '@fontsource-variable/manrope'
import './styles/tokens.css'
import './styles/base.css'
import './styles/shell.css'
import './styles/games.css'
import './styles/game.css'
import './styles/follows.css'
import './styles/notifications.css'
import './styles/auth.css'
import './styles/settings.css'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import App from './App'
import { applyTheme, theme } from './theme'

// Before the first paint, so a dark-mode user never sees a white flash.
applyTheme(theme.get())

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
)
