import type { AlertDirection, HistoricalRuleSnapshot } from '../api/types'

export interface ChartAnnotation {
  index: number
  kind: AlertDirection | 'HISTORY'
  label: string
  detail: string
}

export interface ChartThreshold {
  value: number
  label: string
  kind: AlertDirection | 'HISTORY'
  color?: string
}

export interface TimedChartAnnotation {
  id: number; eventTime: string; price: number; kind: AlertDirection
  label: string; detail: string
  ruleName?: string; threshold?: number | string; result?: string; pointSeq?: number; rawPrice?: string
  ruleSnapshot?: HistoricalRuleSnapshot
}

export const ruleStatusLabel: Record<string, string> = {
  ACTIVE: '等待目标价', COMPLETED: '已完成', DELETED: '已取消',
}
