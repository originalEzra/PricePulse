<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { stockApi, marketApi, ruleApi } from '../api'
import { ApiClientError, errorMessage } from '../api/client'
import type { AlertRule, AlertRuleDraft, SaveAlertRule, Stock, MarketTimeline, TimelineWindow, TimelineEvent } from '../api/types'
import AiDraftModal from '../components/AiDraftModal.vue'
import PriceSparkline from '../components/PriceSparkline.vue'
import RuleModal from '../components/RuleModal.vue'
import { formatPrice as money } from '../utils/format'
import type { TimedChartAnnotation } from '../utils/chart'
import { completeStockRules, reconcileShownRules, ruleLineColor, shownRuleThresholds } from '../utils/ruleDisplay'
import '../styles/ruleTable.css'
import { compactNewYorkTime } from '../utils/chartInspection'
import { applyWatchlistQuote, refreshWatchlistQuotes, type WatchlistQuote } from '../utils/watchlistPrices'

const stocks = ref<Stock[]>([])
const rules = ref<AlertRule[]>([])
const timeline = ref<MarketTimeline | null>(null)
const timelineWindow = ref<TimelineWindow>('1D')
const chartLoading = ref(false)
const chartError = ref('')
const extraEvents = ref<TimelineEvent[]>([])
const nextCursor = ref<string | null>(null)
const eventQuery = ref<{ from: string; to: string } | null>(null)
const eventsLoading = ref(false)
let timelineRequest: AbortController | null = null
let watchlistRequest: AbortController | null = null
const acceptedQuoteTimes = new Map<number, string>()
const watchlistError = ref('')
let refreshTimer: ReturnType<typeof setInterval> | null = null
let disposed = false
const selectedId = ref<number | null>(null)
const search = ref('')
const loading = ref(true)
const error = ref('')
const modalOpen = ref(false)
const editingRule = ref<AlertRule | null>(null)
const initialDraft = ref<SaveAlertRule | null>(null)
const aiModalOpen = ref(false)
const shownRulesByStock = reactive(new Map<number, Set<number>>())
const rulesLoading = ref(false), rulesError = ref('')

const filteredStocks = computed(() => {
  const needle = search.value.trim().toLowerCase()
  return needle
    ? stocks.value.filter((s) => `${s.symbol} ${s.name}`.toLowerCase().includes(needle))
    : stocks.value
})
const selectedStock = computed(() => stocks.value.find((s) => s.id === selectedId.value) ?? null)
const selectedRules = computed(() => rules.value.filter((r) => r.stockId === selectedId.value))
const prices = computed(() => timeline.value?.points.map(point => Number(point.price)) ?? [])
const quoteTimes = computed(() => timeline.value?.points.map(point => point.eventTime) ?? [])
const shownRuleIds = computed(() => shownRulesByStock.get(selectedId.value ?? 0) ?? new Set<number>())
const chartThresholds = computed(() => shownRuleThresholds(selectedRules.value, shownRuleIds.value))
const allEvents = computed(() => [...(timeline.value?.events ?? []), ...extraEvents.value])
const deliveryLabel = { PENDING: '通知待生成', UNREAD: '通知未读', READ: '通知已读', FAILED: '通知投递异常' }
const chartAnnotations = computed<TimedChartAnnotation[]>(() => allEvents.value.map(event => ({
  id: event.triggerId, eventTime: event.eventTime, price: Number(event.price), kind: event.ruleSnapshot.direction,
  label: event.ruleSnapshot.direction === 'ABOVE' ? '上破触发' : '下破触发',
  ruleName: event.ruleSnapshot.name, threshold: event.ruleSnapshot.thresholdPrice,
  result: `已触发 · ${deliveryLabel[event.notificationStatus]}`, rawPrice: event.price,
  ruleSnapshot: event.ruleSnapshot,
  detail: `${event.ruleSnapshot.name} · 当时阈值 ${money(event.ruleSnapshot.thresholdPrice)} · ${deliveryLabel[event.notificationStatus]} · #${event.triggerId}`,
})))
const connectionLabel: Record<string, string> = { DISABLED: '未连接行情源', CONNECTING: '连接中', SUBSCRIBED: '行情已连接', RECONNECTING: '正在重连', REJECTED: '认证或订阅被拒绝', STOPPED: '行情已停止' }
const freshnessLabel: Record<string, string> = { MARKET_CLOSED: '正常交易时段已休市', NO_DATA: '尚未收到成交', DELAYED: '成交数据延迟', RECENT_IEX_TRADE: '近期 IEX 成交' }
const shortFreshness: Record<string, string> = { MARKET_CLOSED: '已休市', NO_DATA: '暂无成交', DELAYED: '数据已延迟', RECENT_IEX_TRADE: '近期成交' }
watch(timelineWindow, () => { void loadQuotes() })
onBeforeUnmount(() => {
  disposed = true; timelineRequest?.abort(); watchlistRequest?.abort()
  if (refreshTimer) clearInterval(refreshTimer)
})
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
    if (disposed) return
    watchlistRequest?.abort(); watchlistRequest = null
    acceptedQuoteTimes.clear()
    stocks.value = stockData.filter(stock => stock.market === 'US')
    rules.value = ruleData
    if (!selectedId.value && stocks.value[0]) selectedId.value = stocks.value[0].id
    void loadWatchlistPrices()
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

