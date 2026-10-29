/**
 * V2-P0 research probe only. No PricePulse services, database, Redis or MQ.
 * Requires Node >= 22 with native fetch / WebSocket. Always uses the IEX feed.
 */
import { mkdir, rename, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const DATA = 'https://data.alpaca.markets'
const STREAM = 'wss://stream.data.alpaca.markets/v2/iex'
const OUTPUT = 'target/v2-p0/provider-latest.json'
const MINUTE_NS = 60_000_000_000n
const NY = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'America/New_York', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
})

export class ProbeError extends Error {
  constructor(code) { super(code); this.code = code }
}

export function configuration(env) {
  const missing = ['APCA_API_KEY_ID', 'APCA_API_SECRET_KEY'].filter(k => !env[k]?.trim())
  if (missing.length) throw new ProbeError('MISSING_CREDENTIALS')
  const symbols = (env.PRICEPULSE_PROBE_SYMBOLS || 'AAPL,MSFT,NVDA,AMZN,GOOGL')
    .split(',').map(s => s.trim()).filter(Boolean)
  const seconds = Number(env.PRICEPULSE_PROBE_SECONDS || 20)
  const account = env.APCA_API_ENV || 'paper'
  if (symbols.length < 3 || symbols.length > 5 || new Set(symbols).size !== symbols.length
      || symbols.some(s => !/^[A-Z][A-Z0-9.]{0,9}$/.test(s))
      || !Number.isInteger(seconds) || seconds < 5 || seconds > 60
      || !['paper', 'live'].includes(account)
      || /[\r\n]/.test(env.APCA_API_KEY_ID + env.APCA_API_SECRET_KEY)) {
    throw new ProbeError('INVALID_CONFIGURATION')
  }
  return {
    symbols, seconds, account,
    key: env.APCA_API_KEY_ID.trim(), secret: env.APCA_API_SECRET_KEY.trim(),
    tradingBase: account === 'paper' ? 'https://paper-api.alpaca.markets' : 'https://api.alpaca.markets',
  }
}

export function timestampNanos(value) {
  const m = typeof value === 'string'
    && value.match(/^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})$/)
  if (!m) throw new ProbeError('INVALID_EVENT_TIMESTAMP')
  const ms = Date.parse(m[1] + m[3])
  const nominal = Date.parse(m[1] + 'Z')
  if (!Number.isFinite(ms) || !Number.isFinite(nominal)
      || new Date(nominal).toISOString().slice(0, 19) !== m[1]) {
    throw new ProbeError('INVALID_EVENT_TIMESTAMP')
  }
  return BigInt(ms) * 1_000_000n + BigInt((m[2] || '').padEnd(9, '0'))
}

function nyParts(ms) {
  return Object.fromEntries(NY.formatToParts(new Date(ms)).map(p => [p.type, p.value]))
}

function nyInstant(date, time) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !/^\d{2}:\d{2}$/.test(time)) {
    throw new ProbeError('INVALID_CALENDAR')
  }
  const nominal = Date.parse(date + 'T' + time + ':00Z')
  let ms = nominal
  for (let i = 0; i < 3; i++) {
    const p = nyParts(ms)
    const shown = Date.parse(p.year + '-' + p.month + '-' + p.day + 'T'
      + p.hour + ':' + p.minute + ':' + p.second + 'Z')
    ms += nominal - shown
  }
  const p = nyParts(ms)
  if (p.year + '-' + p.month + '-' + p.day !== date || p.hour + ':' + p.minute !== time) {
    throw new ProbeError('INVALID_CALENDAR')
  }
  return new Date(ms).toISOString()
}

export function normalizeCalendar(rows) {
  if (!Array.isArray(rows) || !rows.length) throw new ProbeError('EMPTY_CALENDAR')
  const sessions = rows.map(row => ({
    date: row.date, open: nyInstant(row.date, row.open), close: nyInstant(row.date, row.close),
  })).sort((a, b) => a.open.localeCompare(b.open))
  for (let i = 0; i < sessions.length; i++) {
    if (sessions[i].open >= sessions[i].close || (i && sessions[i - 1].close >= sessions[i].open)) {
      throw new ProbeError('INVALID_CALENDAR')
    }
  }
  return sessions
}

/** P0 semantics experiment, not the production Java simulation clock. */
export function advanceTradingMinutes(origin, minutes, sessions) {
  if (!Number.isInteger(minutes) || minutes < 1) throw new ProbeError('INVALID_DURATION')
  let cursor = timestampNanos(origin)
  let remaining = BigInt(minutes) * MINUTE_NS
  for (const s of sessions) {
    const open = timestampNanos(s.open), close = timestampNanos(s.close)
    if (cursor >= close) continue
    if (cursor < open) cursor = open
    const available = close - cursor
    if (remaining <= available) return (cursor + remaining).toString()
    remaining -= available
    cursor = close
  }
  throw new ProbeError('CALENDAR_RANGE_EXHAUSTED')
}

