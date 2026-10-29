import type { SimulationEvent, SimulationEventPage, SimulationPoint } from '../api/types'

/** A missing saved page is not a continuous simulated line. */
export function simulationPointBreaks(points: SimulationPoint[]): string[] {
  return points.filter((point, i) => point.pointSeq !== (points[i - 1]?.pointSeq ?? 0) + 1).map(point => point.eventTime)
}

export async function collectSimulationEvents(first: SimulationEventPage,
  next: (cursor: string) => Promise<SimulationEventPage>): Promise<SimulationEvent[]> {
  const events = new Map(first.events.map(event => [event.id, event]))
  const visited = new Set<string>(); let cursor = first.eventCursor
  while (cursor) {
    if (visited.has(cursor)) throw new Error('模拟事件分页未完成，请重新读取保存结果。')
    visited.add(cursor)
    const page = await next(cursor)
    if (page.watermark !== first.watermark) throw new Error('模拟事件查询水位改变，请重新读取保存结果。')
    page.events.forEach(event => events.set(event.id, event)); cursor = page.eventCursor
  }
  return [...events.values()]
}

export function simulationTriggerCounts(events: SimulationEvent[]): Map<number, number> {
  const counts = new Map<number, number>(), seen = new Set<number>()
  for (const event of events) if (event.eventType === 'TRIGGERED' && !seen.has(event.id)) {
    seen.add(event.id); counts.set(event.snapshotRuleId, (counts.get(event.snapshotRuleId) ?? 0) + 1)
  }
  return counts
}