function updateStockPrice(quote: WatchlistQuote) {
  const stock = stocks.value.find(item => item.id === quote.id)
  if (stock) applyWatchlistQuote(stock, quote, acceptedQuoteTimes)
}

async function loadWatchlistPrices() {
  if (disposed || watchlistRequest || !stocks.value.length) return
  const request = new AbortController()
  watchlistRequest = request
  try {
    const failed = await refreshWatchlistQuotes(stocks.value.filter(stock => stock.id !== selectedId.value).map(stock => stock.id),
      async (id, signal) => (await marketApi.timeline(id, '1D', signal)).stock, updateStockPrice, request.signal)
    if (!disposed && !request.signal.aborted) watchlistError.value = failed.length ? `${failed.length} 只股票报价暂未更新，将自动重试` : ''
  } finally {
    if (watchlistRequest === request) watchlistRequest = null
  }
}

function showRule(rule: AlertRule, event: Event) {
  const shown = new Set(shownRulesByStock.get(rule.stockId) ?? [])
  if ((event.target as HTMLInputElement).checked) shown.add(rule.id)
  else shown.delete(rule.id)
  shownRulesByStock.set(rule.stockId, shown)
}

async function loadQuotes(background = false) {
  if (!selectedId.value || disposed) return
  if (background && (chartLoading.value || eventsLoading.value)) return
  timelineRequest?.abort()
  const request = new AbortController()
  timelineRequest = request
  const stockId = selectedId.value, window = timelineWindow.value
  chartLoading.value = true; chartError.value = ''
  rulesError.value = ''
  if (!background) { timeline.value = null; extraEvents.value = []; nextCursor.value = null }
  try {
    const data = await marketApi.timeline(stockId, window, request.signal)
    if (request.signal.aborted || disposed) return
    // Keep an in-progress event pagination snapshot while still refreshing prices and feed health.
    const paging = background && timeline.value && (extraEvents.value.length > 0 || nextCursor.value)
    if (paging && timeline.value) {
      data.events = timeline.value.events; data.eventCursor = nextCursor.value; data.watermark = timeline.value.watermark
    } else {
      extraEvents.value = []; nextCursor.value = data.eventCursor
      eventQuery.value = { from: data.range.from, to: data.range.asOf }
    }
    timeline.value = data
    updateStockPrice(data.stock)
    rulesLoading.value = true
    try {
      const listed = background ? await ruleApi.list() : rules.value
      const complete = await completeStockRules(stockId, data.thresholds, [...rules.value, ...listed], async id => {
        if (request.signal.aborted) throw new DOMException('Aborted', 'AbortError')
        try { return await ruleApi.get(id, request.signal) }
        catch (cause) { if (cause instanceof ApiClientError && cause.status === 404) return null; throw cause }
      })
      if (!request.signal.aborted && !disposed) {
        rules.value = [...listed.filter(rule => rule.stockId !== stockId), ...complete]
        shownRulesByStock.set(stockId, reconcileShownRules(shownRulesByStock.get(stockId), complete))
      }
    } catch (cause) {
      if (!request.signal.aborted && !disposed) rulesError.value = '完整规则读取失败，列表可能不完整：' + errorMessage(cause)
    }
  } catch (cause) { if (!request.signal.aborted && !disposed) chartError.value = errorMessage(cause) }
  finally { if (timelineRequest === request) { chartLoading.value = false; rulesLoading.value = false } }
}
async function moreEvents() {
  const data = timeline.value, cursor = nextCursor.value, request = timelineRequest, range = eventQuery.value
  if (!data || !cursor || !range || !selectedId.value || eventsLoading.value) return
  eventsLoading.value = true
  try {
    const page = await marketApi.events(selectedId.value, range.from, range.to, cursor, request?.signal)
    if (timeline.value !== data || disposed) return
    extraEvents.value.push(...page.events); nextCursor.value = page.eventCursor
  } catch (cause) { if (!request?.signal.aborted && !disposed) chartError.value = errorMessage(cause) }
  finally { eventsLoading.value = false }
}