export async function readJson(url, config, fetchImpl = fetch) {
  const parsed = new URL(url)
  const allowed = (parsed.origin === DATA
    && ['/v2/stocks/bars', '/v2/stocks/trades/latest'].includes(parsed.pathname))
    || (parsed.origin === config.tradingBase && ['/v2/calendar', '/v2/clock'].includes(parsed.pathname))
  if (!allowed || parsed.username || parsed.password) throw new ProbeError('ENDPOINT_NOT_ALLOWED')
  let response
  try {
    response = await fetchImpl(parsed, {
      method: 'GET', redirect: 'error', signal: AbortSignal.timeout(12_000),
      headers: { 'APCA-API-KEY-ID': config.key, 'APCA-API-SECRET-KEY': config.secret },
    })
  } catch { throw new ProbeError('HTTP_NETWORK_OR_TIMEOUT') }
  if (!response.ok) throw new ProbeError('HTTP_' + response.status)
  try { return await response.json() }
  catch { throw new ProbeError('INVALID_JSON') }
}

export async function historyProbe(config, start, end, fetchImpl = fetch) {
  const perSymbol = Object.fromEntries(config.symbols.map(s => [s, { count: 0, first: null, last: null }]))
  const seenTokens = new Set()
  let token = null
  for (let page = 1; page <= 20; page++) {
    const url = new URL(DATA + '/v2/stocks/bars')
    url.search = new URLSearchParams({
      symbols: config.symbols.join(','), timeframe: '1Min', feed: 'iex', adjustment: 'raw',
      start, end, sort: 'asc', limit: '10000', ...(token ? { page_token: token } : {}),
    }).toString()
    const data = await readJson(url, config, fetchImpl)
    if (!data.bars || typeof data.bars !== 'object') throw new ProbeError('INVALID_BARS')
    for (const symbol of config.symbols) {
      const bars = data.bars[symbol] || []
      if (!Array.isArray(bars)) throw new ProbeError('INVALID_BARS')
      for (const b of bars) {
        const t = timestampNanos(b.t)
        if (t < timestampNanos(start) || t > timestampNanos(end)
            || !Number.isFinite(b.c) || b.c <= 0) throw new ProbeError('INVALID_BAR')
        const stats = perSymbol[symbol]
        if (stats.last && t <= timestampNanos(stats.last)) throw new ProbeError('BAR_ORDER_OR_DUPLICATE')
        stats.first ||= b.t
        stats.last = b.t
        stats.count++
      }
    }
    token = data.next_page_token
    if (!token) {
      const edgeTolerance = 7n * 24n * 60n * MINUTE_NS
      const complete = Object.values(perSymbol).every(s => s.count
        && timestampNanos(s.first) <= timestampNanos(start) + edgeTolerance
        && timestampNanos(s.last) >= timestampNanos(end) - edgeTolerance)
      return { status: complete ? 'PASSED' : 'INCOMPLETE', feed: 'iex', adjustment: 'raw',
        requestedStart: start, requestedEnd: end, pages: page, paginationComplete: true,
        perSymbol, coverageCheck: 'Both ends within 7 days; sparse minute bars are not fabricated.' }
    }
    if (typeof token !== 'string' || seenTokens.has(token)) throw new ProbeError('REPEATED_PAGE_TOKEN')
    seenTokens.add(token)
  }
  throw new ProbeError('HISTORY_PAGE_LIMIT')
}

