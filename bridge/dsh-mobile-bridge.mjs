#!/usr/bin/env node
/**
 * dsh-mobile-bridge — exposes `dsh --profile acp` (ACP v1 over stdio) as a
 * WebSocket endpoint the Android client can talk to.
 *
 *   node bridge/dsh-mobile-bridge.mjs --port 8787 --token SECRET --cwd /path/to/project
 *
 * Client connects to ws://<host>:8787/ws?token=SECRET and speaks ACP JSON-RPC
 * exactly as it would over stdio: one JSON object per text frame.
 *
 * Only dependency: `ws` (npm i ws). Everything else is Node built-ins.
 */
import { createServer } from 'node:http'
import { spawn } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import process from 'node:process'

const args = new Map()
for (let i = 2; i < process.argv.length; i += 2) {
  const k = process.argv[i].replace(/^--/, '')
  args.set(k, process.argv[i + 1])
}

const PORT = Number(args.get('port') ?? 8787)
const TOKEN = args.get('token') ?? ''
const CWD = args.get('cwd') ?? process.cwd()
const DSH = args.get('dsh') ?? 'dsh'

let WebSocketServer
try {
  ;({ WebSocketServer } = await import('ws'))
} catch {
  console.error('missing dependency: run `npm i ws` inside bridge/ first')
  process.exit(1)
}

/** One ACP child process per connected client: no shared state, no cross-talk. */
function spawnAcp() {
  const child = spawn(DSH, ['--profile', 'acp'], {
    cwd: CWD,
    env: { ...process.env, DSH_HOME: args.get('dsh-home') ?? process.env.DSH_HOME ?? '' },
    stdio: ['pipe', 'pipe', 'pipe'],
  })
  child.stderr.on('data', (b) => process.stderr.write(`[acp] ${b}`))
  child.on('exit', (code) => process.stderr.write(`[acp] exited ${code}\n`))
  return child
}

const server = createServer((req, res) => {
  if (req.url === '/health') {
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ ok: true, clients: wss.clients.size, cwd: CWD }))
    return
  }
  res.writeHead(404).end('not found')
})

const wss = new WebSocketServer({ server, path: '/ws' })

wss.on('connection', (ws, req) => {
  const url = new URL(req.url, `http://${req.headers.host}`)
  const given = url.searchParams.get('token') ?? ''
  if (TOKEN && given !== TOKEN && req.headers.authorization !== `Bearer ${TOKEN}`) {
    ws.close(4401, 'unauthorized')
    return
  }

  const id = randomUUID().slice(0, 8)
  const child = spawnAcp()
  console.log(`[bridge] client ${id} connected, acp pid ${child.pid}`)

  let buffer = ''
  child.stdout.on('data', (chunk) => {
    buffer += chunk.toString('utf8')
    let nl
    while ((nl = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, nl).trim()
      buffer = buffer.slice(nl + 1)
      if (line && ws.readyState === ws.OPEN) ws.send(line)
    }
  })

  ws.on('message', (data) => {
    const text = data.toString('utf8').trim()
    if (!text) return
    // bridge/ping is answered locally so the client can measure liveness cheaply.
    if (text === '{"bridge":"ping"}') {
      ws.send(JSON.stringify({ bridge: 'pong', ts: Date.now() }))
      return
    }
    child.stdin.write(text + '\n')
  })

  const shutdown = () => {
    try { child.stdin.end() } catch {}
    try { child.kill('SIGTERM') } catch {}
  }
  ws.on('close', () => { console.log(`[bridge] client ${id} gone`); shutdown() })
  ws.on('error', shutdown)
  child.on('exit', () => { try { ws.close(1011, 'acp exited') } catch {} })
})

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[bridge] ws://0.0.0.0:${PORT}/ws  cwd=${CWD}  token=${TOKEN ? 'on' : 'off'}`)
})