function openCreate() { editingRule.value = null; initialDraft.value = null; modalOpen.value = true }
function openEdit(rule: AlertRule) { editingRule.value = rule; initialDraft.value = null; modalOpen.value = true }
async function useAiDraft(draft: AlertRuleDraft) {
  if (!stocks.value.some((stock) => stock.id === draft.stockId)) {
    try {
      stocks.value.push(await stockApi.get(draft.stockId))
    } catch (cause) {
      aiModalOpen.value = false
      error.value = errorMessage(cause)
      return
    }
  }
  selectedId.value = draft.stockId
  editingRule.value = null
  initialDraft.value = {
    stockId: draft.stockId,
    name: draft.name,
    note: draft.note ?? undefined,
    direction: draft.direction,
    thresholdPrice: draft.thresholdPrice,
    triggerMode: draft.triggerMode,
    rearmPrice: draft.rearmPrice,
  }
  aiModalOpen.value = false
  modalOpen.value = true
  loadQuotes()
}
function replaceRule(updated: AlertRule) {
  const index = rules.value.findIndex((r) => r.id === updated.id)
  if (index >= 0) rules.value[index] = updated
  else rules.value.unshift(updated)
  void loadQuotes()
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
const date = (value: string | null) => value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'America/New_York', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) + '（纽约）' : '尚无行情'

onMounted(() => {
  void load()
  refreshTimer = setInterval(() => {
    if (document.visibilityState === 'visible') { void loadQuotes(true); void loadWatchlistPrices() }
  }, 10000)
})
</script>

