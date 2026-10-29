import test from 'node:test'
import assert from 'node:assert/strict'
import {
  configuration, timestampNanos, normalizeCalendar, advanceTradingMinutes,
  readJson, historyProbe, streamProbe, runProbe,
} from '../v2-market-data-probe.mjs'

const ENV = { APCA_API_KEY_ID: 'offline-test-key', APCA_API_SECRET_KEY: 'offline-test-secret' }
const config = configuration(ENV)
const NOW = Date.parse('2026-10-28T14:00:00Z')
const START = new Date(NOW - 32 * 86_400_000).toISOString()
const END = new Date(NOW).toISOString()
const jsonResponse = body => ({ ok: true, json: async () => body })
const bars = () => [
  { t: START, c: 100 }, { t: END, c: 101 },
]

function socketDouble({ authError = false, acknowledge = true, earlyClose = false, peerCloseCode } = {}) {
  return class extends EventTarget {
    static instances = []
    static active = 0
    constructor(url) {
      super()
      assert.equal(url, 'wss://stream.data.alpaca.markets/v2/iex')
      assert.equal(this.constructor.active, 0, 'only one connection may be open')
      this.constructor.active++
      this.constructor.instances.push(this)
      this.sent = []; this.closed = false
      queueMicrotask(() => this.dispatchEvent(new Event('open')))
    }
    message(body) {
      queueMicrotask(() => this.dispatchEvent(Object.assign(new Event('message'), { data: JSON.stringify(body) })))
    }
    send(raw) {
      const data = JSON.parse(raw)
      this.sent.push(data)
      if (data.action === 'auth') {
        this.message(authError ? [{ T: 'error', code: 402, msg: ENV.APCA_API_SECRET_KEY }]
          : [{ T: 'success', msg: 'authenticated' }])
        if (earlyClose) queueMicrotask(() => this.close(1006))
      } else if (data.action === 'subscribe' && acknowledge) {
        this.message([{ T: 'subscription', trades: data.trades }])
        this.message([
          { T: 'q', S: 'AAPL', ap: 101, bp: 100 },
          { T: 't', S: 'AAPL', i: 1, x: 'V', p: 100, t: '2026-10-28T14:00:00.000000002Z' },
          { T: 't', S: 'AAPL', i: 2, x: 'V', p: 99, t: '2026-10-28T14:00:00.000000001Z' },
          { T: 't', S: 'AAPL', i: 2, x: 'V', p: 99, t: '2026-10-28T14:00:00.000000001Z' },
        ])
      }
    }
    close(code = 1000) {
      if (this.closed) return
      this.closed = true
      this.constructor.active--
      queueMicrotask(() => this.dispatchEvent(Object.assign(new Event('close'), { code: peerCloseCode ?? code })))
    }
  }
}

test('missing credentials blocks before any external request; not a skipped/pass result', async () => {
  let calls = 0
  const report = await runProbe({}, {
    fetchImpl: async () => { calls++; throw new Error('must not call') },
    WebSocketImpl: class { constructor() { calls++; throw new Error('must not connect') } },
  })
  assert.equal(report.status, 'BLOCKED')
  assert.equal(report.reason, 'MISSING_CREDENTIALS')
  assert.equal(report.businessWrites, 0)
  assert.equal(calls, 0)
})

test('configuration limits scope to 3–5 distinct symbols, fixed account endpoints and bounded duration', () => {
  assert.equal(config.tradingBase, 'https://paper-api.alpaca.markets')
  for (const extra of [
    { PRICEPULSE_PROBE_SYMBOLS: '*' }, { PRICEPULSE_PROBE_SYMBOLS: 'AAPL,MSFT,AAPL' },
    { PRICEPULSE_PROBE_SECONDS: '0' }, { PRICEPULSE_PROBE_SECONDS: '61' },
    { APCA_API_ENV: 'https://example.com' }, { APCA_API_KEY_ID: 'unsafe\r\nheader' },
  ]) assert.throws(() => configuration({ ...ENV, ...extra }), /INVALID_CONFIGURATION/)
})

