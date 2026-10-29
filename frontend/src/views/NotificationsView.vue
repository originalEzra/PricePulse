<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { notificationApi } from '../api'
import { errorMessage } from '../api/client'
import type { NotificationItem } from '../api/types'
import { formatPrice } from '../utils/format'
import { useNotificationsStore } from '../stores/notifications'

const notifications = useNotificationsStore()
const items = ref<NotificationItem[]>([])
const filter = ref<'ALL' | 'UNREAD' | 'READ'>('ALL')
const loading = ref(true)
const error = ref('')
const actionError = ref('')
let pending: AbortController | null = null
let requestVersion = 0

async function load(showLoading = true) {
  pending?.abort()
  const request = new AbortController()
  pending = request
  const version = ++requestVersion
  if (showLoading) loading.value = true
  error.value = ''
  try {
    const result = await notificationApi.list(filter.value === 'ALL' ? undefined : filter.value, request.signal)
    if (version === requestVersion) items.value = result
  }
  catch (cause) { if (version === requestVersion && !request.signal.aborted) error.value = errorMessage(cause) }
  finally { if (version === requestVersion) { loading.value = false; pending = null } }
}

async function markRead(item: NotificationItem) {
  if (item.status === 'READ') return
  actionError.value = ''
  try {
    const updated = await notifications.markRead(item)
    if (!updated) return
    const index = items.value.findIndex((row) => row.id === item.id)
    if (index >= 0) {
      if (filter.value === 'UNREAD') items.value.splice(index, 1)
      else items.value[index] = updated
    }
  } catch (cause) { actionError.value = errorMessage(cause) }
}

async function markAllRead() {
  actionError.value = ''
  try { if (await notifications.markAllRead() != null) await load(false) }
  catch (cause) { actionError.value = errorMessage(cause) }
}

function refresh() {
  actionError.value = ''
  void notifications.refresh()
  void load(false)
}

const date = (value: string) => new Date(value).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
watch(filter, () => void load(), { immediate: true })
watch(() => notifications.revision, () => void load(false))
onBeforeUnmount(() => { requestVersion++; pending?.abort() })
</script>

<template>
  <div class="page-wrap narrow-page">
    <header class="page-head"><div><p class="eyebrow">NOTIFICATION CENTER</p><h1>通知中心</h1><p class="muted">{{ notifications.reconnecting ? '连接暂时中断，正在自动重试。' : '页面可见时每 5 秒自动更新，新提醒会在右上角提示。' }}</p></div><div class="unread-badge"><strong>{{ notifications.unreadCount ?? '—' }}</strong><span>条未读</span></div></header>
    <div class="notification-toolbar">
      <div class="filter-bar">
        <button v-for="entry in (['ALL', 'UNREAD', 'READ'] as const)" :key="entry" :class="{ active: filter === entry }" @click="filter = entry">{{ entry === 'ALL' ? '全部' : entry === 'UNREAD' ? '未读' : '已读' }}</button>
      </div>
      <div class="notification-actions">
        <button class="text-button" :disabled="!notifications.unreadCount || notifications.markingAll || notifications.reading.size > 0" @click="markAllRead">{{ notifications.markingAll ? '正在处理…' : '全部标为已读' }}</button>
        <button class="button secondary" :disabled="loading" @click="refresh">刷新</button>
      </div>
    </div>
    <p v-if="actionError || error" class="page-error">{{ actionError || error }} <button @click="refresh">重试</button></p>
    <section class="panel notification-list">
      <div v-if="loading" class="loading-block">加载通知中…</div>
      <button v-for="item in items" :key="item.id" class="notification-row" :class="{ unread: item.status === 'UNREAD' }" :disabled="notifications.markingAll || notifications.reading.has(item.id)" @click="markRead(item)">
        <span class="notice-dot"></span>
        <span class="notice-symbol">{{ item.stockSymbol.slice(0, 2) }}</span>
        <span class="notice-copy"><strong>{{ item.title }}</strong><span>{{ item.content }}</span><small>{{ item.stockName }} · 行情时间 {{ date(item.eventTime) }}</small></span>
        <span class="notice-price"><strong>{{ formatPrice(item.triggerPrice) }}</strong><small>{{ date(item.createdAt) }}</small></span>
      </button>
      <div v-if="!loading && items.length === 0" class="empty-state roomy"><strong>没有{{ filter === 'UNREAD' ? '未读' : filter === 'READ' ? '已读' : '' }}通知</strong><span>规则被价格真正穿越后，通知会出现在这里。</span></div>
    </section>
  </div>
</template>

<style scoped>
.notification-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin-bottom: 16px; flex-wrap: wrap; }
.filter-bar { margin-bottom: 0; }
.notification-actions { display: flex; align-items: center; gap: 16px; }
.text-button:disabled { opacity: .45; cursor: default; }
.notification-row:disabled { cursor: wait; opacity: .7; }
</style>