<template>
  <div class="page-wrap">
    <header class="page-head">
      <div><p class="eyebrow">MARKET OVERVIEW</p><h1>行情与提醒</h1><div class="dashboard-counts"><span>{{ stocks.length }} 只标的</span><span>{{ activeRuleCount }} 条运行中规则</span><span>{{ rules.length }} 条已加载规则</span></div></div>
      <div class="head-actions">
        <button class="button ai-button" @click="aiModalOpen = true">✦ AI 文本生成</button>
        <button class="button primary" :disabled="!selectedStock" @click="openCreate">＋ 创建提醒</button>
      </div>
    </header>
    <p v-if="error" class="page-error">{{ error }} <button @click="load">重试</button></p>
    <div class="dashboard-grid">
      <section class="panel stock-panel">
        <div class="panel-head"><div><p class="eyebrow">WATCHLIST</p><h2>股票列表</h2></div><input v-model="search" class="compact-input" placeholder="搜索代码或名称" /></div>
        <div v-if="loading" class="loading-block">加载行情中…</div>
        <button v-for="stock in filteredStocks" :key="stock.id" class="stock-row" :class="{ selected: stock.id === selectedId }" @click="selectStock(stock.id)">
          <span class="ticker-avatar">{{ stock.symbol.slice(0, 2) }}</span>
          <span class="stock-name"><strong>{{ stock.symbol }}</strong><small>{{ stock.name }}</small></span>
          <span class="stock-price" :title="date(stock.latestQuoteAt)"><strong>{{ money(stock.latestPrice) }}</strong><small>{{ stock.currency }}</small></span>
        </button>
        <div v-if="!loading && filteredStocks.length === 0" class="empty-state">没有匹配的股票</div>
        <p v-if="watchlistError" class="watchlist-warning" role="status">{{ watchlistError }}</p>
      </section>
      <section class="panel market-panel">
        <p v-if="chartError" class="page-error">{{ chartError }} <button @click="loadQuotes()">重试</button></p>
        <PriceSparkline :values="prices" :timestamps="quoteTimes" :currency="selectedStock?.currency" :thresholds="chartThresholds" :temporal-annotations="chartAnnotations"
          :scope-key="`${selectedId}:${timelineWindow}`"
          :exact-prices="timeline?.points.map(point => point.price)"
          :time-range="timeline?.range" :session-ids="timeline?.points.map(point => point.session)"
          :trading-sessions="timeline?.sessions" :compress-non-trading="timelineWindow === '1W' || timelineWindow === '1M'"
          :point-kinds="timeline?.points.map(point => point.kind)" :gap-ranges="timeline?.gaps.filter(gap => gap.reason === 'SPARSE_IEX_OR_CACHE_PENDING')"
          :break-times="timeline?.gaps.filter(gap => gap.reason === 'LIVE_BASELINE_AFTER_GAP').map(gap => gap.to)"
          :point-details="timeline?.points.map(point => point.kind === 'BAR_CLOSE' ? `分钟收盘价 · 区间结束后可知 · 修订 ${point.revision}` : '实际成交 · 非聚合均价')"
          :empty-label="chartLoading ? '正在读取历史缓存…' : '此范围暂无缓存行情'">
          <template #heading><div v-if="selectedStock" class="stock-chart-heading"><h2>{{ selectedStock.name }}</h2><span class="stock-symbol">{{ selectedStock.symbol }}</span>
            <div class="window-tabs" aria-label="行情时间窗口"><button v-for="item in (['1H', '1D', '1W', '1M'] as const)" :key="item" :class="{ active: timelineWindow === item }" @click="timelineWindow = item">{{ item }}</button></div>
            <p v-if="timeline" class="market-status">IEX · {{ shortFreshness[timeline.health.freshness] ?? timeline.health.freshness }} · {{ compactNewYorkTime(selectedStock.latestQuoteAt ?? '').slice(6) }}</p>
            <p v-if="timeline" class="monitor-status">{{ connectionLabel[timeline.health.connection] ?? timeline.health.connection }} · {{ timeline.health.businessWritesEnabled ? '正式提醒已启用' : '只读观察，正式提醒未启用' }}</p>
            <p v-if="timeline?.health.lastError" class="market-warning">行情有异常，请查看行情详情</p>
            <p v-if="timeline?.dataQuality && (!timeline.dataQuality.historyCoverageComplete || !timeline.dataQuality.calendarCoverageComplete || timeline.dataQuality.unavailableMinutes > 0)" class="market-warning">历史有缺口，详见行情详情</p>
          </div></template>
          <template #latest><template v-if="selectedStock"><small>{{ timeline?.stock.priceKind === 'HISTORICAL_BAR_CLOSE' ? '最近分钟收盘价' : '最新有效价格' }}（{{ selectedStock.currency }}）</small><strong>{{ money(selectedStock.latestPrice) }}</strong><small>{{ compactNewYorkTime(selectedStock.latestQuoteAt ?? '') }}</small><b v-if="priceMove != null" class="price-return" :class="priceMove >= 0 ? 'positive' : 'negative'">{{ priceMove >= 0 ? '+' : '' }}{{ priceMove.toFixed(2) }}%</b></template></template>
        </PriceSparkline>
        <div class="market-bottom-tools"><details v-if="timeline" class="market-data-details"><summary>行情详情</summary><p>Alpaca · IEX 单一交易所 · 未复权 · {{ freshnessLabel[timeline.health.freshness] }}</p><p>{{ date(selectedStock?.latestQuoteAt ?? null) }} · {{ prices.length }} 个绘图点</p><p v-if="timeline.dataQuality">分钟点 {{ timeline.dataQuality.displayedBars }} / {{ timeline.dataQuality.availableBars }} · 预期区间 {{ timeline.dataQuality.expectedMinutes }} · 暂无分钟线 {{ timeline.dataQuality.unavailableMinutes }}</p><p v-if="timeline.dataQuality && !timeline.dataQuality.historyCoverageComplete">历史下载未覆盖完整范围。</p><p v-if="timeline.dataQuality && !timeline.dataQuality.calendarCoverageComplete">交易日历未覆盖完整范围，未知时段未压缩。</p><p v-if="timeline.gaps.length">有稀疏成交、缓存或实时基线缺口；不补造价格和提醒。</p><p v-if="timeline.health.lastError">最近安全错误：{{ timeline.health.lastError }}</p><p>{{ allEvents.length }} 条已加载历史触发，独立于阈值线勾选。</p></details><button v-if="nextCursor" class="text-button" :disabled="eventsLoading" @click="moreEvents">{{ eventsLoading ? '读取中…' : '加载更多触发' }}</button><button class="text-button" :disabled="chartLoading" @click="loadQuotes()">{{ chartLoading ? '读取中…' : '刷新行情' }}</button></div>
      </section>
    </div>
    <section class="panel rules-panel">
        <div class="rule-section-head"><h2>{{ selectedStock?.symbol }} 的提醒规则 <small>（{{ selectedRules.length }}）</small></h2><button class="text-button" :disabled="!selectedStock" @click="openCreate">添加规则 ＋</button></div>
        <p v-if="rulesLoading" class="rule-display-hint" role="status">正在同步完整规则列表…</p>
        <p v-if="rulesError" class="page-error" role="alert">{{ rulesError }} <button @click="loadQuotes()">重试</button></p>
        <div v-if="selectedRules.length" class="rule-table-scroll" role="region" aria-label="该股票的全部提醒规则" tabindex="0">
          <table class="rule-table">
            <caption>显示 {{ chartThresholds.length }} 条阈值线 · 勾选不改变监控状态，历史触发始终保留。</caption>
            <thead><tr><th scope="col">显示</th><th scope="col">规则名称</th><th scope="col">方向</th><th scope="col">阈值价格</th><th scope="col">规则状态</th><th scope="col">触发方式</th><th scope="col">操作</th></tr></thead>
            <tbody><tr v-for="rule in selectedRules" :key="rule.id">
              <td><input type="checkbox" :checked="shownRuleIds.has(rule.id)" :style="{ accentColor: ruleLineColor(rule.id) }" :aria-label="`显示规则“${rule.name}”的阈值线`" @change="showRule(rule, $event)" /></td>
              <td><span class="rule-name"><i :style="{ background: ruleLineColor(rule.id) }" aria-hidden="true"></i><strong>{{ rule.name }}</strong></span></td>
              <td class="rule-direction">{{ rule.direction === 'ABOVE' ? '↑' : '↓' }} {{ directionLabel[rule.direction] }}</td>
              <td class="rule-price">{{ money(rule.thresholdPrice) }}<small v-if="rule.rearmPrice != null">再激活 {{ money(rule.rearmPrice) }}</small></td>
              <td><span class="status-pill" :class="rule.status.toLowerCase()"><b>{{ rule.status }}</b><small>{{ statusLabel[rule.status] ?? rule.status }}</small></span></td>
              <td>{{ rule.triggerMode === 'ONCE' ? '一次性' : '重复' }}</td>
              <td><div class="row-actions"><button @click="openEdit(rule)">编辑</button><button v-if="rule.status !== 'COMPLETED'" @click="toggleRule(rule)">{{ rule.status === 'PAUSED' ? '启用' : '暂停' }}</button><button class="danger" @click="removeRule(rule)">删除</button></div></td>
            </tr></tbody>
          </table>
        </div>
        <div v-else-if="!rulesLoading && !loading && !rulesError && !chartError" class="empty-state roomy"><strong>还没有提醒规则</strong><span>选择上破或下破价格，系统会在真正穿越阈值时触发。</span><button class="button secondary" @click="openCreate">创建第一条规则</button></div>
      </section>
    <AiDraftModal :open="aiModalOpen" @close="aiModalOpen = false" @use-draft="useAiDraft" />
    <RuleModal :open="modalOpen" :stock="selectedStock" :rule="editingRule" :initial-draft="initialDraft" @close="modalOpen = false" @saved="replaceRule" />
  </div>
