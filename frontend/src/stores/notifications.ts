import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { notificationApi } from '../api'
import type { NotificationFeed, NotificationItem } from '../api/types'
import { formatPrice } from '../utils/format'

export interface NotificationToast {
  id: number
  title: string
  message: string
  notificationIds: number[]
}

const pollInterval = 5000
const toastDuration = 6000

export const useNotificationsStore = defineStore('notifications', () => {
  const unreadCount = ref<number | null>(null)
  const revision = ref(0)
  const toasts = ref<NotificationToast[]>([])
  const reading = ref(new Set<number>())
  const markingAll = ref(false)
  const reconnecting = ref(false)
  const badgeText = computed(() => unreadCount.value == null || unreadCount.value === 0
    ? '' : unreadCount.value > 99 ? '99+' : String(unreadCount.value))
  const toastTimers = new Map<number, ReturnType<typeof setTimeout>>()
  let ownerId: number | null = null
  let ownerVersion = 0
  let requestVersion = 0
  let controller: AbortController | null = null
  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let baseline = false
  let previousTotal = 0
  let knownIds = new Set<number>()
  let signature = ''
  let failures = 0
  let toastSequence = 0

  function pauseToast(id: number) {
    const timer = toastTimers.get(id)
    if (timer) clearTimeout(timer)
    toastTimers.delete(id)
  }

  function dismissToast(id: number) {
    pauseToast(id)
    toasts.value = toasts.value.filter(toast => toast.id !== id)
  }

  function resumeToast(id: number) {
    pauseToast(id)
    if (document.hidden || !toasts.value.some(toast => toast.id === id)) return
    toastTimers.set(id, setTimeout(() => dismissToast(id), toastDuration))
  }

  function clearToasts() {
    for (const id of toastTimers.keys()) pauseToast(id)
    toasts.value = []
  }

  function addToast(title: string, message: string, ids: number[]) {
    const toast = { id: ++toastSequence, title, message, notificationIds: ids }
    toasts.value.push(toast)
    if (toasts.value.length > 3) dismissToast(toasts.value[0]!.id)
    resumeToast(toast.id)
  }

  function applyFeed(feed: NotificationFeed) {
    const added = feed.latest.filter(item => !knownIds.has(item.id))
    const unread = added.filter(item => item.status === 'UNREAD')
    const newCount = feed.totalCount - previousTotal
    const nextSignature = `${feed.totalCount}:${feed.unreadCount}:${feed.latest.map(item => `${item.id}:${item.status}`).join(',')}`

    if (baseline && newCount >= 0) {
      if (unread.length > 0 && newCount <= 3 && unread.length <= 3) {
        for (const item of [...unread].reverse()) {
          addToast(item.title, `${item.stockName} · 触发价 ${formatPrice(item.triggerPrice)}`, [item.id])
        }
      } else if (unread.length > 0 || (newCount > added.length && feed.unreadCount > 0)) {
        addToast(newCount > 1 ? `新增 ${newCount} 条价格提醒` : '有新的价格提醒',
          '多条提醒已合并，点击前往通知中心查看。', unread.map(item => item.id))
      }
    }

    const readIds = new Set(feed.latest.filter(item => item.status === 'READ').map(item => item.id))
    for (const toast of [...toasts.value]) {
      if (toast.notificationIds.length && toast.notificationIds.every(id => readIds.has(id))) dismissToast(toast.id)
    }
    if (feed.unreadCount === 0) clearToasts()
    unreadCount.value = feed.unreadCount
    previousTotal = feed.totalCount
    knownIds = new Set(feed.latest.map(item => item.id))
    baseline = true
    if (signature !== nextSignature) revision.value++
    signature = nextSignature
  }

  function cancelPoll() {
    if (pollTimer) clearTimeout(pollTimer)
    pollTimer = null
    requestVersion++
    controller?.abort()
    controller = null
  }

  async function refresh() {
    if (ownerId == null || document.hidden || controller || reading.value.size || markingAll.value) return
    if (pollTimer) clearTimeout(pollTimer)
    pollTimer = null
    const session = ownerVersion
    const request = ++requestVersion
    const current = new AbortController()
    controller = current
    const timeout = setTimeout(() => current.abort(), 8000)
    try {
      const feed = await notificationApi.feed(current.signal)
      if (session !== ownerVersion || request !== requestVersion) return
      applyFeed(feed)
      failures = 0
      reconnecting.value = false
    } catch {
      if (session === ownerVersion && request === requestVersion) {
        failures++
        reconnecting.value = true
      }
    } finally {
      clearTimeout(timeout)
      if (controller === current) {
        controller = null
        if (ownerId != null && !document.hidden && !reading.value.size && !markingAll.value) {
          pollTimer = setTimeout(() => void refresh(), Math.min(30000, pollInterval * 2 ** Math.min(failures, 3)))
        }
      }
    }
  }

  function visibilityChanged() {
    if (document.hidden) {
      cancelPoll()
      for (const toast of toasts.value) pauseToast(toast.id)
    } else {
      for (const toast of toasts.value) resumeToast(toast.id)
      void refresh()
    }
  }

  function windowFocused() {
    if (!document.hidden) void refresh()
  }

  function stop() {
    ownerVersion++
    ownerId = null
    cancelPoll()
    clearToasts()
    document.removeEventListener('visibilitychange', visibilityChanged)
    window.removeEventListener('focus', windowFocused)
    unreadCount.value = null
    reconnecting.value = false
    reading.value = new Set()
    markingAll.value = false
    baseline = false
    previousTotal = 0
    knownIds = new Set()
    signature = ''
    failures = 0
  }

  function start(userId: number) {
    stop()
    ownerId = userId
    document.addEventListener('visibilitychange', visibilityChanged)
    window.addEventListener('focus', windowFocused)
    void refresh()
  }

  async function markRead(item: NotificationItem): Promise<NotificationItem | null> {
    if (ownerId == null || reading.value.has(item.id) || markingAll.value) return null
    if (item.status === 'READ') return item
    const session = ownerVersion
    reading.value.add(item.id)
    cancelPoll()
    try {
      const updated = await notificationApi.read(item.id)
      if (session !== ownerVersion) return null
      for (const toast of [...toasts.value]) {
        if (toast.notificationIds.length === 1 && toast.notificationIds[0] === item.id) dismissToast(toast.id)
      }
      return updated
    } finally {
      if (session === ownerVersion) {
        reading.value.delete(item.id)
        void refresh()
      }
    }
  }

  async function markAllRead(): Promise<number | null> {
    if (ownerId == null || markingAll.value || reading.value.size) return null
    const session = ownerVersion
    markingAll.value = true
    cancelPoll()
    try {
      const result = await notificationApi.readAll()
      if (session !== ownerVersion) return null
      clearToasts()
      return result.markedRead
    } finally {
      if (session === ownerVersion) {
        markingAll.value = false
        void refresh()
      }
    }
  }

  return { unreadCount, badgeText, revision, toasts, reading, markingAll, reconnecting,
    start, stop, refresh, markRead, markAllRead, dismissToast, pauseToast, resumeToast }
})
