import test from 'node:test'
import assert from 'node:assert/strict'
import { compactNewYorkTime, instantKey, indexPointEvents, previousValidPrice, priceDifference } from '../src/utils/chartInspection.ts'

test('event inspection preserves nanoseconds and recognizes the same instant in a different timezone', () => {
  const first = '2026-10-28T13:50:00.123456789Z', second = '2026-10-28T13:50:00.123456790Z'
  assert.notEqual(instantKey(first), instantKey(second))
  assert.equal(instantKey(first), instantKey('2026-10-28T09:50:00.123456789-04:00'))
  assert.equal(instantKey('invalid'), null)
})

test('multiple exact-time events stay attached to their own price and rule details', () => {
  const events = [1, 2].map(id => ({ id, eventTime: '2026-10-28T13:50:00.123456789Z', price: 100 + id,
    kind: 'ABOVE' as const, label: '触发', detail: '', ruleName: `Rule ${id}`, threshold: 100, result: '已触发' }))
  const index = indexPointEvents(events)
  assert.deepEqual(index.get(instantKey(events[0]!.eventTime)!), events)
  assert.equal(index.get(instantKey('2026-10-28T13:50:00Z')!), undefined)
  assert.deepEqual(events.map(e => e.price), [101, 102])
})

test('price change skips invalid points and the simulation offset uses the explicit origin baseline', () => {
  assert.equal(previousValidPrice([100, NaN, Infinity], 3), 100)
  assert.equal(previousValidPrice([100], 0), null)
  assert.deepEqual(priceDifference(101, 100), { value: 1, percent: 1 })
  assert.equal(priceDifference(101, null), null)
  assert.equal(priceDifference(101, 0), null)
  assert.equal(priceDifference(101, 100.1789)!.value.toFixed(4), '0.8211')
})

test('compact time uses New York across DST and keeps midnight unambiguous', () => {
  assert.equal(compactNewYorkTime('2026-10-29T13:57:00Z'), '10/29 09:57 ET')
  assert.equal(compactNewYorkTime('2026-11-02T14:57:00Z'), '11/02 09:57 ET')
  assert.equal(compactNewYorkTime('2026-11-03T05:00:00Z'), '11/03 00:00 ET')
  assert.equal(compactNewYorkTime('invalid'), '—')
})