test('event timestamps preserve nanoseconds, offsets and equality without JS number rounding', () => {
  const a = timestampNanos('2026-10-28T14:00:00.123456001Z')
  const b = timestampNanos('2026-10-28T10:00:00.123456002-04:00')
  assert.equal(b - a, 1n)
  assert.equal(timestampNanos('2026-10-28T14:00:00Z'), BigInt(NOW) * 1_000_000n)
  for (const invalid of ['2026-10-28T14:00:00', '2026-02-30T14:00:00Z', 'not-a-time',
    '2026-10-28T14:00:00.1234567891Z']) {
    assert.throws(() => timestampNanos(invalid), /INVALID_EVENT_TIMESTAMP/)
  }
})

test('calendar converts New York sessions across DST and preserves early close', () => {
  const sessions = normalizeCalendar([
    { date: '2026-03-06', open: '09:30', close: '16:00' },
    { date: '2026-03-09', open: '09:30', close: '16:00' },
    { date: '2026-11-27', open: '09:30', close: '13:00' },
  ])
  assert.equal(sessions[0].open, '2026-03-06T14:30:00.000Z')
  assert.equal(sessions[1].open, '2026-03-09T13:30:00.000Z')
  assert.equal(sessions[2].close, '2026-11-27T18:00:00.000Z')
})

test('60 trading minutes skip the weekend without dropping fractional origin seconds', () => {
  const sessions = normalizeCalendar([
    { date: '2026-03-06', open: '09:30', close: '16:00' },
    { date: '2026-03-09', open: '09:30', close: '16:00' },
  ])
  assert.equal(advanceTradingMinutes('2026-03-06T20:40:30.000000125Z', 60, sessions),
    timestampNanos('2026-03-09T14:10:30.000000125Z').toString())
  assert.equal(advanceTradingMinutes('2026-03-07T10:00:00Z', 60, sessions),
    timestampNanos('2026-03-09T14:30:00Z').toString())
  assert.throws(() => advanceTradingMinutes('2026-03-09T19:50:00Z', 60, sessions),
    /CALENDAR_RANGE_EXHAUSTED/)
})

test('early-close boundary advances into the next supplied trading session', () => {
  const sessions = normalizeCalendar([
    { date: '2026-11-27', open: '09:30', close: '13:00' },
    { date: '2026-11-30', open: '09:30', close: '16:00' },
  ])
  assert.equal(advanceTradingMinutes('2026-11-27T17:40:00Z', 60, sessions),
    timestampNanos('2026-11-30T15:10:00Z').toString())
  assert.throws(() => normalizeCalendar([
    { date: '2026-11-27', open: '13:00', close: '09:30' },
  ]), /INVALID_CALENDAR/)
})

test('REST requests are GET-only, use fixed endpoints, and never follow redirects', async () => {
  let called = false
  await readJson('https://data.alpaca.markets/v2/stocks/bars?feed=iex', config, async (_, init) => {
    called = true
    assert.equal(init.method, 'GET')
    assert.equal(init.redirect, 'error')
    assert.equal(init.headers['APCA-API-KEY-ID'], ENV.APCA_API_KEY_ID)
    return jsonResponse({})
  })
  assert.equal(called, true)
  for (const url of ['http://data.alpaca.markets/v2/stocks/bars',
    'https://data.alpaca.markets/v2/orders', 'http://127.0.0.1:18080/api/market/simulate',
    'https://example.com/v2/calendar']) {
    await assert.rejects(readJson(url, config, async () => { throw new Error('must not call') }),
      /ENDPOINT_NOT_ALLOWED/)
  }
})

