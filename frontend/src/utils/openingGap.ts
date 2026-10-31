import type { TimelineEvent } from '../api/types'

export function openingGapDetail(event: Pick<TimelineEvent, 'triggerContext'>, formatTime: (value: string) => string): string | null {
  const context = event.triggerContext
  return context?.kind === 'OPENING_GAP'
    ? `开盘跳空 · 参考收盘 ${context.referencePrice} USD · ${formatTime(context.referenceTime)} · IEX 正常时段最后一分钟`
    : null
}

export function targetReachLabel(event: Pick<TimelineEvent, 'triggerContext'>): string | null {
  const context = event.triggerContext
  if (context?.kind !== 'TARGET_REACHED') return null
  return { LIVE: '已达到目标价', RECOVERY: '恢复后确认达到', RULE_CREATED: '创建时确认达到', RULE_ACTIVATED: '启用时确认达到' }[context.confirmation]
}

export function targetReachDetail(event: Pick<TimelineEvent, 'triggerContext'>, formatTime: (value: string) => string): string | null {
  const context = event.triggerContext
  return context?.kind === 'TARGET_REACHED'
    ? `${targetReachLabel(event)} · 确认 ${formatTime(context.confirmedAt)} · 参考真实成交 ${formatTime(context.quoteEventTime)}${context.baselineReason ? ' · 未推断缺口期间的突破' : ''}`
    : null
}