</template>

<style scoped>
.page-wrap { padding-top: 26px; }
.page-head { margin-bottom: 18px; align-items: center; }
.page-head h1 { font-size: 32px; }
.dashboard-counts { display: flex; gap: 14px; flex-wrap: wrap; color: var(--muted); font-size: 11px; }
.dashboard-grid { grid-template-columns: 260px minmax(0, 1fr); gap: 14px; align-items: start; }
.stock-panel .panel-head { padding: 16px; gap: 10px; }
.stock-panel .panel-head h2 { font-size: 17px; }
.stock-panel .compact-input { width: 122px; font-size: 11px; }
.stock-row { padding: 13px 16px; gap: 9px; grid-template-columns: 36px minmax(0, 1fr) auto; }
.stock-name { min-width: 0; } .stock-name small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.stock-symbol { display: block; color: var(--muted); font-size: 12px; margin-bottom: 6px; }
.window-tabs { display: inline-flex; gap: 2px; background: #edf1eb; padding: 3px; border-radius: 9px; }
.window-tabs button { padding: 5px 10px; border: 0; border-radius: 6px; background: transparent; cursor: pointer; color: var(--muted); font-size: 12px; }
.window-tabs button.active { background: white; color: #23785b; box-shadow: 0 1px 5px #193b2310; }
.market-status, .monitor-status, .market-warning { margin: 6px 0 0; color: var(--muted); font-size: 10px; line-height: 1.4; }
.watchlist-warning { margin: 12px 16px; color: var(--muted); font-size: 12px; line-height: 1.5; }
.monitor-status, .market-warning { color: #946a2f; }
.market-bottom-tools { display: flex; gap: 16px; align-items: start; padding: 0 20px 12px; font-size: 11px; }
.market-data-details { margin-right: auto; min-width: 0; color: var(--muted); }
.market-data-details p { font-size: 11px; line-height: 1.6; margin: 7px 0 0; }
.rules-panel { margin-top: 14px; }
.rules-panel .rule-section-head { border-top: 0; padding: 16px 20px 12px; }
.positive { color: #20815d; } .negative { color: #c45a53; }
@media (max-width: 980px) { .dashboard-grid { grid-template-columns: minmax(0, 1fr); } }
</style>