test('HTTP permission failures retain only numeric status, never raw provider error bodies', async () => {
  await assert.rejects(readJson('https://data.alpaca.markets/v2/stocks/bars', config,
    async () => ({ ok: false, status: 403, json: async () => { throw new Error(ENV.APCA_API_SECRET_KEY) } })),
  /HTTP_403/)
})

test('history follows pagination across symbols and explicitly pins IEX/raw/minute bars', async () => {
  let pages = 0
  const result = await historyProbe(config, START, END, async url => {
    const u = new URL(url)
    assert.equal(u.searchParams.get('feed'), 'iex')
    assert.equal(u.searchParams.get('adjustment'), 'raw')
    assert.equal(u.searchParams.get('timeframe'), '1Min')
    pages++
    if (pages === 1) return jsonResponse({ bars: { AAPL: bars() }, next_page_token: 'second-page' })
    assert.equal(u.searchParams.get('page_token'), 'second-page')
    return jsonResponse({ bars: Object.fromEntries(config.symbols.slice(1).map(s => [s, bars()])) })
  })
  assert.equal(result.status, 'PASSED')
  assert.equal(result.pages, 2)
  assert.ok(Object.values(result.perSymbol).every(s => s.count === 2))
})

test('missing monthly coverage is incomplete, pagination loops fail instead of silently truncating', async () => {
  const short = await historyProbe(config, START, END,
    async () => jsonResponse({ bars: { AAPL: [{ t: END, c: 101 }] } }))
  assert.equal(short.status, 'INCOMPLETE')
  await assert.rejects(historyProbe(config, START, END,
    async () => jsonResponse({ bars: {}, next_page_token: 'same-token' })), /REPEATED_PAGE_TOKEN/)
})

test('WebSocket authenticates then subscribes, counts trade duplicates/order and excludes quotes', async () => {
  const Socket = socketDouble()
  const result = await streamProbe(config, { WebSocketImpl: Socket, sampleMs: 5 })
  assert.equal(result.status, 'PASSED')
  assert.equal(result.authenticated, true)
  assert.equal(result.subscribed, true)
  assert.equal(result.tradeCount, 3)
  assert.equal(result.quoteMessages, 1)
  assert.equal(result.duplicateTrades, 1)
  assert.equal(result.outOfOrderTrades, 2)
  assert.deepEqual(Socket.instances[0].sent.map(x => x.action), ['auth', 'subscribe'])
  assert.equal(Socket.active, 0)
  assert.equal(result.sampleTimestamps[0], '2026-10-28T14:00:00.000000002Z')
})

test('auth error, missing subscription ack and early disconnect cannot pass', async () => {
  const auth = await streamProbe(config, { WebSocketImpl: socketDouble({ authError: true }), sampleMs: 1 })
  assert.equal(auth.errorCode, 'WS_402')
  assert.ok(!JSON.stringify(auth).includes(ENV.APCA_API_SECRET_KEY))
  const timeout = await streamProbe(config, {
    WebSocketImpl: socketDouble({ acknowledge: false }), sampleMs: 1, timeoutMs: 5,
  })
  assert.equal(timeout.errorCode, 'AUTH_OR_SUBSCRIPTION_TIMEOUT')
  const closed = await streamProbe(config, {
    WebSocketImpl: socketDouble({ earlyClose: true, acknowledge: false }), sampleMs: 1,
  })
  assert.equal(closed.errorCode, 'WEBSOCKET_CLOSED_EARLY')
})

