import type { MarketTimeline, SimulationGapContext, SimulationRun, SimulationTimeline } from '../api/types'
export function closedReferencePoints(source: MarketTimeline | null) {
  return source?.points.filter(p => p.kind === 'BAR_CLOSE' && source.sessions.some(s => s.close === p.eventTime)) ?? []
}
export function gapDescription(gap: SimulationGapContext): string {
  return `${gap.direction === 'UP' ? '⇧ 向上' : gap.direction === 'DOWN' ? '⇩ 向下' : '↔ 平开'}模拟跳空 · 参考 ${gap.referencePrice} → 开盘 ${gap.openingPrice} USD`
}
/** Viewport padding only: no prices or timestamps are invented for the first two-point chart. */
export function simulationChartRange(run: SimulationRun, timeline: SimulationTimeline) {
  const gap = run.parameters.openingGap
  return gap && run.origin.kind === 'SIMULATION_REFERENCE' && timeline.simulation.length
    ? { from: new Date(Date.parse(gap.referenceTime) - 60000).toISOString(), to: new Date(Math.max(Date.parse(timeline.range.to), Date.parse(gap.openingTime) + 60000)).toISOString() }
    : timeline.range
}
