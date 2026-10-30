import type { SubscriptionState } from '../api/types.ts'

export const subscriptionLabels: Record<SubscriptionState, string> = { PENDING: '订阅中', CONFIRMED: '监控中', FAILED: '接入失败' }
export const subscriptionReasons: Record<string, string> = {
  DYNAMIC_SUBSCRIPTION_UNAVAILABLE: '当前服务暂不支持接入这只股票', FORMAL_FEED_DISABLED: '正式行情处理尚未启用',
  UPSTREAM_REJECTED: '上游拒绝接入', AWAITING_UPSTREAM_CONFIRMATION: '等待行情源确认', AWAITING_CAPACITY: '等待可用监控名额',
  UPSTREAM_LIMIT_REACHED: '上游实时订阅额度不足，请释放名额后重试',
  UPSTREAM_SUBSCRIPTION_FAILED: '上游订阅未成功，连接恢复后可重试',
}
export function confirmedMonitoring(state: SubscriptionState | undefined): boolean { return state === 'CONFIRMED' }

/** A response for an older query/account never updates the current search, even if abort is ignored. */
export function createAssetSearch<T>(read: (query: string, signal: AbortSignal) => Promise<T>,
  changed: (value: T | null, error: unknown | null, loading: boolean) => void, delay = 300) {
  let timer: ReturnType<typeof setTimeout> | null = null, request: AbortController | null = null, generation = 0, disposed = false
  function query(text: string) {
    const current = ++generation
    if (timer) clearTimeout(timer)
    request?.abort(); request = null
    const value = text.trim()
    changed(null, null, false)
    if (!value || disposed) return
    changed(null, null, true)
    timer = setTimeout(async () => {
      const controller = new AbortController(); request = controller
      try {
        const result = await read(value, controller.signal)
        if (!disposed && current === generation && !controller.signal.aborted) changed(result, null, false)
      } catch (error) {
        if (!disposed && current === generation && !controller.signal.aborted) changed(null, error, false)
      }
    }, delay)
  }
  function dispose() { disposed = true; generation++; if (timer) clearTimeout(timer); request?.abort() }
  return { query, dispose }
}