export function streamProbe(config, {
  WebSocketImpl = WebSocket, sampleMs = config.seconds * 1000, timeoutMs = 12_000, now = Date.now,
} = {}) {
  return new Promise(resolveResult => {
    const result = { status: 'FAILED', authenticated: false, subscribed: false, feed: 'iex',
      tradeCount: 0, quoteMessages: 0, duplicateTrades: 0, outOfOrderTrades: 0,
      perSymbol: Object.fromEntries(config.symbols.map(s => [s, 0])), sampleTimestamps: [] }
    const identities = new Set(), watermarks = new Map(), buckets = new Map()
    let socket, sampleTimer, closeTimer, requestedClose = false, started = null, code = null, settled = false
    const finish = () => {
      if (settled) return
      settled = true
      clearTimeout(authTimer); clearTimeout(sampleTimer); clearTimeout(closeTimer)
      result.status = code ? 'FAILED' : 'PASSED'
      if (code) result.errorCode = code
      result.observationSeconds = started === null ? 0 : Math.min(sampleMs, Math.max(0, now() - started)) / 1000
      result.eventsPerSecond = result.observationSeconds ? result.tradeCount / result.observationSeconds : 0
      result.peakOneSecondEvents = Math.max(0, ...buckets.values())
      resolveResult(result)
    }
    const close = errorCode => {
      if (requestedClose) return
      requestedClose = true
      code = errorCode || null
      clearTimeout(authTimer); clearTimeout(sampleTimer)
      closeTimer = setTimeout(() => { code = 'SOCKET_CLOSE_TIMEOUT'; finish() }, 2_000)
      try { socket.close(1000) } catch { code ||= 'WEBSOCKET_CLOSE_FAILED'; finish() }
    }
    const authTimer = setTimeout(() => close('AUTH_OR_SUBSCRIPTION_TIMEOUT'), timeoutMs)
    try { socket = new WebSocketImpl(STREAM) }
    catch { code = 'WEBSOCKET_UNAVAILABLE'; finish(); return }
    socket.addEventListener('open', () => {
      try { socket.send(JSON.stringify({ action: 'auth', key: config.key, secret: config.secret })) }
      catch { close('AUTH_SEND_FAILED') }
    })
    socket.addEventListener('message', event => {
      if (requestedClose) return
      try {
        const messages = JSON.parse(event.data)
        if (!Array.isArray(messages)) throw new ProbeError('INVALID_WS_FRAME')
        for (const message of messages) {
          if (message.T === 'error') {
            close('WS_' + (Number.isInteger(message.code) ? message.code : 'PROVIDER_ERROR'))
            return
          }
          if (message.T === 'success' && message.msg === 'authenticated') {
            if (result.authenticated) continue
            result.authenticated = true
            socket.send(JSON.stringify({ action: 'subscribe', trades: config.symbols }))
          } else if (message.T === 'subscription') {
            if (!result.authenticated || !Array.isArray(message.trades)
                || !config.symbols.every(s => message.trades.includes(s))) {
              close('SUBSCRIPTION_MISMATCH'); return
            }
            if (result.subscribed) continue
            result.subscribed = true
            clearTimeout(authTimer)
            started = now()
            sampleTimer = setTimeout(() => close(), sampleMs)
          } else if (message.T === 'q') {
            result.quoteMessages++
          } else if (message.T === 't' && result.subscribed) {
            if (!config.symbols.includes(message.S) || !Number.isSafeInteger(message.i)
                || !Number.isFinite(message.p) || message.p <= 0) throw new ProbeError('INVALID_TRADE')
            const t = timestampNanos(message.t)
            const p = nyParts(Number(t / 1_000_000n))
            const id = [message.S, p.year, p.month, p.day, message.x, message.i].join(':')
            if (identities.has(id)) result.duplicateTrades++
            identities.add(id)
            if (identities.size > 100_000) throw new ProbeError('PROBE_SAMPLE_CAPACITY')
            const previous = watermarks.get(message.S)
            if (previous !== undefined && t < previous) result.outOfOrderTrades++
            if (previous === undefined || t > previous) watermarks.set(message.S, t)
            result.perSymbol[message.S]++
            result.tradeCount++
            const bucket = Math.floor((now() - started) / 1000)
            buckets.set(bucket, (buckets.get(bucket) || 0) + 1)
            if (result.sampleTimestamps.length < 3) result.sampleTimestamps.push(message.t)
          }
        }
      } catch (e) { close(e instanceof ProbeError ? e.code : 'INVALID_WS_FRAME') }
    })
    socket.addEventListener('error', () => close('WEBSOCKET_TRANSPORT_ERROR'))
    socket.addEventListener('close', event => {
      result.closeCode = event.code
      result.clientRequestedClose = requestedClose
      // A missing close acknowledgement AFTER our sample window is not an early stream failure.
      // Still retain it as a warning for the production Java client verification.
      if (!requestedClose) code ||= 'WEBSOCKET_CLOSED_EARLY'
      else if (event.code === 1006 && !code && started !== null) {
        result.warnings = ['UNCLEAN_CLOSE_AFTER_PROBE_WINDOW']
      } else if (![1000, 1005].includes(event.code)) code ||= 'WEBSOCKET_ABNORMAL_CLOSE'
      finish()
    })
  })
}

