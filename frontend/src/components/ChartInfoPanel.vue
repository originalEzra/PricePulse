<script setup lang="ts">
import { compactNewYorkTime } from '../utils/chartInspection'
import { formatPrice } from '../utils/format'

defineProps<{
  time: string; price: number | null; currency: string; kind: string
  change: { value: number; percent: number } | null
}>()
</script>

<template>
  <aside class="chart-info-panel" role="status" aria-label="固定行情信息面板" aria-live="polite">
    <p class="info-kind">{{ kind }}</p>
    <dl>
      <div><dt>时间</dt><dd><time :datetime="time">{{ compactNewYorkTime(time) }}</time></dd></div>
      <div><dt>价格</dt><dd class="info-price">{{ formatPrice(price) }} <small>{{ currency }}</small></dd></div>
      <div><dt>变化</dt><dd :class="change ? change.value >= 0 ? 'info-up' : 'info-down' : ''">{{ change ? `${change.value >= 0 ? '+' : ''}${formatPrice(change.value)}（${change.percent >= 0 ? '+' : ''}${change.percent.toFixed(2)}%）` : '—' }}</dd></div>
    </dl>
  </aside>
</template>

<style scoped>
.chart-info-panel { width: 100%; max-width: 280px; padding: 10px 12px; border: 1px solid #e0e6df; border-radius: 10px; background: #f9fbf7; font-size: 12px; }
.info-kind { margin: 0 0 6px; font-size: 11px; color: #63756a; line-height: 16px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
dl { display: grid; gap: 3px; margin: 0; } dl > div { display: grid; grid-template-columns: 34px minmax(0, 1fr); gap: 8px; align-items: baseline; line-height: 20px; }
dt { color: var(--muted); } dd { margin: 0; text-align: right; white-space: nowrap; font-variant-numeric: tabular-nums; }
.info-price { font-size: 17px; font-weight: 700; } small { font-size: 10px; font-weight: 400; color: var(--muted); }
.info-up { color: #20815d; } .info-down { color: #c45a53; }
</style>
