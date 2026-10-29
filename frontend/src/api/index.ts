import { api } from './client'
import type {
  AlertRule,
  AlertTrigger,
  AlertDraftResult,
  AuthPayload,
  NotificationItem,
  NotificationFeed,
  QuoteHistory,
  SaveAlertRule,
  SimulationRequest,
  SimulationResult,
  Stock,
  MarketTimeline, TimelineEventPage, TimelineWindow,
  SimulationRun, StartSimulation, SimulationTimeline, SimulationEventPage, SimulationBatch,
} from './types'

export const authApi = {
  login: (email: string, password: string) =>
    api<AuthPayload>('/api/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) }),
  register: (email: string, password: string, displayName: string) =>
    api<AuthPayload>('/api/auth/register', {
      method: 'POST',
      body: JSON.stringify({ email, password, displayName }),
    }),
  logout: () => api<{ message: string }>('/api/auth/logout', { method: 'POST' }),
}

export const simulationApi = {
  current: (signal?: AbortSignal) => api<SimulationRun | null>('/api/simulations/current', { cache: 'no-store', signal }),
  start: (body: StartSimulation, key: string) => api<SimulationRun>('/api/simulations/start', {
    method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body),
  }),
  advance: (runId: string, body: { expectedVersion: number; expectedBatchNo: number }, key: string) =>
    api<SimulationBatch>(`/api/simulations/${runId}/advance`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body) }),
  timeline: (runId: string, from?: string, to?: string, cursor?: string, signal?: AbortSignal) => {
    const query = new URLSearchParams()
    if (from) query.set('from', from)
    if (to) query.set('to', to)
    if (cursor) query.set('cursor', cursor)
    return api<SimulationTimeline>(`/api/simulations/${runId}/timeline?${query}`, { cache: 'no-store', signal })
  },
  events: (runId: string, from: string, to: string, cursor?: string, signal?: AbortSignal) =>
    api<SimulationEventPage>(`/api/simulations/${runId}/events?${new URLSearchParams({ from, to, ...(cursor ? { cursor } : {}) })}`, { cache: 'no-store', signal }),
}

export const stockApi = {
  list: (query = '') => api<Stock[]>(`/api/stocks?query=${encodeURIComponent(query)}&limit=50`),
  get: (id: number) => api<Stock>(`/api/stocks/${id}`),
}

export const ruleApi = {
  get: (id: number, signal?: AbortSignal) => api<AlertRule>(`/api/alert-rules/${id}`, { signal, cache: 'no-store' }),
  triggers: (id: number, signal?: AbortSignal) =>
    api<AlertTrigger[]>(`/api/alert-rules/${id}/triggers?limit=500`, { signal, cache: 'no-store' }),
  list: (stockId?: number) =>
    api<AlertRule[]>(`/api/alert-rules?limit=100${stockId ? `&stockId=${stockId}` : ''}`),
  create: (rule: SaveAlertRule) =>
    api<AlertRule>('/api/alert-rules', { method: 'POST', body: JSON.stringify(rule) }),
  update: (id: number, version: number, rule: SaveAlertRule) =>
    api<AlertRule>(`/api/alert-rules/${id}`, {
      method: 'PUT',
      body: JSON.stringify({ version, rule }),
    }),
  pause: (rule: AlertRule) =>
    api<AlertRule>(`/api/alert-rules/${rule.id}/pause`, {
      method: 'POST',
      body: JSON.stringify({ version: rule.version }),
    }),
  resume: (rule: AlertRule) =>
    api<AlertRule>(`/api/alert-rules/${rule.id}/resume`, {
      method: 'POST',
      body: JSON.stringify({ version: rule.version }),
    }),
  remove: (rule: AlertRule) =>
    api<{ deletedRuleId: number }>(`/api/alert-rules/${rule.id}?version=${rule.version}`, {
      method: 'DELETE',
    }),
}

export const marketApi = {
  timeline: (stockId: number, window: TimelineWindow, signal?: AbortSignal) =>
    api<MarketTimeline>(`/api/market/stocks/${stockId}/timeline?window=${window}`, { signal, cache: 'no-store' }),
  events: (stockId: number, from: string, to: string, cursor: string, signal?: AbortSignal) =>
    api<TimelineEventPage>(`/api/market/stocks/${stockId}/timeline/events?${new URLSearchParams({ from, to, cursor })}`, { signal, cache: 'no-store' }),
  history: (stockId: number) => api<QuoteHistory[]>(`/api/market/stocks/${stockId}/quotes?limit=100`),
  simulate: (request: SimulationRequest) =>
    api<SimulationResult>('/api/market/simulate', { method: 'POST', body: JSON.stringify(request) }),
}

export const notificationApi = {
  list: (status?: 'UNREAD' | 'READ', signal?: AbortSignal) =>
    api<NotificationItem[]>(`/api/notifications?limit=100${status ? `&status=${status}` : ''}`, { signal, cache: 'no-store' }),
  feed: (signal?: AbortSignal) => api<NotificationFeed>('/api/notifications/feed', { signal, cache: 'no-store' }),
  read: (id: number) => api<NotificationItem>(`/api/notifications/${id}/read`, { method: 'POST' }),
  readAll: () => api<{ markedRead: number }>('/api/notifications/read-all', { method: 'POST' }),
}

export const assistantApi = {
  draftAlert: (text: string, signal?: AbortSignal) =>
    api<AlertDraftResult>('/api/assistant/alert-drafts', {
      method: 'POST',
      body: JSON.stringify({ text }),
      signal,
    }),
}