test('an empty peer close acknowledgement is valid only after client-initiated close', async () => {
  const normal = await streamProbe(config, {
    WebSocketImpl: socketDouble({ peerCloseCode: 1005 }), sampleMs: 1,
  })
  assert.equal(normal.status, 'PASSED')
  assert.equal(normal.closeCode, 1005)
  const early = await streamProbe(config, {
    WebSocketImpl: socketDouble({ earlyClose: true, acknowledge: false, peerCloseCode: 1005 }),
    sampleMs: 1,
  })
  assert.equal(early.status, 'FAILED')
  const abnormal = await streamProbe(config, {
    WebSocketImpl: socketDouble({ peerCloseCode: 1006 }), sampleMs: 1,
  })
  assert.equal(abnormal.status, 'PASSED')
  assert.deepEqual(abnormal.warnings, ['UNCLEAN_CLOSE_AFTER_PROBE_WINDOW'])
  const dropped = await streamProbe(config, {
    WebSocketImpl: socketDouble({ earlyClose: true, acknowledge: false, peerCloseCode: 1006 }),
    sampleMs: 1,
  })
  assert.equal(dropped.status, 'FAILED')
  assert.equal(dropped.errorCode, 'WEBSOCKET_CLOSED_EARLY')
})

test('complete probe reconnects sequentially; closed-market traffic is not claimed as RTH throughput', async () => {
  const Socket = socketDouble()
  const report = await runProbe(ENV, {
    now: () => NOW, WebSocketImpl: Socket, sampleMs: 2, wait: async () => {},
    fetchImpl: async url => {
      if (url.pathname === '/v2/clock') return jsonResponse({ timestamp: END, is_open: false })
      if (url.pathname === '/v2/calendar') return jsonResponse([
        { date: '2026-10-28', open: '09:30', close: '16:00' },
      ])
      if (url.pathname === '/v2/stocks/trades/latest') return jsonResponse({
        trades: Object.fromEntries(config.symbols.map(s => [s, { t: END, p: 100 }])),
      })
      return jsonResponse({ bars: Object.fromEntries(config.symbols.map(s => [s, bars()])) })
    },
  })
  assert.equal(Socket.instances.length, 2)
  assert.equal(Socket.active, 0)
  assert.equal(report.checks.stream.status, 'PASSED')
  assert.equal(report.checks.reconnect.status, 'PASSED')
  assert.equal(report.checks.throughput.status, 'INCOMPLETE')
  assert.equal(report.checks.calendar.status, 'INCOMPLETE')
  assert.equal(report.status, 'INCOMPLETE')
  assert.equal(report.businessWrites, 0)
  const serialized = JSON.stringify(report)
  assert.ok(!serialized.includes(ENV.APCA_API_KEY_ID))
  assert.ok(!serialized.includes(ENV.APCA_API_SECRET_KEY))
})

test('open-market full probe requires calendar coverage, all symbols and two real observation windows', async () => {
  const Socket = socketDouble()
  const wallStart = Date.now()
  const calendar = []
  for (let offset = -32; offset <= 35; offset++) {
    const day = new Date(NOW + offset * 86_400_000)
    if (![0, 6].includes(day.getUTCDay())) {
      calendar.push({ date: day.toISOString().slice(0, 10), open: '09:30', close: '16:00' })
    }
  }
  const report = await runProbe(ENV, {
    now: () => NOW + Date.now() - wallStart,
    WebSocketImpl: Socket, sampleMs: 5, wait: async () => {},
    fetchImpl: async url => {
      if (url.pathname === '/v2/clock') return jsonResponse({ timestamp: END, is_open: true })
      if (url.pathname === '/v2/calendar') return jsonResponse(calendar)
      if (url.pathname === '/v2/stocks/trades/latest') return jsonResponse({
        trades: Object.fromEntries(config.symbols.map(s => [s, { t: END, p: 100 }])),
      })
      const end = url.searchParams.get('end'), start = url.searchParams.get('start')
      return jsonResponse({ bars: Object.fromEntries(config.symbols.map(s =>
        [s, [{ t: start, c: 100 }, { t: end, c: 101 }]])) })
    },
  })
  assert.equal(report.status, 'PASSED')
  assert.equal(report.checks.calendar.status, 'PASSED')
  assert.equal(report.checks.throughput.status, 'PASSED')
  assert.equal(Socket.instances.length, 2)
  assert.equal(Socket.active, 0)
})
