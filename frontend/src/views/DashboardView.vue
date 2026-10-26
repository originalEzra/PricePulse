<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { stockApi, marketApi, ruleApi } from '../api'
import { errorMessage } from '../api/client'
import type { AlertRule, QuoteHistory, Stock } from '../api/types'
import PriceSparkline from '../components/PriceSparkline.vue'
import RuleModal from '../components/RuleModal.vue'

const stocks = ref<Stock[]>([])
const rules = ref<AlertRule[]>([])
const quotes = ref<QuoteHistory[]>([])
const selectedId = ref<number | null>(null)
const search = ref('')
const loading = ref(true)
const error = ref('')
const modalOpen = ref(false)
const editingRule = ref<AlertRule | null>(null)

const filteredStocks = computed(() => {
  const needle = search.value.trim().toLowerCase()
  return needle
    ? stocks.value.filter((s) => `${s.symbol} ${s.name}`.toLowerCase().includes(needle))
    : stocks.value
})
const selectedStock = computed(() => stocks.value.find((s) => s.id === selectedId.value) ?? null)
const selectedRules = computed(() => rules.value.filter((r) => r.stockId === selectedId.value))
const prices = computed(() => [...quotes.value].reverse().map((q) => Number(q.price)))
const priceMove = computed(() => {
  if (prices.value.length < 2) return null
  const first = prices.value[0]!
  const last = prices.value.at(-1)!
  return ((last - first) / first) * 100
})
const activeRuleCount = computed(() => rules.value.filter((r) => r.status === 'ACTIVE' || r.status === 'COOLDOWN').length)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [stockData, ruleData] = await Promise.all([stockApi.list(), ruleApi.list()])
    stocks.value = stockData
    rules.value = ruleData
    if (!selectedId.value && stocks.value[0]) selectedId.value = stocks.value[0].id
    await loadQuotes()
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

async function selectStock(id: number) {
  selectedId.value = id
  await loadQuotes()
}

async function loadQuotes() {
  if (!selectedId.value) return
  try { quotes.value = await marketApi.history(selectedId.value) } catch { quotes.value = [] }
}

function openCreate() { editingRule.value = null; modalOpen.value = true }
function openEdit(rule: AlertRule) { editingRule.value = rule; modalOpen.value = true }
function replaceRule(updated: AlertRule) {
  const index = rules.value.findIndex((r) => r.id === updated.id)
  if (index >= 0) rules.value[index] = updated
  else rules.value.unshift(updated)
}

async function toggleRule(rule: AlertRule) {
  error.value = ''
  try { replaceRule(rule.status === 'PAUSED' ? await ruleApi.resume(rule) : await ruleApi.pause(rule)) }
  catch (cause) { error.value = errorMessage(cause) }
}

async function removeRule(rule: AlertRule) {
  if (!confirm(`确认删除规则“${rule.name}”？触发历史会保留。`)) return
  error.value = ''
  try {
    await ruleApi.remove(rule)
    rules.value = rules.value.filter((item) => item.id !== rule.id)
  } catch (cause) { error.value = errorMessage(cause) }
}

const statusLabel: Record<string, string> = { ACTIVE: '监控中', COOLDOWN: '冷却中', PAUSED: '已暂停', COMPLETED: '已完成' }
const directionLabel: Record<string, string> = { ABOVE: '上破', BELOW: '下破' }
const money = (value: number | null) => value == null ? '—' : Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 })
const date = (value: string | null) => value ? new Date(value).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '尚无行情'

onMounted(load)
</script>

