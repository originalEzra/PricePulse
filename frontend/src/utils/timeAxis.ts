import type { TradingSession } from '../api/types'
export type { TradingSession } from '../api/types'
export interface AxisBreak { from: number; to: number; left: number; right: number }
export interface TimeAxis {
  position(time: number): number
  timeAt(position: number): number
  breaks: AxisBreak[]
  ticks(count: number): Array<{ at: number; position: number }>
}

/** Compress only closures proven by adjacent calendar sessions; never infer them from sparse quotes. */
export function createTimeAxis(from: number, to: number, sessions: TradingSession[] = [], compress = false, width = 640): TimeAxis {
  const calendar = sessions.map(s => ({ open: Date.parse(s.open), close: Date.parse(s.close) }))
    .filter(s => Number.isFinite(s.open) && Number.isFinite(s.close) && s.close > s.open).sort((a, b) => a.open - b.open)
  const merged: Array<{ open: number; close: number }> = []
  for (const session of calendar) {
    const previous = merged.at(-1)
    if (previous && session.open <= previous.close) previous.close = Math.max(previous.close, session.close)
    else merged.push({ ...session })
  }
  const closures: Array<{ from: number; to: number }> = []
  if (compress && to > from) for (let i = 1; i < merged.length; i++) {
    const a = Math.max(from, merged[i - 1]!.close), b = Math.min(to, merged[i]!.open)
    if (b > a) closures.push({ from: a, to: b })
  }
  const active = to - from - closures.reduce((sum, gap) => sum + gap.to - gap.from, 0)
  if (active <= 0) closures.length = 0
  // A narrow, explicit gutter per closure; at least 76% of the plot remains for elapsed trading time.
  const gapSize = closures.length ? Math.min(12 / Math.max(1, width), 0.24 / closures.length) : 0
  const scale = (1 - gapSize * closures.length) / Math.max(1, closures.length ? active : to - from)
  const parts: Array<{ from: number; to: number; left: number; right: number; closed: boolean }> = []
  let cursor = from, position = 0
  for (const gap of closures) {
    if (gap.from > cursor) {
      const right = position + (gap.from - cursor) * scale
      parts.push({ from: cursor, to: gap.from, left: position, right, closed: false }); position = right
    }
    parts.push({ ...gap, left: position, right: position + gapSize, closed: true })
    cursor = gap.to; position += gapSize
  }
  if (to > cursor) parts.push({ from: cursor, to, left: position, right: 1, closed: false })
  const breaks = parts.filter(p => p.closed).map(({ from, to, left, right }) => ({ from, to, left, right }))
  const project = (time: number) => {
    if (to <= from) return 0.5
    const at = Math.max(from, Math.min(to, time)), part = parts.find(p => at <= p.to)
    return part ? part.left + timeFraction(at, part.from, part.to) * (part.right - part.left) : 1
  }
  const inverse = (value: number) => {
    const at = Math.max(0, Math.min(1, value)), part = parts.find(p => at <= p.right)
    return part ? part.from + timeFraction(at, part.left, part.right) * (part.to - part.from) : from
  }
  return {
    position: project, timeAt: inverse, breaks,
    ticks(count) {
      const result: Array<{ at: number; position: number }> = []
      for (let i = 0; i < Math.max(2, count); i++) {
        const target = i / (Math.max(2, count) - 1), gap = breaks.find(b => target > b.left && target < b.right)
        const at = gap ? target - gap.left < gap.right - target ? gap.from : gap.to : inverse(target)
        if (!result.some(t => Math.abs(t.at - at) < 1)) result.push({ at, position: project(at) })
      }
      return result
    },
  }
}

export function timeFraction(time: number, from: number, to: number): number {
  return to > from ? (time - from) / (to - from) : 0.5
}

/** Keep sub-millisecond precision in the label; JS Date is used only for NY calendar formatting. */
export function formatNewYorkTimestamp(raw: string): string {
  const date = new Date(raw)
  if (!Number.isFinite(date.getTime())) return raw
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'America/New_York', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23', timeZoneName: 'short' }).formatToParts(date)
  const part = (name: string) => parts.find(p => p.type === name)?.value ?? ''
  const fraction = raw.match(/\.(\d+)(?:Z|[+-]\d{2}:\d{2})$/)?.[1]
  return `${part('year')}-${part('month')}-${part('day')} ${part('hour')}:${part('minute')}:${part('second')}${fraction ? '.' + fraction : ''} ${part('timeZoneName')}（纽约）`
}

export function barGapRanges(points: Array<{ kind: string; eventTime: string; intervalStart?: string | null; session: string }>): Array<{ from: string; to: string }> {
  return points.flatMap((point, i) => {
    const previous = points[i - 1]
    return previous?.kind === 'BAR_CLOSE' && point.kind === 'BAR_CLOSE' && previous.session === point.session
      && point.intervalStart && Date.parse(point.intervalStart) > Date.parse(previous.eventTime)
      ? [{ from: previous.eventTime, to: point.intervalStart }] : []
  })
}
export function nearestTimeIndex(sortedTimes: number[], target: number): number | null {
  if (!sortedTimes.length) return null
  let low = 0, high = sortedTimes.length - 1
  while (low < high) {
    const middle = Math.floor((low + high) / 2)
    if (sortedTimes[middle]! < target) low = middle + 1
    else high = middle
  }
  return low > 0 && target - sortedTimes[low - 1]! <= sortedTimes[low]! - target ? low - 1 : low
}
export function segments<T>(points: T[], group: (p: T) => string, breaks: (a: T, b: T) => boolean): T[][] {
  const result: T[][] = []
  for (const p of points) {
    const current = result.at(-1), previous = current?.at(-1)
    if (!current || previous == null || group(previous) !== group(p) || breaks(previous, p)) result.push([p])
    else current.push(p)
  }
  return result
}
