import test from 'node:test'
import assert from 'node:assert/strict'
import { closedReferencePoints, gapDescription, simulationChartRange } from '../src/utils/simulationGap.ts'
import { createTimeAxis } from '../src/utils/timeAxis.ts'
import type { MarketTimeline, SimulationGapContext, SimulationRun, SimulationTimeline } from '../src/api/types'

const gap = { kind: 'SIMULATED_OPENING_GAP', scope: 'SIMULATION', source: 'CUSTOM', referencePrice: '345.0000', openingPrice: '355.0000', referenceTime: '2024-11-27T21:00:00Z', openingTime: '2024-11-29T14:30:00Z', direction: 'UP', continuationMode: 'NORMAL' } as SimulationGapContext
test('historical gap choices require an actual final closed minute', () => {
  const source = { sessions: [{ close: gap.referenceTime }], points: [{ kind: 'BAR_CLOSE', pointRef: 'bar:1', eventTime: '2024-11-27T20:59:00Z' }, { kind: 'BAR_CLOSE', pointRef: 'bar:2', eventTime: gap.referenceTime }, { kind: 'TRADE', pointRef: 'trade:3', eventTime: gap.referenceTime }] } as MarketTimeline
  assert.deepEqual(closedReferencePoints(source).map(p => p.pointRef), ['bar:2'])
  assert.deepEqual(closedReferencePoints(null), [])
})
test('custom two-point viewport keeps the shared closed-time axis and invents no prices', () => {
  const run = { origin: { kind: 'SIMULATION_REFERENCE' }, parameters: { openingGap: gap } } as SimulationRun
  const timeline = { range: { from: gap.referenceTime, to: gap.openingTime }, simulation: [{ pointSeq: 1, eventTime: gap.openingTime, price: gap.openingPrice }] } as SimulationTimeline
  const before = JSON.stringify(timeline), range = simulationChartRange(run, timeline)
  const axis = createTimeAxis(Date.parse(range.from), Date.parse(range.to), [{ date: '2024-11-27', open: '2024-11-27T14:30:00Z', close: gap.referenceTime }, { date: '2024-11-29', open: gap.openingTime, close: '2024-11-29T18:00:00Z' }], true)
  assert.equal(axis.breaks.length, 1)
  assert.ok(axis.position(Date.parse(gap.openingTime)) - axis.position(Date.parse(gap.referenceTime)) < 0.03)
  assert.equal(JSON.stringify(timeline), before)
  assert.equal(gapDescription(gap).includes('参考 345.0000 → 开盘 355.0000'), true)
  assert.equal(gapDescription({ ...gap, direction: 'DOWN', openingPrice: '335.0000' }).startsWith('⇩'), true)
})
