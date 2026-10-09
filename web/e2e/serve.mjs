// A static server for the PWA update test, standing in for the gateway's WebFiles:
//   node serve.mjs <dir> <port>
// `/api/*` answers 401 in the API's Error shape (no backend, so the app shows sign in). Files come
// from <dir>, read on every request, so the test can swap the folder for a new build. Paths with no
// extension get index.html; a missing file with an extension stays a 404. Every answer is
// `no-cache` and carries the gateway's CSP, so an inline script in the build fails here too.
import { readFile, stat } from 'node:fs/promises'
import { createServer } from 'node:http'
import { extname, join, normalize, resolve, sep } from 'node:path'

const [dir, port] = process.argv.slice(2)
if (!dir || !port) {
  console.error('usage: node serve.mjs <dir> <port>')
  process.exit(2)
}
const root = resolve(dir)

const types = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json',
  '.webmanifest': 'application/manifest+json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.woff': 'font/woff',
  '.txt': 'text/plain; charset=utf-8',
}

function csp(host) {
  const connect = /^[A-Za-z0-9.-]+(:[0-9]{1,5})?$/.test(host ?? '')
    ? `'self' ws://${host} wss://${host}`
    : `'self'`
  return [
    "default-src 'self'",
    "script-src 'self'",
    "style-src 'self'",
    "img-src 'self' data: https://a.espncdn.com",
    "font-src 'self'",
    `connect-src ${connect}`,
    "manifest-src 'self'",
    "worker-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
  ].join('; ')
}

async function file(path) {
  try {
    const info = await stat(path)
    return info.isFile() ? await readFile(path) : null
  } catch {
    return null
  }
}

createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', 'http://localhost')
  const headers = { 'Cache-Control': 'no-cache', 'Content-Security-Policy': csp(req.headers.host) }

  if (url.pathname.startsWith('/api/')) {
    res.writeHead(401, { ...headers, 'Content-Type': 'application/json' })
    res.end(JSON.stringify({ error: { code: 'unauthenticated', message: 'Sign in first.' } }))
    return
  }

  const wanted = normalize(join(root, decodeURIComponent(url.pathname)))
  if (wanted !== root && !wanted.startsWith(root + sep)) {
    res.writeHead(404, headers)
    res.end()
    return
  }
  const ext = extname(url.pathname)
  const path = url.pathname.endsWith('/') ? join(wanted, 'index.html') : wanted
  let body = await file(path)
  let type = types[extname(path)] ?? 'application/octet-stream'
  if (!body && !ext) {
    body = await file(join(root, 'index.html'))
    type = types['.html']
  }
  if (!body) {
    res.writeHead(404, headers)
    res.end()
    return
  }
  res.writeHead(200, { ...headers, 'Content-Type': type })
  res.end(body)
}).listen(Number(port), () => console.log(`serving ${root} on http://localhost:${port}`))
