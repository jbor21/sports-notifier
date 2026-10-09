// Starts Prism (the API mock, :4010) and Vite in mock mode; stops both on Ctrl-C or when one exits.
import { spawn } from 'node:child_process'

const children = [
  spawn('pnpm', ['exec', 'prism', 'mock', '../docs/api/openapi.yaml', '-p', '4010'], {
    stdio: 'inherit',
  }),
  spawn('pnpm', ['exec', 'vite'], {
    stdio: 'inherit',
    env: { ...process.env, VITE_MOCK: '1' },
  }),
]

let stopping = false
function stop(code) {
  if (stopping) return
  stopping = true
  for (const c of children) c.kill('SIGTERM')
  setTimeout(() => process.exit(code), 200)
}

for (const c of children) c.on('exit', (code) => stop(code ?? 0))
process.on('SIGINT', () => stop(0))
process.on('SIGTERM', () => stop(0))
