import type { AlertDirection, AlertRule, RuleStatus } from '../api/types'

export interface ChartAnnotation {
  index: number
  kind: AlertDirection | 'REARMED'
  label: string
  detail: string
}

export interface ChartThreshold {
  value: number
  label: string
  kind: AlertDirection | 'REARMED'
  color?: string
}

export interface TimedChartAnnotation {
  id: number; eventTime: string; price: number; kind: AlertDirection
  label: string; detail: string
  ruleName?: string; threshold?: number | string; result?: string; pointSeq?: number; rawPrice?: string
  ruleSnapshot?: AlertRule
}

export const ruleStatusLabel: Record<RuleStatus, string> = {
  ACTIVE: '监控中', COOLDOWN: '冷却中', PAUSED: '已暂停', COMPLETED: '已完成', DELETED: '已删除',
}
