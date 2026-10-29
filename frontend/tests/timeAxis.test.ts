import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createTimeAxis, timeFraction, nearestTimeIndex, segments, formatNewYorkTimestamp, barGapRanges } from '../src/utils/timeAxis.ts'

test('actual time geometry preserves long gaps and future empty range', () => {
  assert.equal(timeFraction(1, 0, 100), 0.01)
  assert.equal(timeFraction(99, 0, 100), 0.99)
  assert.equal(timeFraction(1, 1, 1), 0.5)
})
test('crosshair chooses nearest time rather than nearest array fraction', () => {
  assert.equal(nearestTimeIndex([0, 1, 99, 100], 10), 1)
  assert.equal(nearestTimeIndex([0, 1, 99, 100], 95), 2)
  assert.equal(nearestTimeIndex([], 0), null)
  assert.equal(nearestTimeIndex([10, 10, 11], 10), 0)
})
test('sessions and confirmed live gaps break the curve', () => {
  const points = [{ t: 0, s: 'Fri' }, { t: 1, s: 'Fri' }, { t: 50, s: 'Mon' }, { t: 51, s: 'Mon' }]
  assert.deepEqual(segments(points, p => p.s, (a, b) => a.t < 51 && b.t >= 51).map(s => s.map(p => p.t)), [[0, 1], [50], [51]])
})

const ms = (raw: string) => Date.parse(raw)
const calendar = [
  { date: '2026-10-30', open: '2026-10-30T13:30:00Z', close: '2026-10-30T20:00:00Z' },
  { date: '2026-11-02', open: '2026-11-02T14:30:00Z', close: '2026-11-02T21:00:00Z' },
]
const near = (a: number, b: number) => assert.ok(Math.abs(a - b) < 1e-8, `${a} != ${b}`)

test('1H and 1D retain irregular elapsed spacing and an unfilled session remainder', () => {
  for (const duration of [3600000, 23400000]) {
    const open = ms(calendar[0]!.open), axis = createTimeAxis(open, open + duration, calendar, false, 600)
    const positions = [1, 2, 10].map(minutes => axis.position(open + minutes * 60000))
    near((positions[2]! - positions[1]!) / (positions[1]! - positions[0]!), 8)
    assert.equal(axis.breaks.length, 0)
    assert.ok(axis.position(open + 30 * 60000) < 1)
  }
})

test('weekend and DST closures compress identically while both sessions keep elapsed trading proportions', () => {
  const open = ms(calendar[0]!.open), close = ms(calendar[1]!.close)
  const axis = createTimeAxis(open, close, calendar, true, 600)
  assert.equal(axis.breaks.length, 1)
  near(axis.breaks[0]!.right - axis.breaks[0]!.left, 12 / 600)
  near(axis.position(ms(calendar[0]!.close)) - axis.position(open),
    axis.position(close) - axis.position(ms(calendar[1]!.open)))
  const realOrigin = ms(calendar[0]!.close), simulationFirst = ms(calendar[1]!.open) + 60000
  assert.ok(axis.position(simulationFirst) - axis.position(realOrigin) < 0.03)
  for (const at of [open, realOrigin, simulationFirst, close]) near(axis.timeAt(axis.position(at)), at)
  assert.ok(axis.ticks(5).every(t => !axis.breaks.some(gap => t.at > gap.from && t.at < gap.to)))
})

test('holiday and early close use provider boundaries; missing intraday data is never compressed', () => {
  const sessions = [
    { date: '2026-11-25', open: '2026-11-25T14:30:00Z', close: '2026-11-25T21:00:00Z' },
    { date: '2026-11-27', open: '2026-11-27T14:30:00Z', close: '2026-11-27T18:00:00Z' },
    { date: '2026-11-30', open: '2026-11-30T14:30:00Z', close: '2026-11-30T21:00:00Z' },
  ]
  const axis = createTimeAxis(ms(sessions[0]!.open), ms(sessions[2]!.close), sessions, true, 700)
  assert.equal(axis.breaks.length, 2)
  assert.equal(axis.breaks[1]!.from, ms(sessions[1]!.close))
  const a = ms(sessions[0]!.open), one = axis.position(a + 60000) - axis.position(a)
  near((axis.position(a + 10 * 60000) - axis.position(a + 2 * 60000)) / one, 8)
  const unknown = createTimeAxis(a, ms(sessions[2]!.close), [], true, 700)
  assert.equal(unknown.breaks.length, 0)
  near(unknown.position(a + 60000), timeFraction(a + 60000, a, ms(sessions[2]!.close)))
})

test('month gutters stay bounded and the cursor selects the closest displayed real point', () => {
  const sessions = Array.from({ length: 22 }, (_, i) => ({ date: String(i),
    open: new Date(ms('2026-10-01T13:30:00Z') + i * 86400000).toISOString(),
    close: new Date(ms('2026-10-01T20:00:00Z') + i * 86400000).toISOString() }))
  const axis = createTimeAxis(ms(sessions[0]!.open), ms(sessions.at(-1)!.close), sessions, true, 300)
  near(axis.breaks.reduce((sum, gap) => sum + gap.right - gap.left, 0), 0.24)
  const originals = [calendar[0]!.close, calendar[1]!.open, calendar[1]!.close]
  const copy = [...originals], week = createTimeAxis(ms(calendar[0]!.open), ms(calendar[1]!.close), calendar, true, 600)
  const mapped = originals.map(t => week.position(ms(t)))
  assert.equal(nearestTimeIndex(mapped, mapped[1]! + 0.0001), 1)
  assert.deepEqual(originals, copy)
})

test('hover labels preserve exact fractional timestamps and distinguish NY daylight and standard time', () => {
  assert.equal(formatNewYorkTimestamp('2026-11-01T05:30:00.123456789Z'), '2026-11-01 01:30:00.123456789 EDT（纽约）')
  assert.equal(formatNewYorkTimestamp('2026-11-01T06:30:00.987654321Z'), '2026-11-01 01:30:00.987654321 EST（纽约）')
})

test('frozen-prefix missing minutes break the line rather than turning into closure gutters', () => {
  const points = [
    { kind: 'BAR_CLOSE', session: 'Fri', eventTime: '2026-10-30T13:31:00Z', intervalStart: '2026-10-30T13:30:00Z' },
    { kind: 'BAR_CLOSE', session: 'Fri', eventTime: '2026-10-30T13:40:00Z', intervalStart: '2026-10-30T13:39:00Z' },
    { kind: 'BAR_CLOSE', session: 'Mon', eventTime: '2026-11-02T14:31:00Z', intervalStart: '2026-11-02T14:30:00Z' },
  ]
  assert.deepEqual(barGapRanges(points), [{ from: points[0]!.eventTime, to: points[1]!.intervalStart }])
})
