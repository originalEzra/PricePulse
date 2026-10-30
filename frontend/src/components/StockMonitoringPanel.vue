<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { monitoringApi } from '../api'
import { errorMessage } from '../api/client'
import type { MonitoringPage, AssetSearchResult, CancellationPreview, MonitoredStock } from '../api/types'
import { createAssetSearch, subscriptionLabels, subscriptionReasons } from '../utils/monitoring'
import { formatPrice } from '../utils/format'
import { compactNewYorkTime } from '../utils/chartInspection'

defineProps<{ page: MonitoringPage; selectedId: number | null }>()
const emit = defineEmits<{ select: [id: number]; changed: [] }>()
const query = ref(''), results = ref<AssetSearchResult | null>(null), searching = ref(false), error = ref(''), busy = ref(false)
const preview = ref<CancellationPreview | null>(null), confirmed = ref(false)
let disposed = false
const search = createAssetSearch(monitoringApi.search, (value, cause, loading) => {
  results.value = value; searching.value = loading; error.value = cause ? errorMessage(cause) : ''
})
watch(query, search.query)
onBeforeUnmount(() => { disposed = true; search.dispose() })
async function add(assetId: string) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try {
    const value = await monitoringApi.add(assetId)
    if (disposed) return
    if (value.state === 'FAILED') error.value = subscriptionReasons[value.errorCode ?? ''] ?? '请求接入失败，请稍后重试'
    else query.value = ''
    emit('changed')
  } catch (cause) { if (!disposed) error.value = errorMessage(cause) }
  finally { busy.value = false }
}
async function beginCancel(id: number) {
  if (busy.value) return
  busy.value = true; error.value = ''; confirmed.value = false
  try { const value = await monitoringApi.preview(id); if (!disposed) preview.value = value }
  catch (cause) { if (!disposed) error.value = errorMessage(cause) }
  finally { busy.value = false }
}
async function cancel() {
  const request = preview.value
  if (!request || !confirmed.value || busy.value) return
  busy.value = true; error.value = ''
  try { await monitoringApi.cancel(request); if (!disposed) { preview.value = null; emit('changed') } }
  catch (cause) { if (!disposed) { preview.value = null; error.value = errorMessage(cause) } }
  finally { busy.value = false }
}
async function retry(row: MonitoredStock) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { await monitoringApi.retry(row.stock.id, row.version); if (!disposed) emit('changed') }
  catch (cause) { if (!disposed) error.value = errorMessage(cause) }
  finally { busy.value = false }
}
</script>

<template>
  <section class="panel monitor-panel">
    <div class="panel-head"><div><p class="eyebrow">MONITORING</p><h2>股票监控</h2></div><small>{{ page.capacity.reserved }} / {{ page.capacity.limit }} 个全局名额</small></div>
    <div class="asset-search"><label for="asset-query">搜索并请求监控</label><input id="asset-query" v-model="query" class="compact-input" placeholder="股票代码或公司名称" maxlength="64" autocomplete="off" /></div>
    <p v-if="error" class="message error" role="alert">{{ error }}</p>
    <p v-if="searching" class="message" role="status">正在搜索资产目录…</p>
    <p v-if="results?.stale" class="message" role="status">资产目录暂未更新，结果可能过期；添加时会重新校验。</p>
    <div v-if="results" class="asset-results" aria-label="股票搜索结果">
      <p v-if="!results.assets.length && !results.stale" class="message">没有找到匹配的股票</p>
      <div v-for="item in results.assets" :key="item.asset.id" class="asset-row">
        <div><strong>{{ item.asset.symbol }}</strong><span>{{ item.asset.name }}</span><small>{{ item.asset.assetClass }} · {{ item.asset.exchange }}{{ item.asset.status !== 'active' ? ' · 已停用' : '' }}</small></div>
        <button class="text-button" :disabled="busy || item.subscribed || !item.requestSupported || item.asset.status !== 'active'" @click="add(item.asset.id)">{{ item.subscribed ? '已请求' : !item.requestSupported ? '暂不可接入' : item.asset.status !== 'active' ? '已停用' : '请求监控' }}</button>
      </div>
      <p v-if="results.assets.some(a => !a.requestSupported)" class="message">当前服务仅接入已有股票；其余搜索结果尚不能申请实时监控。</p>
    </div>
    <div class="monitor-list" role="region" aria-label="个人股票监控列表" tabindex="0">
      <div v-for="row in page.subscriptions" :key="row.stock.id" class="monitor-row" :class="{ selected: row.stock.id === selectedId }">
        <button class="stock-pick" @click="emit('select', row.stock.id)">
          <span class="ticker-avatar">{{ row.stock.symbol.slice(0, 2) }}</span>
          <span class="stock-copy"><strong>{{ row.stock.symbol }}</strong><small>{{ row.stock.name }}</small><small class="monitor-state" :class="row.state.toLowerCase()">{{ subscriptionLabels[row.state] }}</small></span>
          <span class="price" :title="compactNewYorkTime(row.stock.latestQuoteAt ?? '')"><strong>{{ formatPrice(row.stock.latestPrice == null ? null : Number(row.stock.latestPrice)) }}</strong><small>{{ row.stock.currency }}</small></span>
        </button>
        <div class="row-management"><small v-if="row.state !== 'CONFIRMED'">{{ subscriptionReasons[row.errorCode ?? ''] ?? '等待接入确认' }}</small><span><button v-if="row.state === 'FAILED'" class="text-button" :disabled="busy" @click="retry(row)">重试</button><button class="text-button danger" :disabled="busy" :aria-label="`取消 ${row.stock.symbol} 监控`" @click="beginCancel(row.stock.id)">取消监控</button></span></div>
      </div>
      <p v-if="!page.subscriptions.length" class="message">监控列表为空，搜索股票后请求监控。</p>
    </div>
    <div v-if="preview" class="confirmation-backdrop"><section class="confirmation panel" role="dialog" aria-modal="true" aria-labelledby="cancel-title">
      <h2 id="cancel-title">取消 {{ preview.symbol }} 监控？</h2>
      <p>该股票的 {{ preview.ruleCount }} 条提醒规则将同时停用并逻辑删除，包括已暂停或已完成规则。历史触发与通知会保留。</p>
      <label class="confirm-check"><input v-model="confirmed" type="checkbox" :disabled="busy" />我确认取消监控，并停用这些规则</label>
      <div class="confirmation-actions"><button class="button secondary" :disabled="busy" @click="preview = null">返回</button><button class="button primary" :disabled="busy || !confirmed" @click="cancel">{{ busy ? '处理中…' : '确认取消监控' }}</button></div>
    </section></div>
  </section>
