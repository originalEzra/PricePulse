<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { notificationApi } from '../api'
import { errorMessage } from '../api/client'
import type { NotificationItem } from '../api/types'

const items = ref<NotificationItem[]>([])
const filter = ref<'ALL' | 'UNREAD' | 'READ'>('ALL')
const loading = ref(true)
const error = ref('')
const unread = ref(0)

async function load() {
  loading.value = true; error.value = ''
  try {
    const allRequest = notificationApi.list()
    const filteredRequest = filter.value === 'ALL' ? allRequest : notificationApi.list(filter.value)
    const [all, filtered] = await Promise.all([allRequest, filteredRequest])
    unread.value = all.filter((item) => item.status === 'UNREAD').length
    items.value = filtered
  }
  catch (cause) { error.value = errorMessage(cause) }
  finally { loading.value = false }
}

async function markRead(item: NotificationItem) {
  if (item.status === 'READ') return
  try {
    const updated = await notificationApi.read(item.id)
    const index = items.value.findIndex((row) => row.id === item.id)
    if (index >= 0) items.value[index] = updated
    unread.value = Math.max(0, unread.value - 1)
  } catch (cause) { error.value = errorMessage(cause) }
}

const date = (value: string) => new Date(value).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
onMounted(load)
</script>

<template>
  <div class="page-wrap narrow-page">
    <header class="page-head"><div><p class="eyebrow">NOTIFICATION CENTER</p><h1>通知中心</h1><p class="muted">每一条通知都可以追溯到真实触发记录。</p></div><div class="unread-badge"><strong>{{ unread }}</strong><span>条未读</span></div></header>
    <div class="filter-bar">
      <button v-for="entry in (['ALL', 'UNREAD', 'READ'] as const)" :key="entry" :class="{ active: filter === entry }" @click="filter = entry; load()">{{ entry === 'ALL' ? '全部' : entry === 'UNREAD' ? '未读' : '已读' }}</button>
    </div>
    <p v-if="error" class="page-error">{{ error }}</p>
    <section class="panel notification-list">
      <div v-if="loading" class="loading-block">加载通知中…</div>
      <button v-for="item in items" :key="item.id" class="notification-row" :class="{ unread: item.status === 'UNREAD' }" @click="markRead(item)">
        <span class="notice-dot"></span>
        <span class="notice-symbol">{{ item.stockSymbol.slice(0, 2) }}</span>
        <span class="notice-copy"><strong>{{ item.title }}</strong><span>{{ item.content }}</span><small>{{ item.stockName }} · 行情时间 {{ date(item.eventTime) }}</small></span>
        <span class="notice-price"><strong>{{ Number(item.triggerPrice).toFixed(2) }}</strong><small>{{ date(item.createdAt) }}</small></span>
      </button>
      <div v-if="!loading && items.length === 0" class="empty-state roomy"><strong>没有{{ filter === 'UNREAD' ? '未读' : filter === 'READ' ? '已读' : '' }}通知</strong><span>规则被价格真正穿越后，通知会出现在这里。</span></div>
    </section>
  </div>
</template>
