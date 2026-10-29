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

export interface AlertTrigger {
  id: number
  ruleId: number
  quoteEventId: number
  triggerPrice: number
  eventTime: string
  ruleSnapshot: AlertRule
  createdAt: string
}

export interface RuleObservation {
  ruleId: number
  ruleName: string
  direction: AlertDirection
  thresholdPrice: number
  rearmPrice: number | null
  triggerMode: TriggerMode
  beforeStatus: RuleStatus
  afterStatus: RuleStatus
  event: 'TRIGGERED' | 'REARMED' | 'NONE'
  triggerId: number | null
  version: number
}

export interface QuoteResult {
  eventId: number
  eventKey: string
  stockId: number
  previousPrice: number | null
  price: number
  eventTime: string
  outcome: 'PROCESSED' | 'DUPLICATE' | 'DISCARDED_STALE' | 'DISCARDED_FUTURE' | 'DISCARDED_OUT_OF_ORDER'
  reason: string | null
  matching: { candidates: number; triggered: number; rearmed: number } | null
  observation: RuleObservation | null
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

export interface NotificationFeed {
  totalCount: number
  unreadCount: number
  latest: NotificationItem[]
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
  ruleId?: number | null
}

export interface SimulationResult {
  requested: number
  processed: number
  duplicates: number
  discarded: number
  results: QuoteResult[]
}

export type TimelineWindow = '1H' | '1D' | '1W' | '1M'
export interface TradingSession { date: string; open: string; close: string }
export interface TimelinePoint {
  pointRef: string; eventTime: string; epochNanos: string; price: string
  kind: 'TRADE' | 'BAR_CLOSE'; intervalStart: string | null; intervalEnd: string | null
  availableAt: string; revision: string; session: string
}
export interface TimelineEvent {
  triggerId: number; quoteId: number; eventTime: string; epochNanos: string; price: string
  ruleSnapshot: AlertRule; notificationStatus: 'PENDING' | 'UNREAD' | 'READ' | 'FAILED'
}
export interface TimelineEventPage { events: TimelineEvent[]; eventCursor: string | null; watermark: string }
export interface MarketTimeline extends TimelineEventPage {
  scope: 'REAL'; provider: 'ALPACA'; feed: 'iex'; adjustment: 'raw'; resolution: string
  stock: Omit<Stock, 'latestPrice'> & { latestPrice: string | null; priceKind: string }
  range: { from: string; to: string; asOf: string; marketTimeZone: string }
  coverage: { requestedFrom: string; requestedTo: string; syncedAt: string } | null
  health: { connection: string; market: 'OPEN' | 'CLOSED'; freshness: string; businessWritesEnabled: boolean
    lastObservedAt: string | null; lastAcceptedAt: string | null; lastError: string | null }
  gaps: Array<{ from: string; to: string; reason: string }>
  sessions: TradingSession[]
  dataQuality: { availableBars: number; displayedBars: number; expectedMinutes: number; unavailableMinutes: number
    historyCoverageComplete: boolean; calendarCoverageComplete: boolean; barSamplingApplied: boolean; tradeTailPoints: number }
  points: TimelinePoint[]
  thresholds: Array<{ id: number; name: string; direction: AlertDirection; thresholdPrice: string
    rearmPrice: string | null; status: RuleStatus; version: number }>
  thresholdSemantics: 'CURRENT_CONFIGURATION'
}

export type SimulationMode = 'NORMAL' | 'RISE' | 'FALL' | 'OSCILLATE'
export interface StartSimulation {
  stockId: number; origin: { kind: 'LATEST' | 'HISTORICAL_POINT'; pointRef?: string; revision?: string }
  mode: SimulationMode; stepRatio: string; seed: string | null; expectedCurrentRunId: string | null
}
export interface SimulationSnapshot {
  snapshotRuleId: number; sourceRuleId: number; sourceVersion: number; config: AlertRule
  sourceStatus: RuleStatus; initialState: RuleStatus; currentState: RuleStatus; stateVersion: number
}
export interface SimulationRun {
  runId: string; stockId: number; version: number; lastBatchNo: number; simClock: string
  latestPrice: string; nextPointSeq: number; seed: string; generatorVersion: string; generatorSupported: boolean
  origin: { kind: 'LATEST' | 'HISTORICAL_POINT'; stockSymbol: string; provider: 'ALPACA'; feed: 'iex'; point: TimelinePoint; prefixFrom: string; calendarVersion: string }
  parameters: { mode: SimulationMode; stepRatio: string }; ruleSnapshots: SimulationSnapshot[]
  stats: { triggered: number; rearmed: number }
}
export interface SimulationPoint { pointSeq: number; eventTime: string; price: string }
export interface SimulationTimeline {
  scope: 'SIMULATION'; runId: string; stockId: number; version: number; simClock: string; origin: SimulationRun['origin']
  range: { from: string; to: string; marketTimeZone: string }; sessions: TradingSession[]; realPrefix: TimelinePoint[]; simulation: SimulationPoint[]
  thresholds: SimulationSnapshot[]; stats: SimulationRun['stats']; pointCursor: string | null; watermark: string
}
export interface SimulationEvent {
  id: number; snapshotRuleId: number; pointSeq: number; eventType: 'TRIGGERED' | 'REARMED'
  eventTime: string; epochNanos: string; price: string; fromState: RuleStatus; toState: RuleStatus; ruleSnapshot: AlertRule
}
export interface SimulationEventPage { events: SimulationEvent[]; eventCursor: string | null; watermark: string }
export interface SimulationBatch {
  runId: string; batchNo: number; versionAfter: number; fromClock: string; toClock: string
  firstPointSeq: number; lastPointSeq: number; triggered: number; rearmed: number; points: SimulationPoint[]
}
