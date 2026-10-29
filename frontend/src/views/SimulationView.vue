<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, toRefs, watch } from 'vue'
import { marketApi, stockApi } from '../api'
import { errorMessage } from '../api/client'
import type { MarketTimeline, SimulationEvent, SimulationMode, StartSimulation, Stock } from '../api/types'
import PriceSparkline from '../components/PriceSparkline.vue'
import { formatPrice } from '../utils/format'
import { ruleStatusLabel, type TimedChartAnnotation } from '../utils/chart'
import { simulationSeed } from '../utils/simulationRequests'
import { useSimulationStore } from '../stores/simulation'
import { barGapRanges } from '../utils/timeAxis'
import { reconcileShownRules, ruleLineColor, simulationThresholds } from '../utils/ruleDisplay'
import { simulationPointBreaks, simulationTriggerCounts } from '../utils/simulationDisplay'
import '../styles/ruleTable.css'
import { compactNewYorkTime, priceDifference } from '../utils/chartInspection'

const modes: Record<SimulationMode, string> = { NORMAL: '正常波动', RISE: '持续上涨', FALL: '持续下跌', OSCILLATE: '反复震荡' }
const simulation = useSimulationStore(), session = simulation.controller
const { browseStockId, originKind, mode, percent, seedInput } = toRefs(simulation.draft)
const { run, timeline, events, eventsReady, busy, error, startCommands, advanceCommands } = toRefs(session)
const shownByRun = simulation.shownByRun
const stocks = ref<Stock[]>([])
const source = ref<MarketTimeline | null>(null), sourceLoading = ref(false), sourceError = ref('')
const selectedPointRef = ref(''), visibleEventCount = ref(100)
const chart = ref<InstanceType<typeof PriceSparkline> | null>(null)
const eventTable = ref<HTMLDivElement | null>(null)
const selectedEvents = ref(new Set<number>())
const loading = ref(true)
let sourceRequest: AbortController | null = null, disposed = false
const locked = computed(() => !!startCommands.value.pending)
const selectedPoint = computed(() => source.value?.points.find(p => p.pointRef === selectedPointRef.value))
const chartPoints = computed(() => [
  ...(timeline.value?.realPrefix ?? []).map(p => ({ ...p, pointSeq: p.eventTime === run.value?.origin.point.eventTime ? 0 : null, detail: p.kind === 'BAR_CLOSE' ? '冻结的真实分钟收盘价' : '冻结的真实成交价' })),
  ...(timeline.value?.simulation ?? []).map(p => ({ ...p, kind: 'SIMULATION', session: new Date(p.eventTime).toLocaleDateString('en-CA', { timeZone: 'America/New_York' }), detail: `模拟点 #${p.pointSeq}` })),
])
const prefixGaps = computed(() => barGapRanges(timeline.value?.realPrefix ?? []))
const pointBreaks = computed(() => simulationPointBreaks(timeline.value?.simulation ?? []))
const shownSnapshotIds = computed(() => shownByRun.get(run.value?.runId ?? '') ?? new Set<number>())
const thresholds = computed(() => simulationThresholds(run.value?.ruleSnapshots ?? [], shownSnapshotIds.value))
const triggerCounts = computed(() => simulationTriggerCounts(events.value))
const visibleEvents = computed(() => events.value.slice(0, visibleEventCount.value))
function eventAnnotation(e: SimulationEvent): TimedChartAnnotation { return {
  id: e.id, eventTime: e.eventTime, price: Number(e.price), kind: e.ruleSnapshot.direction, label: e.eventType === 'TRIGGERED' ? '模拟触发' : '模拟重新激活',
  ruleName: e.ruleSnapshot.name, threshold: e.ruleSnapshot.thresholdPrice, result: `${e.eventType === 'TRIGGERED' ? '已触发' : '已重新激活'} · ${ruleStatusLabel[e.toState]}`,
  pointSeq: e.pointSeq, rawPrice: e.price,
  ruleSnapshot: e.ruleSnapshot,
  detail: `${e.ruleSnapshot.name} · 快照阈值 ${formatPrice(Number(e.ruleSnapshot.thresholdPrice))} · 模拟点 #${e.pointSeq}`,
} }
const annotations = computed(() => events.value.filter(e => e.eventType === 'TRIGGERED').map(eventAnnotation))
const runReturn = computed(() => run.value ? priceDifference(Number(run.value.latestPrice), Number(run.value.origin.point.price)) : null)
watch(run, (current, previous) => {
  if (current?.runId !== previous?.runId) {
    selectedEvents.value = new Set(); visibleEventCount.value = 100
    if (previous) shownByRun.clear()
  }
  if (current) shownByRun.set(current.runId, reconcileShownRules(shownByRun.get(current.runId),
    current.ruleSnapshots.map(rule => ({ id: rule.snapshotRuleId, stockId: current.stockId, status: rule.currentState }))))
}, { immediate: true })
function locateEvent(event: SimulationEvent) { chart.value?.focusEvent(eventAnnotation(event)) }
async function selectChartEvents(ids: number[]) {
  selectedEvents.value = new Set(ids)
  const last = Math.max(...ids.map(id => events.value.findIndex(event => event.id === id)), -1)
  visibleEventCount.value = Math.max(visibleEventCount.value, last + 1)
  await nextTick()
  const table = eventTable.value, row = table?.querySelector<HTMLElement>(`[data-event-id="${ids[0]}"]`)
  if (table && row) table.scrollTop += row.getBoundingClientRect().top - table.getBoundingClientRect().top - 36
}
function showSnapshot(id: number, event: Event) {
  if (!run.value) return
  const shown = new Set(shownSnapshotIds.value)
  if ((event.target as HTMLInputElement).checked) shown.add(id); else shown.delete(id)
  shownByRun.set(run.value.runId, shown)
}

