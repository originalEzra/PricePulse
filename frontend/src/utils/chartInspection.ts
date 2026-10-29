import type { TimedChartAnnotation } from './chart'

/** Use the complete instant, so trades that differ only below milliseconds are not merged. */
export function instantKey(raw: string): string | null {
  const milliseconds = Date.parse(raw)
  if (!Number.isFinite(milliseconds)) return null
  const fraction = raw.match(/\.(\d+)(?:Z|[+-]\d{2}:\d{2})$/)?.[1] ?? ''
  return (BigInt(milliseconds) * 1000000n + BigInt(fraction.padEnd(9, '0').slice(3, 9))).toString()
}

export function indexPointEvents(events: TimedChartAnnotation[]): Map<string, TimedChartAnnotation[]> {
  const result = new Map<string, TimedChartAnnotation[]>()
  for (const event of events) {
    const key = instantKey(event.eventTime)
    if (key != null) result.set(key, [...(result.get(key) ?? []), event])
  }
  return result
}

export function previousValidPrice(values: number[], beforeIndex: number): number | null {
  for (let i = beforeIndex - 1; i >= 0; i--) if (Number.isFinite(values[i]) && values[i]! > 0) return values[i]!
  return null
}

export function priceDifference(value: number, reference: number | null): { value: number; percent: number } | null {
  return reference != null && Number.isFinite(value) && Number.isFinite(reference) && reference > 0
    ? { value: value - reference, percent: (value - reference) / reference * 100 } : null
}

export function compactNewYorkTime(raw: string): string {
  if (!Number.isFinite(Date.parse(raw))) return '—'
  const parts = new Intl.DateTimeFormat('en-US', { timeZone: 'America/New_York', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(new Date(raw))
  const value = (type: string) => parts.find(part => part.type === type)?.value ?? ''
  return `${value('month')}/${value('day')} ${value('hour')}:${value('minute')} ET`
}