export async function runProbe(env = process.env, dependencies = {}) {
  const report = {
    schemaVersion: 1, phase: 'V2-P0', startedAt: new Date().toISOString(), status: 'BLOCKED',
    provider: 'ALPACA', feed: 'iex', marketCoverage: 'SINGLE_EXCHANGE',
    businessWrites: 0, checks: {}, manualGate: 'Account data-use/display permissions require separate evidence.',
  }
  let config
  try { config = configuration(env) }
  catch (e) {
    report.reason = e instanceof ProbeError ? e.code : 'CONFIGURATION_ERROR'
    report.requiredVariables = ['APCA_API_KEY_ID', 'APCA_API_SECRET_KEY']
    return report
  }
  const fetchImpl = dependencies.fetchImpl || fetch
  const now = dependencies.now || Date.now
  const end = new Date(now()).toISOString()
  const start = new Date(now() - 32 * 86_400_000).toISOString()
  report.symbols = config.symbols
  report.accountEndpoint = config.account
  const check = async (name, task) => {
    try { report.checks[name] = await task() }
    catch (e) { report.checks[name] = { status: 'FAILED',
      errorCode: e instanceof ProbeError ? e.code : 'PROBE_CHECK_FAILED' } }
  }
  await check('clock', async () => {
    const c = await readJson(config.tradingBase + '/v2/clock', config, fetchImpl)
    timestampNanos(c.timestamp)
    if (typeof c.is_open !== 'boolean') throw new ProbeError('INVALID_CLOCK')
    return { status: 'PASSED', isOpen: c.is_open, timestamp: c.timestamp }
  })
  await check('calendar', async () => {
    const url = new URL(config.tradingBase + '/v2/calendar')
    url.search = new URLSearchParams({ start: start.slice(0, 10),
      end: new Date(now() + 35 * 86_400_000).toISOString().slice(0, 10) }).toString()
    const sessions = normalizeCalendar(await readJson(url, config, fetchImpl))
    const digestInput = JSON.stringify(sessions)
    const { createHash } = await import('node:crypto')
    const first = Date.parse(sessions[0].open)
    const last = Date.parse(sessions.at(-1).close)
    const coverage = sessions.length >= 20 && first <= Date.parse(start) + 7 * 86_400_000
      && last >= now() + 28 * 86_400_000
    return { status: coverage ? 'PASSED' : 'INCOMPLETE', sessionCount: sessions.length,
      sha256: createHash('sha256').update(digestInput).digest('hex'), sessions }
  })
  await check('latest', async () => {
    const url = new URL(DATA + '/v2/stocks/trades/latest')
    url.search = new URLSearchParams({ symbols: config.symbols.join(','), feed: 'iex' }).toString()
    const data = await readJson(url, config, fetchImpl)
    const timestamps = {}
    for (const symbol of config.symbols) {
      const trade = data.trades?.[symbol]
      if (!trade || !Number.isFinite(trade.p) || trade.p <= 0) throw new ProbeError('MISSING_LATEST_TRADE')
      timestampNanos(trade.t)
      timestamps[symbol] = trade.t
    }
    return { status: 'PASSED', timestamps }
  })
  await check('history', () => historyProbe(config, start, end, fetchImpl))
  // Sequential sockets: Basic accounts may permit only one connection.
  const first = await streamProbe(config, dependencies)
  report.checks.stream = first
  if (first.status === 'PASSED') {
    await (dependencies.wait || (ms => new Promise(r => setTimeout(r, ms))))(1_000)
    report.checks.reconnect = await streamProbe(config, dependencies)
  } else {
    report.checks.reconnect = { status: 'BLOCKED', reason: 'INITIAL_STREAM_FAILED' }
  }
  report.checks.throughput = {
    status: report.checks.clock?.isOpen && first.tradeCount > 0
      && first.observationSeconds > 0 && report.checks.reconnect.tradeCount > 0
      && report.checks.reconnect.observationSeconds > 0 ? 'PASSED' : 'INCOMPLETE',
    meaning: 'Observed source rate only; does not prove PricePulse processing capacity.',
  }
  const statuses = Object.values(report.checks).map(c => c.status)
  report.status = statuses.includes('FAILED') || statuses.includes('BLOCKED') ? 'FAILED'
    : statuses.every(s => s === 'PASSED') ? 'PASSED' : 'INCOMPLETE'
  report.finishedAt = new Date().toISOString()
  return report
}

async function main() {
  const report = await runProbe()
  await mkdir(dirname(OUTPUT), { recursive: true })
  await writeFile(OUTPUT + '.tmp', JSON.stringify(report, null, 2) + '\n', { mode: 0o600 })
  await rename(OUTPUT + '.tmp', OUTPUT)
  console.log('V2-P0 provider probe: ' + report.status + '; report: ' + OUTPUT)
  if (report.reason) console.log('Reason: ' + report.reason + '; configure .local/alpaca.env locally.')
  process.exitCode = report.status === 'PASSED' ? 0 : report.status === 'FAILED' ? 1 : 2
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(() => { console.error('V2-P0 probe failed; no provider response or credentials logged.'); process.exitCode = 1 })
}