async function loadSource() {
  if (!browseStockId.value || disposed) return
  sourceRequest?.abort()
  const request = new AbortController(); sourceRequest = request
  sourceLoading.value = true; sourceError.value = ''; source.value = null; selectedPointRef.value = ''
  try {
    const result = await marketApi.timeline(browseStockId.value, '1D', request.signal)
    if (request.signal.aborted || disposed) return
    source.value = result; selectedPointRef.value = result.points.at(-1)?.pointRef ?? ''
  } catch (cause) { if (!request.signal.aborted && !disposed) sourceError.value = errorMessage(cause) }
  finally { if (sourceRequest === request) sourceLoading.value = false }
}
watch(browseStockId, loadSource)

async function restore() { await session.restore() }
async function begin() {
  if (busy.value || session.playing || session.needsRefresh || advanceCommands.value.pending) return
  let input: StartSimulation | undefined
  if (!startCommands.value.pending) {
    if (!source.value || (originKind.value === 'HISTORICAL_POINT' && !selectedPoint.value)) return
    let seed: string | null
    try { seed = simulationSeed(seedInput.value) } catch (cause) { error.value = errorMessage(cause); return }
    if (!Number.isFinite(percent.value) || percent.value <= 0 || percent.value > 0.3) { error.value = '每分钟最大幅度应大于 0 且不超过 0.3%。'; return }
    if (run.value && !window.confirm(`开始新实验将替换最近一次 ${run.value.origin.stockSymbol} 实验。确认替换？`)) return
    input = { stockId: browseStockId.value, origin: originKind.value === 'LATEST' ? { kind: 'LATEST' }
      : { kind: 'HISTORICAL_POINT', pointRef: selectedPoint.value!.pointRef, revision: selectedPoint.value!.revision },
      mode: mode.value, stepRatio: (percent.value / 100).toFixed(6), seed, expectedCurrentRunId: run.value?.runId ?? null }
  }
  await session.start(input)
}
async function advance() { await session.advance() }
async function more(kind: 'POINTS' | 'EVENTS') {
  if (kind === 'POINTS') await session.morePoints()
  else visibleEventCount.value += 100
}
function visibilityChanged() {
  if (document.visibilityState === 'hidden') session.leave('页面进入后台，自动播放已暂停。')
  else if (!disposed && !loading.value) void simulation.enter()
}
function pageHidden() { session.leave() }
function pageShown() { if (!disposed && !loading.value) void simulation.enter() }
onMounted(async () => {
  document.addEventListener('visibilitychange', visibilityChanged)
  window.addEventListener('pagehide', pageHidden); window.addEventListener('pageshow', pageShown)
  try {
    const available = await stockApi.list()
    if (disposed) return
    stocks.value = available.filter(s => s.market === 'US')
    await simulation.enter()
    if (!disposed) {
      if (!stocks.value.some(stock => stock.id === browseStockId.value)) browseStockId.value = run.value?.stockId ?? stocks.value[0]?.id ?? 0
      else void loadSource()
    }
  } catch (cause) { if (!disposed) error.value = errorMessage(cause) }
  finally { loading.value = false }
})
onBeforeUnmount(() => {
  disposed = true; sourceRequest?.abort(); session.leave()
  document.removeEventListener('visibilitychange', visibilityChanged)
  window.removeEventListener('pagehide', pageHidden); window.removeEventListener('pageshow', pageShown)
})
</script>