</template>

<style scoped>
.monitor-panel { min-width: 0; overflow: hidden; }.panel-head { align-items: center; gap: 10px; }.panel-head small { color: var(--muted); font-size: 11px; }
.asset-search { padding: 0 18px 12px; display: grid; gap: 6px; }.asset-search label { color: var(--muted); font-size: 12px; }.compact-input { width: 100%; min-width: 0; }
.message { padding: 8px 18px; margin: 0; font-size: 12px; line-height: 1.6; color: var(--muted); }.error { color: var(--danger, #b74343); }
.asset-results { max-height: 280px; overflow-y: auto; border-bottom: 1px solid var(--line); }.asset-row { display: flex; align-items: center; gap: 10px; padding: 12px 18px; }.asset-row > div { display: grid; gap: 3px; min-width: 0; flex: 1; }.asset-row span { font-size: 12px; overflow-wrap: anywhere; }.asset-row small { font-size: 10px; color: var(--muted); }.asset-row button { flex-shrink: 0; }
.monitor-list { max-height: 640px; overflow-y: auto; }.monitor-row { border-top: 1px solid var(--line); padding-bottom: 8px; }.monitor-row.selected { background: var(--mint-soft, #edf7f1); }.stock-pick { border: 0; background: transparent; color: inherit; width: 100%; display: flex; gap: 10px; padding: 13px 18px 4px; align-items: center; text-align: left; cursor: pointer; }.stock-copy { display: grid; min-width: 0; flex: 1; gap: 2px; }.stock-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--muted); font-size: 11px; }.stock-copy .confirmed { color: #278868; }.stock-copy .failed { color: #b74343; }.price { display: grid; justify-items: end; font-size: 16px; }.price small { color: var(--muted); font-size: 10px; }.row-management { display: flex; justify-content: space-between; gap: 8px; padding: 0 18px 0 66px; }.row-management > small { color: var(--muted); font-size: 10px; }.row-management > span { margin-left: auto; display: flex; gap: 10px; }.row-management button { font-size: 11px; }
.confirmation-backdrop { position: fixed; inset: 0; background: #07172377; z-index: 100; display: grid; place-items: center; padding: 18px; }.confirmation { width: min(480px, 100%); padding: 24px; }.confirmation p { line-height: 1.8; font-size: 14px; }.confirm-check { display: flex; align-items: center; gap: 8px; font-size: 13px; }.confirmation-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 20px; }
@media(max-width: 600px) { .monitor-list { max-height: 360px; }.confirmation { padding: 18px; }.confirmation-actions { flex-wrap: wrap; } }
</style>
