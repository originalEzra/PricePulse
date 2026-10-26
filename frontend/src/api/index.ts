import { api } from './client'
import type {
  AlertRule,
  AuthPayload,
  NotificationItem,
  QuoteHistory,
  SaveAlertRule,
  SimulationRequest,
  SimulationResult,
  Stock,
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

export const stockApi = {
  list: (query = '') => api<Stock[]>(`/api/stocks?query=${encodeURIComponent(query)}&limit=50`),
}

export const ruleApi = {
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
  history: (stockId: number) => api<QuoteHistory[]>(`/api/market/stocks/${stockId}/quotes?limit=100`),
  simulate: (request: SimulationRequest) =>
    api<SimulationResult>('/api/market/simulate', { method: 'POST', body: JSON.stringify(request) }),
}

export const notificationApi = {
  list: (status?: 'UNREAD' | 'READ') =>
    api<NotificationItem[]>(`/api/notifications?limit=100${status ? `&status=${status}` : ''}`),
  read: (id: number) => api<NotificationItem>(`/api/notifications/${id}/read`, { method: 'POST' }),
}
