import type { MarketTimeline, Stock } from '../api/types'
import { instantKey } from './chartInspection.ts'

export type WatchlistQuote = Pick<MarketTimeline['stock'], 'id' | 'latestPrice' | 'latestQuoteAt'>

/** Only compare prices already read from the real timeline, not legacy catalog prices. */
export function applyWatchlistQuote(stock: Stock, quote: WatchlistQuote, acceptedTimes: Map<number, string>): void {
  if (stock.id !== quote.id) return
  const key = quote.latestQuoteAt == null ? null : instantKey(quote.latestQuoteAt)
  const price = quote.latestPrice == null ? null : Number(quote.latestPrice)
  if (key == null || price == null || !Number.isFinite(price) || price <= 0) {
    if (!acceptedTimes.has(stock.id)) { stock.latestPrice = null; stock.latestQuoteAt = null }
    return
  }
  const previous = acceptedTimes.get(stock.id)
  if (previous != null && BigInt(previous) > BigInt(key)) return
  stock.latestPrice = price
  stock.latestQuoteAt = quote.latestQuoteAt
  acceptedTimes.set(stock.id, key)
}

/** A short, read-only timeline per unselected stock; failures do not block other rows or the chart. */
export async function refreshWatchlistQuotes(stockIds: number[], read: (id: number, signal: AbortSignal) => Promise<WatchlistQuote>,
  apply: (quote: WatchlistQuote) => void, signal: AbortSignal): Promise<number[]> {
  const ids = [...new Set(stockIds)], failed: number[] = []
  let next = 0
  async function worker() {
    while (!signal.aborted && next < ids.length) {
      const id = ids[next++]!
      try {
        const quote = await read(id, signal)
        if (signal.aborted) return
        if (quote.id !== id) throw new Error('Quote stock does not match the requested row')
        apply(quote)
      } catch {
        if (!signal.aborted) failed.push(id)
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(2, ids.length) }, worker))
  return failed
}
