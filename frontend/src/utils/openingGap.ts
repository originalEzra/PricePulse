import type { TimelineEvent } from '../api/types'

export function openingGapDetail(event: Pick<TimelineEvent, 'triggerContext'>, formatTime: (value: string) => string): string | null {
  const context = event.triggerContext
  return context?.kind === 'OPENING_GAP'
    ? `开盘跳空 · 参考收盘 ${context.referencePrice} USD · ${formatTime(context.referenceTime)} · IEX 正常时段最后一分钟`
    : null
}
