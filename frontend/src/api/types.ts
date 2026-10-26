export interface ApiErrorBody {
  code: string
  message: string
  details?: Array<{ field?: string; message?: string }>
}

export interface ApiEnvelope<T> {
  success: boolean
  data: T
  error: ApiErrorBody | null
}

export interface UserSummary {
  id: number
  email: string
  displayName: string
}

export interface AuthPayload {
  accessToken: string
  tokenType: string
  expiresAt: string
  user: UserSummary
}

export interface Stock {
  id: number
  symbol: string
  name: string
  market: string
  currency: string
  latestPrice: number | null
  latestQuoteAt: string | null
}

export type AlertDirection = 'ABOVE' | 'BELOW'
export type TriggerMode = 'ONCE' | 'REPEAT'
export type RuleStatus = 'ACTIVE' | 'COOLDOWN' | 'PAUSED' | 'COMPLETED' | 'DELETED'

export interface SaveAlertRule {
  stockId: number
  name: string
  note?: string
  direction: AlertDirection
  thresholdPrice: number
  triggerMode: TriggerMode
  rearmPrice?: number | null
}

export interface AlertRuleDraft extends Omit<SaveAlertRule, 'note'> {
  stockSymbol: string
  stockName: string
  note: string | null
}

export interface AlertDraftResult {
  ready: boolean
  clarificationQuestion: string | null
  draft: AlertRuleDraft | null
}

export interface AlertRule extends SaveAlertRule {
  id: number
  stockSymbol: string
  stockName: string
  status: RuleStatus
  lastTriggeredAt: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface QuoteHistory {
  id: number
  eventKey: string
  stockId: number
  price: number
  eventTime: string
  receivedAt: string
  source: string
  sequenceNo: number | null
  processingStatus: 'PROCESSED' | 'DISCARDED'
  discardReason: string | null
}

export interface NotificationItem {
  id: number
  triggerId: number
  ruleId: number
  stockId: number
  stockSymbol: string
  stockName: string
  triggerPrice: number
  eventTime: string
  title: string
  content: string
  status: 'UNREAD' | 'READ'
  readAt: string | null
  createdAt: string
}

export interface SimulationRequest {
  stockId: number
  runKey: string
  steps: number
  mode: 'NORMAL' | 'RISE' | 'FALL' | 'OSCILLATE'
  seed: number
  stepRatio: number
  initialPrice: number
  startTime: string
}

export interface SimulationResult {
  requested: number
  processed: number
  duplicates: number
  discarded: number
  results: Array<unknown>
}
