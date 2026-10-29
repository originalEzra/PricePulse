import type { SimulationBatch, SimulationEvent, SimulationRun, SimulationTimeline, StartSimulation } from '../api/types'

export interface Pending<T> { key: string; body: T }

export function simulationSeed(input: string): string | null {
  const value = input.trim()
  if (!value) return null
  if (!/^-?[0-9]{1,19}$/.test(value)) throw new Error('Seed 需要是 64 位整数，或留空。')
  const seed = BigInt(value)
  if (seed < -9223372036854775808n || seed > 9223372036854775807n) throw new Error('Seed 超出 64 位整数范围。')
  return seed.toString()
}

/** A lost response keeps the original command; only an acknowledged result or a definite 4xx clears it. */
export class RetriableSimulationRequest<T> {
  pending: Pending<T> | null = null
  private newKey: () => string
  constructor(newKey: () => string = () => crypto.randomUUID()) { this.newKey = newKey }
  prepare(body: T): Pending<T> {
    this.pending ??= { key: this.newKey(), body: structuredClone(body) }
    return this.pending
  }
  acknowledge() { this.pending = null }
  fail(status: number) { if (status >= 400 && status < 500) this.pending = null }
}

export function acceptsRunVersion(current: { runId: string; version: number } | null,
  incoming: { runId: string; version: number } | null, requestEpoch: number, currentEpoch: number): boolean {
  return requestEpoch === currentEpoch && (!current || !incoming || current.runId !== incoming.runId || incoming.version >= current.version)
}

interface SimulationTransport {
  current(signal?: AbortSignal): Promise<SimulationRun | null>
  start(body: StartSimulation, key: string): Promise<SimulationRun>
  advance(runId: string, body: { expectedVersion: number; expectedBatchNo: number }, key: string): Promise<SimulationBatch>
  timeline(runId: string, from?: string, to?: string, cursor?: string, signal?: AbortSignal): Promise<SimulationTimeline>
  readEvents(runId: string, from: string, to: string, signal: AbortSignal): Promise<SimulationEvent[]>
}
interface PlaybackTimers { set(task: () => void, delay: number): unknown; clear(handle: unknown): void }
type AdvanceCommand = { runId: string; expectedVersion: number; expectedBatchNo: number }
const conflictMessage = '实验已被其他页面推进或替换，自动播放已暂停。请恢复已保存结果后继续。'

/** One owner-scoped session. Writes are never aborted: an accepted hour may finish after pause/leave. */
export class SimulationSession {
  run: SimulationRun | null = null
  timeline: SimulationTimeline | null = null
  events: SimulationEvent[] = []
  eventsReady = false
  busy = false
  active = false
  playing = false
  delayMs = 2000
  needsRefresh = false
  error = ''
  notice = '已暂停'
  startCommands: RetriableSimulationRequest<StartSimulation>
  advanceCommands: RetriableSimulationRequest<AdvanceCommand>
  private api: SimulationTransport
  private timers: PlaybackTimers
  private timer: unknown = null
  private epoch = 0
  private operation = 0
  private playbackEpoch = 0
  private reader: AbortController | null = null
  private restoreOnIdle = false
  private floor: { runId: string; version: number } | null = null

  constructor(api: SimulationTransport, timers: PlaybackTimers = {
    set: (task, delay) => setTimeout(task, delay), clear: handle => clearTimeout(handle as ReturnType<typeof setTimeout>),
  }, newKey?: () => string) {
    this.api = api; this.timers = timers
    this.startCommands = new RetriableSimulationRequest(newKey)
    this.advanceCommands = new RetriableSimulationRequest(newKey)
  }
  get ready() { return this.active && !this.busy && !this.needsRefresh && !!this.run?.generatorSupported
    && this.timeline?.runId === this.run.runId && this.timeline.version === this.run.version && this.eventsReady }
  get canPlay() { return this.ready && !this.startCommands.pending && !this.advanceCommands.pending }

