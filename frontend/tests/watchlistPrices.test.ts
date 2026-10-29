import test from 'node:test'
import assert from 'node:assert/strict'
import type { Stock } from '../src/api/types.ts'
import { applyWatchlistQuote, refreshWatchlistQuotes, type WatchlistQuote } from '../src/utils/watchlistPrices.ts'

const stock = (id: number): Stock => ({ id, symbol: `TEST${id}`, name: 'Synthetic', market: 'US', currency: 'USD', latestPrice: null, latestQuoteAt: null })
const quote = (id: number, price = '101.25', at = '2026-10-28T20:00:00.123456789Z'): WatchlistQuote => ({ id, latestPrice: price, latestQuoteAt: at })

test('unselected watchlist rows load without clicks and update independently with bounded reads', async () => {
  const rows = [1, 2, 3, 4, 5].map(stock), selected = rows[0]!, accepted = new Map<number, string>()
  applyWatchlistQuote(selected, quote(1, '99.50'), accepted)
  const reads: number[] = [], published: number[] = []
  let active = 0, peak = 0
  const failures = await refreshWatchlistQuotes([2, 3, 4, 5, 2], async id => {
    reads.push(id); peak = Math.max(peak, ++active)
    await new Promise(resolve => setTimeout(resolve, id === 2 ? 10 : 0))
    active--
    return quote(id, String(100 + id))
  }, data => { published.push(data.id); applyWatchlistQuote(rows.find(row => row.id === data.id)!, data, accepted) }, new AbortController().signal)
  assert.deepEqual(reads, [2, 3, 4, 5])
  assert.ok(peak <= 2)
  assert.equal(published[0], 3)
  assert.deepEqual(rows.map(row => row.latestPrice), [99.5, 102, 103, 104, 105])
  assert.deepEqual(failures, [])
})

test('one unavailable stock does not block other prices and mismatched identities are rejected', async () => {
  const published: number[] = []
  const failures = await refreshWatchlistQuotes([1, 2, 3, 4], async id => {
    if (id === 2) throw new Error('Unavailable')
    return quote(id === 4 ? 99 : id)
  }, data => { published.push(data.id) }, new AbortController().signal)
  assert.deepEqual(new Set(failures), new Set([2, 4]))
  assert.deepEqual(new Set(published), new Set([1, 3]))
})

test('leaving the page discards late responses and stops queued stock reads', async () => {
  const controller = new AbortController(), reads: number[] = [], published: number[] = []
  const releases: Array<() => void> = []
  const work = refreshWatchlistQuotes([1, 2, 3, 4], async id => {
    reads.push(id)
    await new Promise<void>(resolve => releases.push(resolve))
    return quote(id)
  }, data => { published.push(data.id) }, controller.signal)
  assert.deepEqual(reads, [1, 2])
  controller.abort(); releases.forEach(release => release())
  await work
  assert.deepEqual(reads, [1, 2])
  assert.deepEqual(published, [])
  await refreshWatchlistQuotes([5], async id => { reads.push(id); return quote(id) }, () => assert.fail('Aborted refresh applied'), controller.signal)
  assert.deepEqual(reads, [1, 2])
})

test('a slower background response cannot replace a newer chart quote, including nanosecond ties', () => {
  const row = stock(1), accepted = new Map<number, string>()
  applyWatchlistQuote(row, quote(1, '102', '2026-10-28T20:00:00.123456790Z'), accepted)
  applyWatchlistQuote(row, quote(1, '101'), accepted)
  applyWatchlistQuote(row, quote(2, '999'), accepted)
  assert.equal(row.latestPrice, 102)
  assert.equal(row.latestQuoteAt, '2026-10-28T20:00:00.123456790Z')
  applyWatchlistQuote(row, quote(1, '102.5', '2026-10-28T16:00:00.123456790-04:00'), accepted)
  assert.equal(row.latestPrice, 102.5)
})

test('missing real data clears an unverified catalog price without fabricating or erasing a known valid quote', () => {
  const row = { ...stock(1), latestPrice: 999, latestQuoteAt: '2026-10-29T20:00:00Z' }, accepted = new Map<number, string>()
  applyWatchlistQuote(row, { id: 1, latestPrice: null, latestQuoteAt: null }, accepted)
  assert.equal(row.latestPrice, null)
  assert.equal(row.latestQuoteAt, null)
  applyWatchlistQuote(row, quote(1), accepted)
  for (const invalid of [null, 'NaN', '0', '-2']) applyWatchlistQuote(row, { ...quote(1), latestPrice: invalid }, accepted)
  applyWatchlistQuote(row, quote(1, '999', 'invalid'), accepted)
  assert.equal(row.latestPrice, 101.25)
  assert.equal(row.latestQuoteAt, quote(1).latestQuoteAt)
})
