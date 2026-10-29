import test from 'node:test'
import assert from 'node:assert/strict'
import { SimulationSession } from '../src/utils/simulationRequests.ts'
import { simulationPointBreaks } from '../src/utils/simulationDisplay.ts'
import type { SimulationBatch, SimulationRun, SimulationTimeline, StartSimulation } from '../src/api/types.ts'

function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (cause: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const flush = () => new Promise<void>(resolve => setImmediate(resolve))
const failure = (status: number) => Object.assign(new Error('Synthetic failure'), { status })
const origin = '2026-10-28T13:30:00Z'
const point = (seq: number) => ({ pointSeq: seq, eventTime: new Date(Date.parse(origin) + seq * 60000).toISOString(), price: (100 + seq / 1000).toFixed(4) })
const run = (id = 'run-a', version = 0): SimulationRun => ({ runId: id, stockId: 1, version, lastBatchNo: version,
  simClock: point(version * 60).eventTime, latestPrice: point(version * 60).price, nextPointSeq: version * 60 + 1,
  seed: '42', generatorVersion: 'synthetic', generatorSupported: true, ruleSnapshots: [], stats: { triggered: 0, rearmed: 0 },
  origin: { kind: 'HISTORICAL_POINT', stockSymbol: 'SYNTHETIC', provider: 'ALPACA', feed: 'iex', calendarVersion: 'synthetic',
    prefixFrom: origin, point: { pointRef: 'bar:1', eventTime: origin, epochNanos: '0', price: '100.0000', kind: 'BAR_CLOSE',
      intervalStart: origin, intervalEnd: origin, availableAt: origin, revision: '1', session: '2026-10-28' } },
  parameters: { mode: 'NORMAL', stepRatio: '0.003' } })

function fixture(initial = run()) {
  let current: SimulationRun | null = initial, keys = 0, reads = 0
  const calls: Array<{ id: string; body: { expectedVersion: number; expectedBatchNo: number }; key: string }> = []
  const batches = new Map<string, SimulationBatch>(), starts = new Map<string, SimulationRun>()
  const api = {
    current: async (_signal?: AbortSignal) => { ++reads; return structuredClone(current) },
    timeline: async (id: string, _from?: string, _to?: string, cursor?: string, _signal?: AbortSignal): Promise<SimulationTimeline> => {
      if (!current || current.runId !== id) throw failure(404)
      const row = structuredClone(current), first = cursor ? Number(cursor) : 1, last = Math.min(row.nextPointSeq - 1, first + 599)
      return { scope: 'SIMULATION', runId: id, stockId: 1, version: row.version, simClock: row.simClock, origin: row.origin,
        range: { from: origin, to: row.simClock, marketTimeZone: 'America/New_York' }, sessions: [], realPrefix: [],
        simulation: Array.from({ length: Math.max(0, last - first + 1) }, (_, i) => point(first + i)),
        thresholds: [], stats: row.stats, pointCursor: row.nextPointSeq - 1 > last ? String(last + 1) : null, watermark: String(row.version) }
    },
    readEvents: async () => [],
    advance: async (id: string, body: { expectedVersion: number; expectedBatchNo: number }, key: string): Promise<SimulationBatch> => {
      calls.push({ id, body: structuredClone(body), key })
      if (batches.has(key)) return structuredClone(batches.get(key)!)
      if (!current || current.runId !== id) throw failure(404)
      if (body.expectedVersion !== current.version || body.expectedBatchNo !== current.lastBatchNo) throw failure(409)
      const previous = current; current = run(id, current.version + 1)
      const batch = { runId: id, versionAfter: current.version, batchNo: current.lastBatchNo, fromClock: previous.simClock,
        toClock: current.simClock, firstPointSeq: previous.nextPointSeq, lastPointSeq: current.nextPointSeq - 1,
        triggered: 0, rearmed: 0, points: Array.from({ length: 60 }, (_, i) => point(previous.nextPointSeq + i)) }
      batches.set(key, batch); return structuredClone(batch)
    },
    start: async (body: StartSimulation, key: string) => {
      if (starts.has(key)) {
        if (current?.runId !== starts.get(key)!.runId) throw failure(409)
        return structuredClone(starts.get(key)!)
      }
      if (body.expectedCurrentRunId !== (current?.runId ?? null)) throw failure(409)
      current = run('replacement'); starts.set(key, current); return structuredClone(current)
    },
  }
  const tasks = new Map<number, () => void>(), delays: number[] = []; let timer = 0
  const timers = { set(task: () => void, delay: number) { tasks.set(++timer, task); delays.push(delay); return timer },
    clear(id: unknown) { tasks.delete(id as number) } }
  const session = new SimulationSession(api, timers, () => `key-${++keys}`)
  return { api, timers, session, calls, tasks, delays, get reads() { return reads }, get current() { return current },
    replace(value: SimulationRun | null) { current = value }, fire() { const [id, task] = [...tasks][0]!; tasks.delete(id); task() } }
}

test('playback is serial, and pause while in flight accepts exactly that hour without scheduling another', async () => {
  const f = fixture(); await f.session.enter()
  const gate = deferred<SimulationBatch>(), advance = f.api.advance
  let requests = 0
  f.api.advance = async () => { ++requests; return gate.promise }
  f.session.play(); f.session.play()
  assert.equal(await f.session.advance(), false)
  assert.equal(requests, 1); assert.equal(f.tasks.size, 0)
  f.session.pause(); gate.resolve(await advance('run-a', { expectedVersion: 0, expectedBatchNo: 0 }, 'commit'))
  await flush()
  assert.equal(f.session.run!.version, 1); assert.equal(f.session.playing, false); assert.equal(f.tasks.size, 0)
})

test('changing speed reschedules only the wait; stale callbacks after pause cannot dispatch a batch', async () => {
  const f = fixture(); await f.session.enter(); f.session.play(); await flush()
  assert.deepEqual(f.delays, [2000]); const stale = [...f.tasks.values()][0]!
  f.session.setDelay(5000); assert.deepEqual(f.delays, [2000, 5000])
  stale(); await flush(); assert.equal(f.calls.length, 1)
  f.fire(); await flush(); assert.equal(f.calls.length, 2)
  assert.deepEqual(f.calls.map(c => c.body), [{ expectedVersion: 0, expectedBatchNo: 0 }, { expectedVersion: 1, expectedBatchNo: 1 }])
  const canceled = [...f.tasks.values()][0]!; f.session.pause(); canceled(); await flush()
  assert.equal(f.calls.length, 2); assert.equal(f.tasks.size, 0)
})

test('leaving stops new requests; an accepted hour can finish and re-entry restores paused', async () => {
  const f = fixture(); await f.session.enter()
  const gate = deferred<SimulationBatch>(), advance = f.api.advance
  f.api.advance = async () => gate.promise
  f.session.play(); f.session.leave()
  gate.resolve(await advance('run-a', { expectedVersion: 0, expectedBatchNo: 0 }, 'accepted'))
  await flush(); assert.equal(f.reads, 1); assert.equal(f.session.needsRefresh, true); assert.equal(f.tasks.size, 0)
  await f.session.enter()
  assert.equal(f.session.run!.version, 1); assert.equal(f.session.playing, false); assert.equal(f.tasks.size, 0)
})

test('a lost response retries the same key/body and does not create another hour', async () => {
  const f = fixture(); await f.session.enter()
  const advance = f.api.advance; let lost = true
  f.api.advance = async (...args) => { const result = await advance(...args); if (lost) { lost = false; throw failure(0) } return result }
  f.session.play(); await flush()
  assert.equal(f.session.playing, false); assert.equal(f.session.advanceCommands.pending!.key, 'key-1')
  assert.equal(f.tasks.size, 0); assert.equal(f.session.canPlay, false)
  await f.session.advance()
  assert.equal(f.current!.version, 1); assert.equal(f.session.run!.version, 1)
  assert.equal(f.calls.length, 2); assert.deepEqual(f.calls[0], f.calls[1]); assert.equal(f.session.advanceCommands.pending, null)
})

test('querying an unchanged saved result does not discard an unknown request', async () => {
  const f = fixture(); await f.session.enter()
  const advance = f.api.advance; f.api.advance = async () => { throw failure(503) }
  await f.session.advance(); const pending = structuredClone(f.session.advanceCommands.pending)
  await f.session.restore()
  assert.deepEqual(f.session.advanceCommands.pending, pending); assert.equal(f.session.canPlay, false)
  f.api.advance = advance; await f.session.advance(); assert.equal(f.current!.version, 1)
})

test('two tabs use distinct keys; the stale tab stops on conflict and requires explicit restore', async () => {
  const f = fixture(), second = new SimulationSession(f.api, f.timers, () => 'tab-b-key')
  await f.session.enter(); await second.enter()
  await f.session.advance(); second.play(); await flush()
  assert.equal(f.current!.version, 1); assert.equal(second.playing, false); assert.equal(second.needsRefresh, true)
  assert.match(second.error, /其他页面/); assert.equal(second.advanceCommands.pending, null)
  assert.equal(second.run!.version, 0)
  await second.restore(); assert.equal(second.run!.version, 1); assert.equal(second.canPlay, true); assert.equal(second.playing, false)
})

test('an externally replaced experiment cannot receive an old page response', async () => {
  const f = fixture(); await f.session.enter(); f.replace(run('external'))
  f.session.play(); await flush()
  assert.equal(f.session.playing, false); assert.equal(f.session.needsRefresh, true); assert.equal(f.current!.version, 0)
  assert.equal(f.session.run!.runId, 'run-a'); await f.session.restore()
  assert.equal(f.session.run!.runId, 'external'); assert.equal(f.session.timeline!.runId, 'external')
})

test('account reset fences both delayed reads and writes, including their final busy state', async () => {
  const f = fixture(), current = f.api.current, oldRead = deferred<SimulationRun | null>()
  f.api.current = async () => oldRead.promise
  const first = f.session.enter(); f.session.reset(); f.replace(run('user-b')); f.api.current = current
  await f.session.enter(); oldRead.resolve(run('user-a')); await first
  assert.equal(f.session.run!.runId, 'user-b'); assert.equal(f.session.busy, false)
  const oldWrite = deferred<SimulationBatch>(); f.api.advance = async () => oldWrite.promise
  const writing = f.session.advance(); f.session.reset(); f.replace(run('user-c')); await f.session.enter()
  oldWrite.resolve({ runId: 'user-b', versionAfter: 1 } as SimulationBatch); await writing
  assert.equal(f.session.run!.runId, 'user-c'); assert.equal(f.session.busy, false); assert.equal(f.session.advanceCommands.pending, null)
})

test('an acknowledged write followed by an old GET cannot start another batch', async () => {
  const f = fixture(); await f.session.enter(); const old = structuredClone(f.current), current = f.api.current
  f.api.current = async () => old
  f.session.play(); await flush()
  assert.equal(f.current!.version, 1); assert.equal(f.session.needsRefresh, true); assert.equal(f.session.playing, false)
  assert.equal(f.session.advanceCommands.pending, null); assert.equal(f.session.canPlay, false)
  assert.equal(await f.session.advance(), false); assert.equal(f.calls.length, 1)
  f.api.current = async () => null; await f.session.restore()
  assert.equal(f.session.run!.version, 0); assert.equal(f.session.needsRefresh, true)
  f.api.current = current; await f.session.restore(); assert.equal(f.session.run!.version, 1)
})

test('current/timeline races never publish mixed rule, price and clock versions', async () => {
  const f = fixture(); await f.session.enter(); const timeline = f.api.timeline
  f.api.timeline = async (...args) => { f.replace(run('run-a', 1)); return timeline(...args) }
  await f.session.restore()
  assert.equal(f.session.run!.version, 0); assert.equal(f.session.timeline!.version, 0)
  assert.equal(f.session.needsRefresh, true); assert.match(f.session.error, /其他页面/)
  f.api.timeline = timeline; await f.session.restore(); assert.equal(f.session.run!.version, 1)
})

test('start retry retains its key across leave and query; a failed replacement leaves the old experiment intact', async () => {
  const f = fixture(); await f.session.enter(); const start = f.api.start
  const input: StartSimulation = { stockId: 1, origin: { kind: 'HISTORICAL_POINT', pointRef: 'bar:1', revision: '1' },
    mode: 'NORMAL', stepRatio: '0.003', seed: '42', expectedCurrentRunId: 'run-a' }
  let lost = true
  f.api.start = async (...args) => { const result = await start(...args); if (lost) { lost = false; throw failure(0) } return result }
  await f.session.start(input); const pending = structuredClone(f.session.startCommands.pending)
  f.session.leave(); await f.session.enter()
  assert.deepEqual(f.session.startCommands.pending, pending); assert.equal(f.session.canPlay, false)
  await f.session.start(); assert.equal(f.session.startCommands.pending, null); assert.equal(f.session.run!.runId, 'replacement')
  f.api.start = async () => { throw failure(422) }
  await f.session.start({ ...input, expectedCurrentRunId: 'replacement' })
  assert.equal(f.session.run!.runId, 'replacement'); assert.equal(f.current!.runId, 'replacement')
})

test('restoring beyond 600 points reads the fixed pages; advancing preserves them without fetching them again', async () => {
  const f = fixture(run('run-a', 12)); await f.session.enter()
  assert.equal(f.session.timeline!.simulation.length, 720); assert.equal(f.session.timeline!.pointCursor, null)
  const timeline = f.api.timeline; let pagedReads = 0
  f.api.timeline = async (...args) => { if (args[3]) ++pagedReads; return timeline(...args) }
  await f.session.advance()
  assert.equal(f.session.timeline!.simulation.length, 780)
  assert.equal(pagedReads, 0)
  assert.deepEqual(simulationPointBreaks(f.session.timeline!.simulation), [])
  assert.equal(f.session.timeline!.simulation.at(-1)!.price, point(780).price)
  assert.deepEqual(simulationPointBreaks([point(1), point(3)]), [point(3).eventTime])
})

test('bad or repeated price cursors fail visibly without publishing a truncated chart', async () => {
  const f = fixture(run('run-a', 12)), timeline = f.api.timeline
  f.api.timeline = async (...args) => ({ ...await timeline(...args), ...(args[3] ? { watermark: 'changed' } : {}) })
  await f.session.enter(); assert.equal(f.session.run, null); assert.match(f.session.error, /分页水位/)
  f.api.timeline = async (id) => ({ ...await timeline(id), pointCursor: 'cycle' })
  await f.session.restore(); assert.equal(f.session.run, null); assert.match(f.session.error, /分页未完成/)
})

test('changing speed does not change the saved deterministic path', async () => {
  const a = fixture(), b = fixture(); await a.session.enter(); await b.session.enter()
  a.session.setDelay(1000); b.session.setDelay(5000)
  a.session.play(); b.session.play(); await flush(); a.fire(); b.fire(); await flush()
  a.session.pause(); b.session.pause()
  assert.deepEqual(a.session.timeline!.simulation, b.session.timeline!.simulation)
  assert.deepEqual(a.current, b.current)
})
