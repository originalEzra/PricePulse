import test from 'node:test'
import assert from 'node:assert/strict'
import type { OpeningGapContext } from '../src/api/types.ts'
import { openingGapDetail, targetReachDetail, targetReachLabel } from '../src/utils/openingGap.ts'
import { compactNewYorkTime } from '../src/utils/chartInspection.ts'

test('opening gap details retain the actual reference precision and use New York time', () => {
  const context: OpeningGapContext = {
    kind: 'OPENING_GAP', referencePrice: '100.1234', referenceTime: '2026-10-28T20:00:00Z',
    referenceEpochNanos: '1793217600000000000', referenceSource: 'ALPACA/iex/raw/1Min/close',
    referenceBarId: 7, referenceRevision: 2, referenceFetchedAt: '2026-10-28T20:05:00Z',
    sessionDate: '2026-10-29', sessionOpen: '2026-10-29T13:30:00Z', ruleEffectiveAt: '2026-10-29T13:00:00Z',
  }
  const before = structuredClone(context)
  const detail = openingGapDetail({ triggerContext: context }, compactNewYorkTime)!
  assert.match(detail, /开盘跳空/)
  assert.match(detail, /100\.1234 USD/)
  assert.match(detail, /10\/28 16:00/)
  assert.match(detail, /IEX 正常时段最后一分钟/)
  assert.deepEqual(context, before)
})

test('existing events without opening provenance retain their ordinary presentation', () => {
  assert.equal(openingGapDetail({}, compactNewYorkTime), null)
  assert.equal(openingGapDetail({ triggerContext: null }, compactNewYorkTime), null)
})

test('reachable confirmations distinguish recovery and creation from a historical crossing', () => {
  const context = { kind: 'TARGET_REACHED' as const, confirmation: 'RECOVERY' as const,
    confirmedAt: '2026-11-02T15:00:20Z', quoteEventTime: '2026-11-02T15:00:19Z', baselineReason: 'RECONNECT' }
  assert.equal(targetReachLabel({ triggerContext: context }), '恢复后确认达到')
  assert.match(targetReachDetail({ triggerContext: context }, compactNewYorkTime)!, /未推断缺口期间的突破/)
  assert.match(targetReachDetail({ triggerContext: context }, compactNewYorkTime)!, /11\/02 10:00/)
  assert.equal(targetReachLabel({ triggerContext: { ...context, confirmation: 'RULE_CREATED' } }), '创建时确认达到')
  assert.equal(openingGapDetail({ triggerContext: context }, compactNewYorkTime), null)
})