  async enter() {
    this.active = true; this.pause()
    if (this.busy) { this.restoreOnIdle = true; return }
    await this.restore()
  }
  leave(reason = '已离开页面，自动播放已暂停。') {
    this.active = false; this.restoreOnIdle = false; this.pause(reason); this.reader?.abort()
  }
  reset() {
    this.leave(); ++this.epoch; ++this.operation
    this.run = null; this.timeline = null; this.events = []; this.eventsReady = false; this.busy = false
    this.error = ''; this.notice = '已暂停'; this.needsRefresh = false; this.floor = null; this.delayMs = 2000
    this.startCommands.acknowledge(); this.advanceCommands.acknowledge()
  }
  pause(reason = this.busy ? '已暂停；已受理的一小时完成后停止。' : '已暂停') {
    this.playing = false; ++this.playbackEpoch; this.notice = reason
    if (this.timer !== null) this.timers.clear(this.timer)
    this.timer = null
  }
  play() {
    if (!this.canPlay || this.playing) return
    this.playing = true; this.notice = '自动播放中'; const generation = ++this.playbackEpoch
    void this.tick(generation)
  }
  setDelay(delay: number) {
    if (![1000, 2000, 5000].includes(delay)) return
    this.delayMs = delay
    if (this.playing && this.timer !== null) {
      this.timers.clear(this.timer); this.timer = null
      this.schedule(++this.playbackEpoch)
    }
  }
  private schedule(generation: number) {
    this.timer = this.timers.set(() => {
      if (generation !== this.playbackEpoch) return
      this.timer = null; void this.tick(generation)
    }, this.delayMs)
  }
  private async tick(generation: number) {
    if (!this.active || !this.playing || generation !== this.playbackEpoch) return
    const advanced = await this.advance(true)
    if (advanced && this.active && this.playing && generation === this.playbackEpoch) this.schedule(generation)
  }
  private async work(task: (valid: () => boolean) => Promise<void>) {
    if (this.busy || !this.active) return false
    const owner = this.epoch, operation = ++this.operation
    const valid = () => owner === this.epoch && operation === this.operation
    this.busy = true; this.error = ''
    try { await task(valid); return valid() }
    catch (cause) {
      if (valid() && !(cause instanceof Error && cause.name === 'AbortError')) {
        this.error = cause instanceof Error ? cause.message : '读取实验失败，请恢复已保存结果。'
        this.pause('已暂停，请处理上方提示后继续。')
      }
      return false
    } finally {
      if (valid()) {
        this.busy = false
        if (this.restoreOnIdle && this.active) { this.restoreOnIdle = false; void this.restore() }
      }
    }
  }
  private async hydrate(valid: () => boolean, expected?: { runId: string; version: number }, batch?: SimulationBatch) {
    const reader = new AbortController(); this.reader?.abort(); this.reader = reader
    const live = () => valid() && !reader.signal.aborted && this.active
    try {
      const run = await this.api.current(reader.signal)
      if (!live()) return
      if (expected && (!run || run.runId !== expected.runId)) throw Object.assign(new Error(conflictMessage), { status: 409 })
      const floor = expected ?? this.floor
      if ((!run && floor) || !acceptsRunVersion(this.run, run, 0, 0) || (run && floor?.runId === run.runId && run.version < floor.version))
        throw new Error('读取到旧实验版本，请恢复已保存结果。')
      if (!run) { this.run = null; this.timeline = null; this.events = []; this.eventsReady = true; this.needsRefresh = false; return }
      const data = await this.api.timeline(run.runId, undefined, undefined, undefined, reader.signal)
      if (!live()) return
      if (data.runId !== run.runId || data.version !== run.version) throw Object.assign(new Error(conflictMessage), { status: 409 })
      const events = await this.api.readEvents(run.runId, data.range.from, data.range.to, reader.signal)
      if (!live()) return
      // Keep all previously loaded immutable points plus the actual committed batch; never synthesize a tail.
      const points = new Map((this.timeline?.runId === run.runId ? this.timeline.simulation : []).map(p => [p.pointSeq, p]))
      data.simulation.forEach(p => points.set(p.pointSeq, p))
      if (batch?.runId === run.runId) batch.points.forEach(p => points.set(p.pointSeq, p))
      const visited = new Set<string>()
      while (data.pointCursor && points.size < run.nextPointSeq - 1) {
        const cursor = data.pointCursor
        if (visited.has(cursor)) throw new Error('价格分页未完成，请恢复保存结果。')
        visited.add(cursor)
        const page = await this.api.timeline(run.runId, data.range.from, data.range.to, cursor, reader.signal)
        if (!live()) return
        if (page.runId !== data.runId || page.watermark !== data.watermark || page.version !== data.version)
          throw new Error('价格分页水位改变，请恢复保存结果。')
        page.simulation.forEach(p => points.set(p.pointSeq, p)); data.pointCursor = page.pointCursor
      }
      data.simulation = [...points.values()].sort((a, b) => a.pointSeq - b.pointSeq)
      if (data.simulation.length !== run.nextPointSeq - 1) throw new Error('已保存价格尚未完整读取，请恢复保存结果。')
      data.pointCursor = null
      this.run = run; this.timeline = data; this.events = events; this.eventsReady = true
      this.floor = { runId: run.runId, version: run.version }; this.needsRefresh = false; this.restoreOnIdle = false
    } finally { if (this.reader === reader) this.reader = null }
  }
  async restore() {
    this.pause('正在恢复保存结果，恢复后保持暂停。')
    return this.work(async valid => {
      this.needsRefresh = true
      await this.hydrate(valid)
      if (!valid() || !this.active) return
      const pending = this.advanceCommands.pending?.body
      if (pending && this.run && (this.run.runId !== pending.runId ||
        (this.run.version > pending.expectedVersion && this.run.lastBatchNo > pending.expectedBatchNo))) this.advanceCommands.acknowledge()
      // A read cannot prove an unacknowledged start key committed. Only retrying that same key can settle it.
      this.notice = this.startCommands.pending || this.advanceCommands.pending ? '请求结果仍待确认，请重试原请求。' : '已恢复保存结果 · 已暂停'
    })
  }
  async start(input?: StartSimulation) {
    if (this.busy || !this.active || this.advanceCommands.pending || this.needsRefresh || this.playing) return false
    if (!this.startCommands.pending && !input) return false
    const pending = this.startCommands.pending ?? this.startCommands.prepare(input!)
    this.pause()
    return this.work(async valid => {
      try {
        const result = await this.api.start(pending.body, pending.key)
        if (!valid()) return
        this.startCommands.acknowledge(); this.floor = { runId: result.runId, version: result.version }; this.needsRefresh = true
        if (this.active) await this.hydrate(valid, this.floor)
      } catch (cause) { if (valid()) this.failCommand(this.startCommands, cause); throw cause }
    })
  }
  async advance(automatic = false) {
    if (this.busy || !this.active || !this.run || this.startCommands.pending || (!automatic && this.playing)
      || (!this.advanceCommands.pending && !this.ready)) return false
    const pending = this.advanceCommands.prepare({ runId: this.run.runId, expectedVersion: this.run.version, expectedBatchNo: this.run.lastBatchNo })
    return this.work(async valid => {
      try {
        const result = await this.api.advance(pending.body.runId,
          { expectedVersion: pending.body.expectedVersion, expectedBatchNo: pending.body.expectedBatchNo }, pending.key)
        if (!valid()) return
        if (result.runId !== pending.body.runId || result.versionAfter <= pending.body.expectedVersion) throw new Error('推进结果不匹配，请恢复保存结果。')
        this.advanceCommands.acknowledge(); this.floor = { runId: result.runId, version: result.versionAfter }; this.needsRefresh = true
        if (this.active) await this.hydrate(valid, this.floor, result)
      } catch (cause) { if (valid()) this.failCommand(this.advanceCommands, cause); throw cause }
    })
  }
  private failCommand<T>(command: RetriableSimulationRequest<T>, cause: unknown) {
    const status = cause && typeof cause === 'object' && 'status' in cause && typeof cause.status === 'number' ? cause.status : 0
    command.fail(status)
    if (status === 409 || status === 404) { this.needsRefresh = true; throw new Error(conflictMessage) }
  }
  async morePoints() {
    const data = this.timeline
    if (!data?.pointCursor || this.playing || this.needsRefresh) return false
    return this.work(async valid => {
      const reader = new AbortController(); this.reader = reader
      try {
        const page = await this.api.timeline(data.runId, data.range.from, data.range.to, data.pointCursor!, reader.signal)
        if (!valid() || reader.signal.aborted || this.timeline !== data) return
        if (page.runId !== data.runId || page.watermark !== data.watermark) throw new Error('价格分页已更新，请恢复保存结果。')
        const points = new Map(data.simulation.map(p => [p.pointSeq, p])); page.simulation.forEach(p => points.set(p.pointSeq, p))
        data.simulation = [...points.values()].sort((a, b) => a.pointSeq - b.pointSeq); data.pointCursor = page.pointCursor
      } finally { if (this.reader === reader) this.reader = null }
    })
  }
}