<template>
  <div class="page-wrap">
    <header class="page-head"><div><p class="eyebrow">MARKET LAB</p><h1>模拟行情</h1><p class="muted">冻结真实起点和本人规则，按交易小时模拟。结果自动保存，恢复后默认暂停。</p></div>
      <button class="button secondary" :disabled="busy || loading" @click="restore">恢复已保存结果</button></header>
    <p v-if="error" class="page-error" role="alert">{{ error }}</p>
    <p v-if="!busy && (startCommands.pending || advanceCommands.pending)" class="scenario-summary" role="status">上次操作结果尚待确认。重试沿用原请求，不会重复推进；查询后若仍未确认，请重试原操作。
      <button class="text-button" @click="restore">查询保存进度</button></p>
    <p v-if="loading" class="muted">正在恢复最近一次实验…</p>
    <div v-else class="simulation-grid">
      <section class="panel simulator-card">
        <div class="panel-head"><h2>开始新实验</h2></div>
        <div class="experiment-controls">
          <label>股票<select v-model="browseStockId" :disabled="locked"><option v-for="stock in stocks" :key="stock.id" :value="stock.id">{{ stock.symbol }} · {{ stock.name }}</option></select></label>
          <label>起点<select v-model="originKind" :disabled="locked"><option value="HISTORICAL_POINT">已闭合历史点</option><option value="LATEST" :disabled="!source?.health.lastAcceptedAt">最新有效实时成交</option></select></label>
          <label>行情模式<select v-model="mode" :disabled="locked"><option v-for="(label, key) in modes" :key="key" :value="key">{{ label }}</option></select></label>
          <label>波动幅度（%）<input v-model.number="percent" type="number" min="0.0001" max="0.3" step="0.001" :disabled="locked" /></label>
          <label v-if="originKind === 'HISTORICAL_POINT'" class="origin-picker">真实起点（纽约）<select v-model="selectedPointRef" :disabled="locked || sourceLoading"><option v-for="point in source?.points ?? []" :key="point.pointRef" :value="point.pointRef">{{ compactNewYorkTime(point.eventTime) }} · ${{ point.price }}</option></select></label>
        </div>
        <p v-if="sourceLoading" class="muted compact-message">正在读取真实历史…</p><p v-if="sourceError" class="form-error">{{ sourceError }}</p>
        <div class="experiment-actions"><details class="advanced-options"><summary>高级选项</summary><label>Seed（可留空）<input v-model="seedInput" inputmode="numeric" placeholder="自动生成并保存" :disabled="locked" /></label><button class="text-button" :disabled="locked || sourceLoading" @click="loadSource">刷新真实起点列表</button><p>配置仅用于新实验；已保存实验的输入保持冻结。新建前请暂停播放。</p><p v-if="source && !source.health.lastAcceptedAt">暂无已接受实时成交，请使用历史点。</p></details><button class="button primary" :disabled="busy || session.playing || session.needsRefresh || !!advanceCommands.pending || (!startCommands.pending && (sourceLoading || !source || (originKind === 'HISTORICAL_POINT' && !selectedPoint)))" @click="begin">{{ startCommands.pending ? '重试开始' : run ? '确认并开始新实验' : '开始新实验' }}</button></div>
        <p v-if="run && browseStockId !== run.stockId" class="compact-message">浏览其他股票不会改变 {{ run.origin.stockSymbol }} 的实验。<button class="text-button" @click="browseStockId = run.stockId">返回 {{ run.origin.stockSymbol }} 起点</button></p>
      </section>
      <section class="panel result-card">
        <template v-if="run">
          <div class="panel-head"><h2>最近一次实验</h2><span class="live-chip">已保存 · {{ session.playing ? '播放中' : '已暂停' }}</span></div>
          <strong class="saved-symbol">{{ run.origin.stockSymbol }} · {{ modes[run.parameters.mode] }}</strong>
          <dl class="saved-summary"><div><dt>起点</dt><dd>{{ compactNewYorkTime(run.origin.point.eventTime) }}<strong>${{ run.origin.point.price }}</strong></dd></div><div><dt>当前模拟</dt><dd>{{ compactNewYorkTime(run.simClock) }}<strong>${{ run.latestPrice }}</strong></dd></div><div><dt>已推进</dt><dd>{{ run.lastBatchNo }} 交易小时</dd></div></dl>
          <div class="experiment-totals"><span>规则快照 <b>{{ run.ruleSnapshots.length }}</b></span><span>已触发规则 <b>{{ eventsReady ? triggerCounts.size : '—' }}</b></span><span>{{ run.stats.triggered }} 次触发 · {{ run.stats.rearmed }} 次重新激活</span></div>
          <div class="playback-controls"><button class="button primary" :disabled="!session.playing && !session.canPlay" @click="session.playing ? session.pause() : session.play()">{{ session.playing ? '暂停播放' : '自动播放' }}</button><label>播放速度<select :value="session.delayMs" @change="session.setDelay(Number(($event.target as HTMLSelectElement).value))"><option :value="5000">慢速 · 5 秒/小时</option><option :value="2000">标准 · 2 秒/小时</option><option :value="1000">快速 · 1 秒/小时</option></select></label><button class="button secondary" :disabled="busy || session.playing || !!startCommands.pending || (!advanceCommands.pending && !session.ready)" @click="advance">{{ advanceCommands.pending ? '重试上次推进' : '下一交易小时' }}</button></div>
          <p class="playback-status" role="status">{{ session.notice }}<span v-if="busy"> · 正在处理本次请求</span></p>
          <p v-if="!run.generatorSupported" class="form-error">当前版本仅可查看保存结果，请新建实验后推进。</p>
          <details class="experiment-details"><summary>实验详情</summary><p>Seed {{ run.seed }} · 每分钟幅度 {{ Number(run.parameters.stepRatio) * 100 }}%。每小时累计 60 个交易分钟，跳过休市。播放速度仅调整两批之间的等待；暂停或离页不再请求新批次，已受理的一小时仍可完成。结果独立保存，不进入通知中心。</p></details>
        </template>
        <template v-else><p class="eyebrow">SAVED EXPERIMENT</p><h2>尚未开始实验</h2><p class="muted">选择一个真实历史点，开始后会冻结本人该股的规则配置。每个用户只保留最近一次实验。</p></template>
      </section>
    </div>
    <section v-if="run && timeline" class="panel simulation-chart-panel">
      <PriceSparkline ref="chart" :values="chartPoints.map(p => Number(p.price))" :timestamps="chartPoints.map(p => p.eventTime)" currency="USD" :time-range="timeline.range" @event-select="selectChartEvents"
        :scope-key="run.runId"
        :session-ids="chartPoints.map(p => p.session)" :point-kinds="chartPoints.map(p => p.kind)" :point-details="chartPoints.map(p => p.detail)"
        :trading-sessions="timeline.sessions" compress-non-trading :gap-ranges="prefixGaps" :break-times="pointBreaks"
        :exact-prices="chartPoints.map(p => p.price)" :point-seqs="chartPoints.map(p => p.pointSeq)" :origin-price="run.origin.point.price"
        :origin-time="run.origin.point.eventTime" :thresholds="thresholds" :temporal-annotations="annotations" event-scope="SIMULATION">
        <template #heading><h2>真实前缀与模拟结果</h2><p class="simulation-legend"><span class="real-key">●</span> 真实历史 <span class="simulation-key">●</span> 模拟走势</p><p class="chart-prompt">悬停查看 · 点击触发点展开详情</p></template>
        <template #latest><small>当前模拟价（USD）</small><strong>{{ formatPrice(Number(run.latestPrice)) }}</strong><small>{{ compactNewYorkTime(run.simClock) }}</small><b v-if="runReturn" class="price-return" :class="runReturn.value >= 0 ? 'positive' : 'negative'">{{ runReturn.percent >= 0 ? '+' : '' }}{{ runReturn.percent.toFixed(2) }}%</b></template>
      </PriceSparkline>
      <p v-if="timeline.pointCursor" class="compact-message">尚有未加载的已保存价格，缺少的区间保留断线。<button class="text-button" :disabled="busy || session.playing" @click="more('POINTS')">加载更多已保存价格</button></p>
      <p v-if="prefixGaps.length" class="compact-message">真实前缀有数据缺口，图中保留空段。</p>
    </section>
    <div v-if="run && timeline" class="simulation-bottom-grid">
      <section class="panel simulation-rule-section">
        <div class="rule-section-head"><h2>本次实验规则 <small>（{{ run.ruleSnapshots.length }}）</small></h2></div>
        <div v-if="run.ruleSnapshots.length" class="rule-table-scroll" role="region" aria-label="本次模拟的规则快照" tabindex="0">
          <table class="rule-table"><caption>勾选仅控制阈值线，模拟结果独立保存。</caption>
            <thead><tr><th scope="col">显示</th><th scope="col">规则</th><th scope="col">方向</th><th scope="col">阈值</th><th scope="col">状态</th><th scope="col">本次结果</th></tr></thead>
            <tbody><tr v-for="rule in run.ruleSnapshots" :key="rule.snapshotRuleId">
              <td><input type="checkbox" :checked="shownSnapshotIds.has(rule.snapshotRuleId)" :style="{ accentColor: ruleLineColor(rule.sourceRuleId) }" :aria-label="`显示快照“${rule.config.name}”的阈值线`" @change="showSnapshot(rule.snapshotRuleId, $event)" /></td>
              <td><span class="rule-name"><i :style="{ background: ruleLineColor(rule.sourceRuleId) }" aria-hidden="true"></i><strong>{{ rule.config.name }}</strong></span></td>
              <td class="rule-direction">{{ rule.config.direction === 'ABOVE' ? '↑ 上破' : '↓ 下破' }}</td>
              <td class="rule-price">{{ formatPrice(Number(rule.config.thresholdPrice)) }}</td>
              <td><span class="status-pill" :class="rule.currentState.toLowerCase()"><b>{{ rule.currentState }}</b><small>{{ ruleStatusLabel[rule.currentState] }}</small></span></td>
              <td class="rule-result" :class="{ triggered: (triggerCounts.get(rule.snapshotRuleId) ?? 0) > 0 }">{{ !eventsReady ? busy || loading ? '结果读取中' : '结果未完整' : triggerCounts.get(rule.snapshotRuleId) ? `已触发 · ${triggerCounts.get(rule.snapshotRuleId)} 次` : '未触发' }}</td>
            </tr></tbody>
          </table>
        </div>
        <p v-else class="rule-display-hint">本次实验没有规则快照。</p>
        <details class="snapshot-metadata"><summary>快照来源与详情</summary><p v-for="rule in run.ruleSnapshots" :key="rule.snapshotRuleId">{{ rule.config.name }} · 真实规则 #{{ rule.sourceRuleId }} v{{ rule.sourceVersion }} · 起始 {{ rule.initialState }} · {{ rule.config.triggerMode === 'ONCE' ? '一次性' : '重复' }}<template v-if="rule.config.rearmPrice != null"> · 再激活 {{ rule.config.rearmPrice }}</template></p></details>
      </section>
      <section class="panel simulation-events-panel"><div class="rule-section-head"><h2>本实验事件 <small>（{{ events.length }}）</small></h2></div>
        <p v-if="!events.length" class="rule-display-hint">尚无触发或重新激活事件。</p>
        <div v-else ref="eventTable" class="event-table-scroll" role="region" aria-label="本实验事件表" tabindex="0"><table class="event-table"><thead><tr><th scope="col">时间（纽约）</th><th scope="col">规则</th><th scope="col">方向</th><th scope="col">价格</th><th scope="col">模拟点</th></tr></thead><tbody>
          <tr v-for="event in visibleEvents" :key="event.id" :data-event-id="event.id" :class="{ 'is-selected': selectedEvents.has(event.id) }" :aria-selected="selectedEvents.has(event.id)" tabindex="0" @click="locateEvent(event)" @keydown.enter.prevent="locateEvent(event)">
            <td><time :datetime="event.eventTime">{{ compactNewYorkTime(event.eventTime).replace(' ET', '') }}</time></td><td class="event-rule-name">{{ event.ruleSnapshot.name }}</td><td :class="event.eventType === 'REARMED' ? 'event-rearmed' : event.ruleSnapshot.direction === 'ABOVE' ? 'positive' : 'negative'">{{ event.ruleSnapshot.direction === 'ABOVE' ? '▲ 上破' : '▼ 下破' }}<small v-if="event.eventType === 'REARMED'">重新激活</small></td><td class="event-price">{{ event.price }}</td><td>#{{ event.pointSeq }}</td>
          </tr>
        </tbody></table></div>
        <p class="event-table-hint">点击事件定位图表 · 点击图中触发点高亮事件</p>
        <button v-if="visibleEventCount < events.length" class="text-button event-more" :disabled="busy" @click="more('EVENTS')">加载更多模拟事件</button>
      </section>
    </div>
  </div>
