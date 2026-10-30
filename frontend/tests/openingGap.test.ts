import test from 'node:test'
import assert from 'node:assert/strict'
import type { OpeningGapContext } from '../src/api/types.ts'
import { openingGapDetail } from '../src/utils/openingGap.ts'
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
