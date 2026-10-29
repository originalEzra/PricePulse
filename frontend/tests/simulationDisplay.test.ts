import test from 'node:test'
import assert from 'node:assert/strict'
import type { SimulationEvent, SimulationSnapshot } from '../src/api/types.ts'
import { collectSimulationEvents, simulationTriggerCounts } from '../src/utils/simulationDisplay.ts'
import { ruleLineColor, simulationThresholds } from '../src/utils/ruleDisplay.ts'

const event = (id: number, snapshotRuleId: number, eventType: 'TRIGGERED' | 'REARMED'): SimulationEvent => ({
  id, snapshotRuleId, eventType, pointSeq: id, eventTime: '2026-10-28T13:50:00Z', epochNanos: '0', price: '101.0000',
  fromState: 'ACTIVE', toState: 'COOLDOWN', ruleSnapshot: {} as SimulationEvent['ruleSnapshot'],
})

test('rule results include the trigger beyond page one, including a REPEAT rule that has rearmed', async () => {
  const first = Array.from({ length: 100 }, (_, i) => event(i + 1, 1, i % 2 ? 'REARMED' : 'TRIGGERED'))
  const result = await collectSimulationEvents({ events: first, eventCursor: 'second', watermark: '102' }, async cursor => {
    assert.equal(cursor, 'second')
    return { events: [event(100, 1, 'REARMED'), event(101, 2, 'TRIGGERED'), event(102, 3, 'REARMED')], eventCursor: null, watermark: '102' }
  })
  assert.equal(result.length, 102)
  const counts = simulationTriggerCounts(result)
  assert.equal(counts.get(1), 50); assert.equal(counts.get(2), 1); assert.equal(counts.get(3), undefined)
})

test('an incomplete or changing event scan fails rather than claiming unfetched rules did not trigger', async () => {
  const page = { events: [], eventCursor: 'same', watermark: '1' }
  await assert.rejects(collectSimulationEvents(page, async () => page), /分页未完成/)
  await assert.rejects(collectSimulationEvents(page, async () => ({ ...page, eventCursor: null, watermark: '2' })), /水位改变/)
  await assert.rejects(collectSimulationEvents(page, async () => { throw new Error('offline') }), /offline/)
})

test('snapshot checkbox colors match the original rule identity and cannot change frozen states', () => {
  const snapshots = [1, 2].map(id => ({ snapshotRuleId: 100 + id, sourceRuleId: id, currentState: 'COMPLETED',
    config: { name: `Frozen ${id}`, thresholdPrice: 100 + id, direction: 'ABOVE' } })) as SimulationSnapshot[]
  const original = structuredClone(snapshots), lines = simulationThresholds(snapshots, new Set([101, 102]))
  assert.equal(lines.length, 2); assert.equal(lines[0]!.color, ruleLineColor(1))
  assert.deepEqual(simulationThresholds(snapshots, new Set()), [])
  assert.deepEqual(snapshots, original)
})