</template>

<style scoped>
.page-wrap { padding-top: 26px; }
.page-head { margin-bottom: 18px; align-items: center; } .page-head h1 { font-size: 32px; }
.page-head .muted { font-size: 12px; }
.simulation-grid { gap: 14px; align-items: stretch; }
.simulator-card, .result-card { padding: 16px 18px; min-width: 0; }
.result-card { min-height: 0; display: block; }
.simulator-card .panel-head, .result-card .panel-head { padding: 0 0 12px; }
.simulator-card h2, .result-card h2 { font-size: 17px; margin: 0; }
.experiment-controls { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.experiment-controls label, .advanced-options label { display: grid; gap: 5px; min-width: 0; color: var(--muted); font-size: 11px; }
.experiment-controls select, .experiment-controls input, .advanced-options input { width: 100%; min-width: 0; max-width: 100%; border: 1px solid var(--line); border-radius: 7px; padding: 8px; background: white; color: var(--ink); font-size: 12px; }
.origin-picker { grid-column: 1 / -1; }
.experiment-actions { display: flex; align-items: start; justify-content: space-between; gap: 12px; margin-top: 12px; }
.experiment-actions .button { padding: 9px 12px; font-size: 12px; flex: none; }
.advanced-options { font-size: 11px; color: var(--muted); flex: 1; min-width: 0; line-height: 1.7; }
.advanced-options label { margin: 10px 0; } .advanced-options p { margin: 8px 0; }
.saved-symbol { display: block; font-size: 13px; margin-bottom: 9px; }
.saved-summary { display: grid; grid-template-columns: 1fr 1fr .7fr; gap: 10px; margin: 0; }
.saved-summary > div + div { padding-left: 10px; border-left: 1px solid var(--line); }
.saved-summary dt { font-size: 10px; color: var(--muted); margin-bottom: 5px; }
.saved-summary dd { font-size: 11px; margin: 0; line-height: 1.6; font-variant-numeric: tabular-nums; }
.saved-summary strong { display: block; font-size: 13px; }
.experiment-totals { display: flex; gap: 14px; flex-wrap: wrap; font-size: 11px; color: var(--muted); margin: 12px 0; }
.experiment-totals b { color: var(--ink); margin-left: 4px; }
.result-card > .button { width: 100%; padding: 9px 12px; font-size: 12px; }
.playback-controls { display: flex; flex-wrap: wrap; gap: 8px; align-items: end; }
.playback-controls .button { padding: 9px 12px; font-size: 12px; flex: 1; white-space: nowrap; }
.playback-controls label { display: grid; gap: 3px; font-size: 10px; color: var(--muted); }
.playback-controls select { max-width: 100%; border: 1px solid var(--line); padding: 8px 6px; border-radius: 7px; background: white; color: var(--ink); font-size: 11px; }
.playback-status { font-size: 10px; color: var(--muted); margin: 8px 0 0; line-height: 1.6; }
.experiment-details, .compact-message { font-size: 11px; color: var(--muted); margin: 9px 0 0; line-height: 1.7; }
.simulation-chart-panel { margin-top: 14px; padding: 0; }
.simulation-chart-panel > .compact-message { padding: 0 20px 10px; }
.simulation-legend, .chart-prompt { margin: 8px 0 0; font-size: 11px; color: var(--muted); }
.real-key { color: #537dad; } .simulation-key { color: #a46c32; margin-left: 12px; }
.simulation-bottom-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); align-items: start; gap: 14px; margin-top: 14px; }
.simulation-bottom-grid > section { min-width: 0; }
.simulation-bottom-grid .rule-section-head { padding: 14px 16px 10px; border: 0; }
.simulation-bottom-grid h2 { font-size: 17px; margin: 0; }
.simulation-rule-section .rule-table { min-width: 510px; }
.simulation-rule-section .rule-table-scroll { max-height: 320px; overflow: auto; }
.simulation-rule-section .rule-table th { position: sticky; top: 0; z-index: 1; background: var(--paper); }
.snapshot-metadata { padding: 0 16px 12px; font-size: 11px; color: var(--muted); line-height: 1.7; }
.snapshot-metadata[open] { max-height: 240px; overflow: auto; }
.snapshot-metadata p { margin: 8px 0; }
.event-table-scroll { max-height: 280px; overflow: auto; margin: 0 12px; position: relative; }
.event-table { width: 100%; min-width: 480px; border-collapse: collapse; text-align: left; font-size: 11px; font-variant-numeric: tabular-nums; }
.event-table th { position: sticky; top: 0; z-index: 1; padding: 10px 6px; background: var(--paper); color: var(--muted); font-weight: 500; border-bottom: 1px solid var(--line); white-space: nowrap; }
.event-table td { padding: 9px 6px; border-bottom: 1px solid var(--line); }
.event-table tbody tr { cursor: pointer; } .event-table tbody tr:hover { background: #f0f6ef; }
.event-table tbody tr.is-selected { background: #e7f6ee; box-shadow: inset 3px 0 #45a67f; }
.event-table tbody tr:focus-visible { outline: 2px solid #55a180; outline-offset: -2px; }
.event-table time, .event-price { white-space: nowrap; } .event-rule-name { min-width: 90px; overflow-wrap: anywhere; }
.event-table td small { display: block; color: var(--muted); font-size: 10px; margin-top: 3px; }
.event-table-hint { margin: 10px 16px; color: var(--muted); font-size: 10px; } .event-more { margin: 0 16px 12px; font-size: 11px; }
.event-rearmed { color: #537dad; } .positive { color: #20815d; } .negative { color: #c45a53; }
@media (max-width: 1250px) { .simulation-bottom-grid { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 660px) { .experiment-controls { grid-template-columns: repeat(2, minmax(0, 1fr)); } .saved-summary { grid-template-columns: 1fr 1fr; } }
</style>