<template>
  <div class="page-wrap">
    <header class="page-head">
      <div><p class="eyebrow">MARKET OVERVIEW</p><h1>行情与提醒</h1><p class="muted">追踪价格边界，而不是持续盯盘。</p></div>
      <button class="button primary" :disabled="!selectedStock" @click="openCreate">＋ 创建提醒</button>
    </header>
    <p v-if="error" class="page-error">{{ error }} <button @click="load">重试</button></p>
    <section class="stat-grid">
      <article class="stat-card"><span>监控股票</span><strong>{{ stocks.length }}</strong><small>当前演示标的</small></article>
      <article class="stat-card"><span>运行中规则</span><strong>{{ activeRuleCount }}</strong><small>ACTIVE + COOLDOWN</small></article>
      <article class="stat-card accent"><span>已配置规则</span><strong>{{ rules.length }}</strong><small>包含暂停与已完成</small></article>
    </section>
    <div class="dashboard-grid">
      <section class="panel stock-panel">
        <div class="panel-head"><div><p class="eyebrow">WATCHLIST</p><h2>股票列表</h2></div><input v-model="search" class="compact-input" placeholder="搜索代码或名称" /></div>
        <div v-if="loading" class="loading-block">加载行情中…</div>
        <button v-for="stock in filteredStocks" :key="stock.id" class="stock-row" :class="{ selected: stock.id === selectedId }" @click="selectStock(stock.id)">
          <span class="ticker-avatar">{{ stock.symbol.slice(0, 2) }}</span>
          <span class="stock-name"><strong>{{ stock.symbol }}</strong><small>{{ stock.name }}</small></span>
          <span class="stock-price"><strong>{{ money(stock.latestPrice) }}</strong><small>{{ stock.currency }}</small></span>
        </button>
        <div v-if="!loading && filteredStocks.length === 0" class="empty-state">没有匹配的股票</div>
      </section>
      <section class="panel market-panel">
        <div v-if="selectedStock" class="market-summary">
          <div><p class="eyebrow">{{ selectedStock.market }} · {{ selectedStock.currency }}</p><h2>{{ selectedStock.name }}</h2><p class="ticker-large">{{ selectedStock.symbol }}</p></div>
          <div class="latest-price"><span>最新价格</span><strong>{{ money(selectedStock.latestPrice) }}</strong><small>{{ date(selectedStock.latestQuoteAt) }}</small></div>
        </div>
        <div v-if="selectedStock" class="chart-meta"><span>最近 {{ prices.length }} 个有效行情点</span><strong v-if="priceMove !== null" :class="priceMove >= 0 ? 'positive' : 'negative'">{{ priceMove >= 0 ? '+' : '' }}{{ priceMove.toFixed(2) }}%</strong></div>
        <PriceSparkline :values="prices" />
        <div class="rule-section-head"><div><p class="eyebrow">ALERT RULES</p><h2>当前提醒</h2></div><button class="text-button" @click="openCreate">添加规则 ＋</button></div>
        <div v-if="selectedRules.length" class="rule-list">
          <article v-for="rule in selectedRules" :key="rule.id" class="rule-row">
            <span class="direction-dot" :class="rule.direction.toLowerCase()">{{ rule.direction === 'ABOVE' ? '↑' : '↓' }}</span>
            <div class="rule-main"><strong>{{ rule.name }}</strong><span>{{ directionLabel[rule.direction] }} {{ money(rule.thresholdPrice) }} · {{ rule.triggerMode === 'ONCE' ? '单次' : '重复' }}</span></div>
            <span class="status-pill" :class="rule.status.toLowerCase()">{{ statusLabel[rule.status] ?? rule.status }}</span>
            <div class="row-actions"><button @click="openEdit(rule)">编辑</button><button v-if="rule.status !== 'COMPLETED'" @click="toggleRule(rule)">{{ rule.status === 'PAUSED' ? '启用' : '暂停' }}</button><button class="danger" @click="removeRule(rule)">删除</button></div>
          </article>
        </div>
        <div v-else class="empty-state roomy"><strong>还没有提醒规则</strong><span>选择上破或下破价格，系统会在真正穿越阈值时触发。</span><button class="button secondary" @click="openCreate">创建第一条规则</button></div>
      </section>
    </div>
    <RuleModal :open="modalOpen" :stock="selectedStock" :rule="editingRule" @close="modalOpen = false" @saved="replaceRule" />
  </div>
</template>
