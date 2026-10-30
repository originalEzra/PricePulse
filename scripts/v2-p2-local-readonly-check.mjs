import assert from 'node:assert/strict'
import { createHmac } from 'node:crypto'
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'

const root = fileURLToPath(new URL('..', import.meta.url))
const reportOverride = process.argv.find(arg => arg.startsWith('--report-dir='))?.slice('--report-dir='.length)
const requestedSymbols = (process.argv.find(arg => arg.startsWith('--symbols='))?.slice('--symbols='.length)
  ?? 'AAPL,MSFT,NVDA,AMZN,GOOGL').split(',')
if (!requestedSymbols.length || requestedSymbols.length > 15 || new Set(requestedSymbols).size !== requestedSymbols.length
  || requestedSymbols.some(symbol => !/^[A-Z][A-Z0-9.]{0,9}$/.test(symbol))) throw new Error('LOCAL_SYMBOL_SCOPE_INVALID')
function reportDirectory(fallback) {
  const directory = resolve(root, reportOverride ?? fallback)
  if (!directory.startsWith(resolve(root, 'target') + '/')) throw new Error('LOCAL_REPORT_DIRECTORY_INVALID')
  mkdirSync(directory, { recursive: true }); return directory
}
const read = path => readFileSync(resolve(root, path), 'utf8')
// Preserve private IDEA settings; only use the signing key in memory for a short-lived
// unpersisted test principal. No real account, password, token, or private config is printed.
const decodeXml = text => text.replace(/&#x([\da-f]+);|&#(\d+);|&(amp|quot|apos|lt|gt);/gi,
  (_, hex, dec, named) => hex ? String.fromCodePoint(parseInt(hex, 16)) : dec ? String.fromCodePoint(Number(dec))
    : ({ amp: '&', quot: '"', apos: "'", lt: '<', gt: '>' })[named.toLowerCase()])
