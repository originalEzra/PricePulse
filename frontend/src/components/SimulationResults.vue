<script setup lang="ts">
import { computed } from 'vue'
import type { AlertRule, SimulationResult, Stock } from '../api/types'
import { formatPrice } from '../utils/format'
import { ruleStatusLabel, type ChartAnnotation, type ChartThreshold } from '../utils/chart'
import PriceSparkline from './PriceSparkline.vue'

const props = defineProps<{ result: SimulationResult; stock: Stock; rule: AlertRule | null }>()
const points = computed(() => props.result.results.filter(point => point.outcome === 'PROCESSED'))
const observations = computed(() => points.value.flatMap(point => point.observation ? [point.observation] : []))
const reference = computed(() => observations.value[0] ?? props.rule)
const thresholds = computed<ChartThreshold[]>(() => reference.value ? [
  { value: reference.value.thresholdPrice, label: reference.value.direction === 'ABOVE' ? '上破提醒' : '下破提醒', kind: reference.value.direction },
  ...(reference.value.rearmPrice == null ? [] : [{ value: reference.value.rearmPrice, label: '重新激活', kind: 'REARMED' as const }]),
] : [])
const transitions = computed(() => points.value.flatMap((point, index) => {
  const observation = point.observation
  if (!observation || observation.event === 'NONE') return []
  const rearmed = observation.event === 'REARMED'
  const label = rearmed ? '再激活' : observation.direction === 'ABOVE' ? '上破' : '下破'
  const price = rearmed ? observation.rearmPrice! : observation.thresholdPrice
  return [{ index, point, observation, label, detail: `${label} ${formatPrice(price)} · ${ruleStatusLabel[observation.beforeStatus]} → ${ruleStatusLabel[observation.afterStatus]}` }]
}))
const annotations = computed<ChartAnnotation[]>(() => transitions.value.map(item => ({
  index: item.index, label: item.label, detail: item.detail,
  kind: item.observation.event === 'REARMED' ? 'REARMED' : item.observation.direction,
})))
const triggered = computed(() => transitions.value.filter(item => item.observation.event === 'TRIGGERED').length)
const rearmed = computed(() => transitions.value.filter(item => item.observation.event === 'REARMED').length)
const configurations = computed(() => new Set(observations.value.map(item => `${item.direction}:${item.thresholdPrice}:${item.rearmPrice}`)).size)
</script>

<template>
  <div class="experiment-result">
    <div class="experiment-stock"><strong>{{ stock.symbol }} · {{ stock.name }}</strong><span>{{ result.processed }} / {{ result.requested }} 个事件有效</span></div>
    <div v-if="rule" class="observed-rule"><strong>{{ rule.name }}</strong><span>{{ triggered }} 次触发 · {{ rearmed }} 次重新激活<span v-if="observations.at(-1)"> · 最终{{ ruleStatusLabel[observations.at(-1)!.afterStatus] }}</span></span></div>
    <PriceSparkline :values="points.map(point => point.price)" :timestamps="points.map(point => point.eventTime)" :previous-values="points.map(point => point.previousPrice)" :currency="stock.currency" :annotations="annotations" :thresholds="thresholds" :point-states="points.map(point => point.observation?.afterStatus ?? null)" />
    <p v-if="rule && !observations.length && points.length" class="result-warning">后端未返回规则观察数据。请重新启动后端，并确认规则仍属于该股票。</p>
    <p v-if="configurations > 1" class="result-warning">规则在运行中发生变更，虚线展示初始设置；各触发点使用当时的实际阈值。</p>
    <p v-if="result.duplicates || result.discarded" class="result-footnote">{{ result.duplicates }} 个重复、{{ result.discarded }} 个丢弃事件未作为新的行情点或状态转换。</p>
    <p v-if="!rule" class="result-footnote">本次仅观察行情；在左侧关联一条提醒，可查看触发、冷却与重新激活。</p>
    <section v-if="rule" class="transition-history">
      <h3>规则运行轨迹 <span>{{ transitions.length }} 次状态转换</span></h3>
      <ol v-if="transitions.length">
        <li v-for="item in transitions.slice(0, 12)" :key="item.point.eventId" :class="{ rearmed: item.observation.event === 'REARMED' }"><span class="transition-step">第 {{ item.index + 1 }} 点</span><div><strong>{{ item.detail }}</strong><small>{{ item.point.eventTime.replace('T', ' ') }} · 行情价 {{ formatPrice(item.point.price) }}<template v-if="item.observation.triggerId"> · 触发 #{{ item.observation.triggerId }}</template></small></div></li>
      </ol>
      <p v-else class="no-transitions">本次没有发生状态转换，图中不会添加虚构的触发点。可调整场景，或检查规则是否已暂停／已完成。</p>
      <details v-if="transitions.length > 12"><summary>查看其余 {{ transitions.length - 12 }} 次转换</summary><ol><li v-for="item in transitions.slice(12)" :key="item.point.eventId"><span class="transition-step">第 {{ item.index + 1 }} 点</span><div><strong>{{ item.detail }}</strong><small>{{ item.point.eventTime.replace('T', ' ') }} · {{ formatPrice(item.point.price) }}</small></div></li></ol></details>
    </section>
  </div>
</template>

<style scoped>
.experiment-stock { display: grid; gap: 5px; padding: 16px 0; }
.experiment-stock strong { font-size: 13px; }.experiment-stock span { color: var(--muted); font-size: 11px; }
.observed-rule { display: grid; gap: 6px; padding: 12px 14px; border-radius: 10px; background: #edf5ee; font-size: 12px; }
.observed-rule span { color: #5b7564; font-size: 11px; }
.experiment-result :deep(.price-chart-wrap) { padding: 18px 0 8px; }
.transition-history { margin-top: 15px; border-top: 1px solid var(--line); padding-top: 15px; }
.transition-history h3 { margin: 0 0 15px; font-size: 13px; }.transition-history h3 span { margin-left: 8px; font-weight: 400; font-size: 10px; color: var(--muted); }
.transition-history ol { margin: 0; padding: 0; list-style: none; }
.transition-history li { display: grid; grid-template-columns: 54px 1fr; gap: 9px; padding: 9px 0; border-bottom: 1px solid #ebeee6; }
.transition-step { align-self: start; border-radius: 5px; background: #e7f1e9; color: #2a7d59; padding: 3px 4px; text-align: center; font-size: 10px; }
.rearmed .transition-step { background: #e8eef7; color: #537dad; }
.transition-history li div { display: grid; gap: 5px; }.transition-history strong { font-size: 11px; line-height: 1.6; }.transition-history small { font-size: 9px; color: var(--muted); overflow-wrap: anywhere; }
.no-transitions, .result-footnote, .result-warning { color: var(--muted); font-size: 11px; line-height: 1.8; }
.result-warning { color: #9b6e2f; }.transition-history summary { margin-top: 10px; font-size: 11px; cursor: pointer; }
</style>