const runEnv = {}
for (const tag of read('.run/PricePulse Local.run.xml').matchAll(/<env\b([^>]*?)\/?\s*>/g)) {
  const attributes = Object.fromEntries([...tag[1].matchAll(/([\w-]+)="([^"]*)"/g)].map(m => [m[1], decodeXml(m[2])]))
  if (attributes.name && attributes.value !== undefined) runEnv[attributes.name] = attributes.value
}
const env = { ...process.env, ...runEnv }
const local = read('src/main/resources/application-local.yml'), main = read('src/main/resources/application.yml')
function scalar(text, name) { return text.match(new RegExp(`^\\s*${name}:\\s*(.+)$`, 'm'))?.[1]?.trim() }
function value(text) {
  if (text?.startsWith('"') && text.endsWith('"')) text = JSON.parse(text)
  else if (text?.startsWith("'") && text.endsWith("'")) text = text.slice(1, -1).replaceAll("''", "'")
  const placeholder = text?.match(/^\$\{([^:}]+)(?::([\s\S]*))?\}$/)
  return placeholder ? env[placeholder[1]] ?? placeholder[2] : text
}
const secret = value(scalar(local, 'secret') ?? scalar(main, 'secret'))
if (!secret || Buffer.byteLength(secret) < 32) throw new Error('LOCAL_VERIFICATION_KEY_UNAVAILABLE')
const issuer = value(scalar(local, 'issuer') ?? scalar(main, 'issuer'))
const encode = object => Buffer.from(JSON.stringify(object)).toString('base64url')
const now = Math.floor(Date.now() / 1000)
const unsigned = encode({ alg: 'HS256', typ: 'JWT' }) + '.' + encode({ iss: issuer, sub: '9223372036854775806', iat: now, exp: now + 180 })
const token = unsigned + '.' + createHmac('sha256', secret).update(unsigned).digest('base64url')
async function get(base, path, authenticated = true) {
  const response = await fetch(base + path, { headers: authenticated ? { Authorization: `Bearer ${token}` } : {}, signal: AbortSignal.timeout(15000) })
  if (!response.ok) throw new Error(`LOCAL_READONLY_HTTP_${response.status}`)
  const result = await response.json()
  assert.equal(result.success, true, 'Local read-only API returned a failure')
  return result.data
}
async function verify() {
  const base = 'http://127.0.0.1:18081'
  const stocks = (await get(base, '/api/stocks?limit=100', false)).filter(s => requestedSymbols.includes(s.symbol) && s.market === 'US')
  assert.equal(stocks.length, requestedSymbols.length)
  if (process.argv.includes('--off-hours') || process.argv.includes('--armed')) {
    const armed = process.argv.includes('--armed')
    const checks = [], warnings = new Set()
    for (const stock of stocks) for (const window of ['1H', '1D', '1W', '1M']) {
      const data = await get(base, `/api/market/stocks/${stock.id}/timeline?window=${window}`)
      assert.equal(data.scope, 'REAL'); assert.equal(data.provider, 'ALPACA'); assert.equal(data.feed, 'iex')
      assert.equal(data.health.businessWritesEnabled, armed)
      if (!armed) assert.equal(data.health.market, 'CLOSED')
      assert.ok(data.points.length > 0, 'No cached history for closed-session window')
      assert.ok(data.points.every(p => typeof p.price === 'string' && Number(p.price) > 0 && Number.isFinite(Date.parse(p.eventTime))))
      if (data.dataQuality) {
        assert.equal(data.dataQuality.barSamplingApplied, false)
        assert.equal(data.points.filter(p => p.kind === 'BAR_CLOSE').length, data.dataQuality.availableBars)
        if (!data.dataQuality.historyCoverageComplete || !data.dataQuality.calendarCoverageComplete || data.dataQuality.unavailableMinutes > 0)
          warnings.add('HISTORICAL_COVERAGE_GAPS_RETAINED')
      }
      if (data.health.connection !== 'SUBSCRIBED') warnings.add('CONNECTION_' + data.health.connection)
      if (data.health.lastError) warnings.add(data.health.lastError)
      if (!data.health.stream) warnings.add('STREAM_DIAGNOSTICS_NOT_LOADED')
      const events = await get(base, `/api/market/stocks/${stock.id}/timeline/events?from=${encodeURIComponent(data.range.from)}&to=${encodeURIComponent(data.range.asOf)}`)
      assert.equal(events.events.length, 0) // Unpersisted principal owns no rules or events.
      checks.push({ symbol: stock.symbol, window, points: data.points.length, dataQuality: data.dataQuality, health: data.health })
    }
    const proxy = await get('http://127.0.0.1:5173', `/api/market/stocks/${stocks[0].id}/timeline?window=1D`)
    assert.equal(proxy.health.businessWritesEnabled, armed)
    const report = { checkedAt: new Date().toISOString(), status: warnings.size ? 'PASSED_WITH_WARNINGS' : 'PASSED',
      mode: armed ? 'REAL_WRITES_ENABLED_READONLY_CHECK' : 'CLOSED_SESSION_READONLY', warnings: [...warnings], formalBusinessWritesEnabled: armed,
      newProviderConnections: 0, syntheticPrincipalPersisted: false, actualUserAccountAccessed: false,
      realTradeReceptionValidated: false, realAlertEndToEndValidated: false, checks }
    const directory = reportDirectory('target/v2-p2-offhours')
    writeFileSync(resolve(directory, armed ? 'local-armed-api.json' : 'local-offhours-api.json'), JSON.stringify(report, null, 2) + '\n')
    console.log(JSON.stringify({ status: report.status, windowsChecked: checks.length, eventPagesChecked: checks.length,
      frontendProxyChecked: true, businessWritesEnabled: armed, symbols: requestedSymbols, warnings: report.warnings }))
    return
  }
  if (process.argv.includes('--axis') || process.argv.includes('--axis-before')) {
    const checks = []
    for (const stock of stocks) for (const window of ['1H', '1D', '1W', '1M']) {
      const data = await get(base, `/api/market/stocks/${stock.id}/timeline?window=${window}`)
      assert.equal(data.scope, 'REAL'); assert.equal(data.health.businessWritesEnabled, false)
      const bars = data.points.filter(p => p.kind === 'BAR_CLOSE')
      if (data.dataQuality) {
        assert.equal(data.dataQuality.barSamplingApplied, false)
        assert.equal(bars.length, data.dataQuality.availableBars)
        assert.equal(bars.length, data.dataQuality.displayedBars)
      }
      checks.push({ symbol: stock.symbol, window, range: data.range, points: data.points.length, bars: bars.length,
        firstPointAt: data.points[0]?.eventTime, lastPointAt: data.points.at(-1)?.eventTime,
        sessions: data.sessions?.length ?? null, dataQuality: data.dataQuality ?? null, gaps: data.gaps.length })
    }
    const proxy = await get('http://127.0.0.1:5173', `/api/market/stocks/${stocks[0].id}/timeline?window=1M`)
    assert.equal(proxy.health.businessWritesEnabled, false)
    const report = { checkedAt: new Date().toISOString(), formalBusinessWritesEnabled: false, syntheticPrincipalPersisted: false,
      actualUserAccountAccessed: false, frontendProxyChecked: true, checks }
    const directory = reportDirectory('target/v2-time-axis')
    const name = process.argv.includes('--axis-before') ? 'live-before' : 'live-after'
    writeFileSync(resolve(directory, `${name}.json`), JSON.stringify(report, null, 2) + '\n')
    console.log(JSON.stringify({ report: name, checks: checks.length, frontendProxyChecked: true, businessWritesEnabled: false,
      aapl: checks.filter(c => c.symbol === 'AAPL').map(c => ({ window: c.window, bars: c.bars, dataQuality: c.dataQuality })) }))
    return
  }
  if (process.argv.includes('--p3')) {
    assert.equal(await get(base, '/api/simulations/current'), null)
    assert.equal(await get('http://127.0.0.1:5173', '/api/simulations/current'), null)
    const timeline = await get(base, `/api/market/stocks/${stocks[0].id}/timeline?window=1D`)
    assert.equal(timeline.scope, 'REAL'); assert.equal(timeline.feed, 'iex')
    assert.equal(timeline.health.businessWritesEnabled, false)
    assert.equal(timeline.health.connection, 'SUBSCRIBED')
    assert.ok(timeline.points.length > 0)
    const warnings = timeline.health.lastError ? [timeline.health.lastError] : []
    const report = { checkedAt: new Date().toISOString(), status: warnings.length ? 'PASSED_WITH_WARNINGS' : 'PASSED', warnings,
      simulationCurrentEndpointChecked: true, noSimulationForUnpersistedPrincipal: true, frontendProxyChecked: true,
      historicalTimelineChecked: true, historicalPoints: timeline.points.length, health: timeline.health,
      formalBusinessWritesEnabled: false, syntheticPrincipalPersisted: false, actualUserAccountAccessed: false,
      credentialsOrTokenLogged: false, realAlertEndToEndValidated: false }
    const directory = reportDirectory('target/v2-p3')
    writeFileSync(resolve(directory, 'local-readonly-api.json'), JSON.stringify(report, null, 2) + '\n')
    console.log(JSON.stringify({ status: report.status, simulationCurrentEndpointChecked: true, frontendProxyChecked: true,
      historicalPoints: timeline.points.length, connection: timeline.health.connection, businessWritesEnabled: false, warnings }))
    return
  }
  const timelines = []
  for (const stock of stocks) for (const window of ['1H', '1D', '1W', '1M']) {
    const data = await get(base, `/api/market/stocks/${stock.id}/timeline?window=${window}`)
    assert.equal(data.scope, 'REAL'); assert.equal(data.provider, 'ALPACA'); assert.equal(data.feed, 'iex')
    assert.equal(data.health.businessWritesEnabled, false); assert.equal(data.health.connection, 'SUBSCRIBED')
    assert.ok(data.health.lastObservedAt, 'No actual observed trade for symbol')
    assert.ok(data.points.length > 0, 'No cached real history for window')
    assert.ok(data.points.every(p => p.kind === 'BAR_CLOSE' && typeof p.price === 'string'))
    assert.equal(data.stock.priceKind, 'HISTORICAL_BAR_CLOSE')
    assert.equal(data.events.length, 0); assert.equal(data.thresholds.length, 0)
    const events = await get(base, `/api/market/stocks/${stock.id}/timeline/events?from=${encodeURIComponent(data.range.from)}&to=${encodeURIComponent(data.range.asOf)}`)
    assert.equal(events.events.length, 0)
    timelines.push({ symbol: stock.symbol, window, points: data.points.length, priceKind: data.stock.priceKind,
      health: data.health, range: data.range, coverage: data.coverage })
  }
  const proxy = await get('http://127.0.0.1:5173', `/api/market/stocks/${stocks[0].id}/timeline?window=1D`)
  assert.equal(proxy.feed, 'iex'); assert.equal(proxy.health.businessWritesEnabled, false); assert.ok(proxy.points.length > 0)
  const warnings = [...new Set(timelines.map(t => t.health.lastError).filter(Boolean))]
  const report = { checkedAt: new Date().toISOString(), status: warnings.length ? 'PASSED_WITH_WARNINGS' : 'PASSED', warnings, formalBusinessWritesEnabled: false,
    syntheticPrincipalPersisted: false, actualUserAccountAccessed: false, credentialsOrTokenLogged: false,
    timelineChecks: timelines.length, independentEventPageChecks: timelines.length, frontendProxyChecked: true,
    realAlertEndToEndValidated: false, timelines }
  const directory = reportDirectory('target/v2-p2')
  writeFileSync(resolve(directory, 'local-readonly-api.json'), JSON.stringify(report, null, 2) + '\n')
  console.log(JSON.stringify({ status: report.status, timelineChecks: timelines.length, eventPageChecks: timelines.length,
    frontendProxyChecked: true, connection: proxy.health.connection, businessWritesEnabled: false,
    observedTrades: proxy.health.observedTrades, queueOverflows: proxy.health.queueOverflows, warnings, lastSafeError: proxy.health.lastError }))
}
verify().catch(error => {
  console.error(error instanceof assert.AssertionError ? 'LOCAL_READONLY_ASSERTION_FAILED: ' + error.message
    : /^LOCAL_[A-Z0-9_]+$/.test(error.message) ? error.message : 'LOCAL_READONLY_CHECK_FAILED')
  process.exitCode = 1
})
