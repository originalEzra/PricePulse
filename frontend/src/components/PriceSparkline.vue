<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import { formatPrice } from '../utils/format'
import type { RuleStatus } from '../api/types'
import { ruleStatusLabel, type ChartAnnotation, type ChartThreshold, type TimedChartAnnotation } from '../utils/chart'
import { createTimeAxis, formatNewYorkTimestamp, nearestTimeIndex, segments, type TradingSession } from '../utils/timeAxis'
import { indexPointEvents, instantKey, previousValidPrice, priceDifference } from '../utils/chartInspection'
import ChartInfoPanel from './ChartInfoPanel.vue'

const emit = defineEmits<{ 'event-select': [ids: number[]] }>()

const props = withDefaults(defineProps<{
  values: number[]; timestamps?: string[]; currency?: string
  annotations?: ChartAnnotation[]; thresholds?: ChartThreshold[]
  pointStates?: Array<RuleStatus | null>; previousValues?: Array<number | null>
  timeRange?: { from: string; to: string }; sessionIds?: string[]; breakTimes?: string[]
  temporalAnnotations?: TimedChartAnnotation[]; pointDetails?: string[]; emptyLabel?: string
  pointKinds?: string[]; gapRanges?: Array<{ from: string; to: string }>
  originTime?: string; eventScope?: 'REAL' | 'SIMULATION'
  tradingSessions?: TradingSession[]; compressNonTrading?: boolean
  exactPrices?: string[]; pointSeqs?: Array<number | null>; originPrice?: string
  scopeKey?: string
}>(), {
  timestamps: () => [],
  currency: '',
  annotations: () => [], thresholds: () => [], pointStates: () => [], previousValues: () => [],
  sessionIds: () => [], breakTimes: () => [], temporalAnnotations: () => [], pointDetails: () => [], emptyLabel: '尚无可用行情',
  pointKinds: () => [], gapRanges: () => [],
  eventScope: 'REAL',
  tradingSessions: () => [], compressNonTrading: false,
  exactPrices: () => [], pointSeqs: () => [],
})
const container = ref<HTMLDivElement | null>(null)
const width = ref(640)
const height = 310
const top = 38
const bottom = height - 52
const right = 18
const activeIndex = ref<number | null>(null)
const selectedMarkers = ref<TimedChartAnnotation[]>([])
const hoveredEvents = ref<TimedChartAnnotation[]>([])
const inspection = ref<{ index: number } | { events: TimedChartAnnotation[] } | null>(null)
const pinnedEvent = ref<TimedChartAnnotation | null>(null)
const pinnedPoint = ref<number | null>(null)
const gradientId = `price-chart-${useId()}`
let resizeObserver: ResizeObserver | null = null

const domain = computed(() => {
  const values = [...props.values, ...props.thresholds.map(line => line.value), ...props.temporalAnnotations.map(item => item.price)].filter(Number.isFinite)
  const min = Math.min(...values)
  const max = Math.max(...values)
  if (!Number.isFinite(min) || !Number.isFinite(max)) return { min: 0, max: 1, step: 0.25 }
  const span = Math.max((max - min) * 1.2, 0.04)
  const roughStep = span / 4
  const magnitude = 10 ** Math.floor(Math.log10(roughStep))
  const multiplier = [1, 2, 2.5, 5, 10].find(value => value * magnitude >= roughStep) ?? 10
  const step = Math.max(0.01, multiplier * magnitude)
  const padding = (span - (max - min)) / 2
  return {
    min: Math.max(0, Math.floor((min - padding) / step) * step),
    max: Math.ceil((max + padding) / step) * step,
    step,
  }
})
const yTicks = computed(() => {
  const { min, max, step } = domain.value
  const count = Math.round((max - min) / step)
  return Array.from({ length: count + 1 }, (_, index) => min + index * step)
})
const left = computed(() => Math.max(66, ...yTicks.value.map(value => formatPrice(value).length * 6.5 + 16)))
const plotWidth = computed(() => Math.max(1, width.value - left.value - right))
const y = (value: number) => bottom - ((value - domain.value.min) / (domain.value.max - domain.value.min)) * (bottom - top)
const times = computed(() => props.timestamps.map(value => new Date(value).getTime()))
const timeDomain = computed(() => {
  if (props.timeRange) return { from: Date.parse(props.timeRange.from), to: Date.parse(props.timeRange.to) }
  if (times.value.length === props.values.length && times.value.length && times.value.every(Number.isFinite))
    return { from: times.value[0]!, to: times.value.at(-1)! }
  return null
})
const timeAxis = computed(() => timeDomain.value ? createTimeAxis(timeDomain.value.from, timeDomain.value.to,
  props.tradingSessions, props.compressNonTrading, plotWidth.value) : null)
const timeX = (time: number) => left.value + timeAxis.value!.position(time) * plotWidth.value
const mappedTimes = computed(() => times.value.map(time => timeAxis.value?.position(time) ?? 0))
const axisBreaks = computed(() => timeAxis.value?.breaks.map(gap => ({ ...gap,
  x: left.value + gap.left * plotWidth.value, width: (gap.right - gap.left) * plotWidth.value,
  center: left.value + (gap.left + gap.right) / 2 * plotWidth.value,
  label: `省略休市：${formatNewYorkTimestamp(new Date(gap.from).toISOString())} 至 ${formatNewYorkTimestamp(new Date(gap.to).toISOString())}`,
})) ?? [])
const hasChart = computed(() => props.values.length > 0 || (timeDomain.value != null && props.temporalAnnotations.length > 0))
const dataPoints = computed(() => props.values.map((value, index) => ({
  value,
  index,
  x: timeDomain.value ? timeX(times.value[index]!) : left.value + (props.values.length === 1 ? 0.5 : index / (props.values.length - 1)) * plotWidth.value,
  y: y(value),
})))
const breaks = (a: { index: number }, b: { index: number }) =>
  timeAxis.value?.breaks.some(gap => times.value[a.index]! <= gap.from && times.value[b.index]! >= gap.to)
  || props.breakTimes.some(raw => { const at = Date.parse(raw); return at > times.value[a.index]! && at <= times.value[b.index]! })
  || (props.pointKinds[a.index] === 'BAR_CLOSE' && props.pointKinds[b.index] === 'BAR_CLOSE'
    && props.gapRanges.some(gap => Date.parse(gap.from) < times.value[b.index]! && Date.parse(gap.to) > times.value[a.index]!))
const lineSegments = computed(() => segments(dataPoints.value,
  p => `${props.sessionIds[p.index] ?? 'one'}:${props.pointKinds[p.index] === 'SIMULATION' ? 'sim' : 'real'}`, breaks)
  .map(raw => {
    const simulation = props.pointKinds[raw[0]!.index] === 'SIMULATION'
    const previous = dataPoints.value[raw[0]!.index - 1]
    const items = simulation && previous && props.pointKinds[previous.index] !== 'SIMULATION'
      && (props.sessionIds[previous.index] ?? 'one') === (props.sessionIds[raw[0]!.index] ?? 'one') && !breaks(previous, raw[0]!)
      ? [previous, ...raw] : raw
    return { points: items.map(p => `${p.x},${p.y}`).join(' '), simulation,
      area: `${items.map(p => `${p.x},${p.y}`).join(' ')} ${items.at(-1)!.x},${bottom} ${items[0]!.x},${bottom}`, items }
  }))
const sessionJumps = computed(() => dataPoints.value.flatMap((point, index) => {
  const previous = dataPoints.value[index - 1]
  return previous && timeAxis.value?.breaks.some(gap => times.value[index - 1]! <= gap.from && times.value[index]! >= gap.to)
    ? [{ from: previous, to: point }] : []
}))
const originX = computed(() => {
  const time = props.originTime ? Date.parse(props.originTime) : NaN
  return timeDomain.value && time >= timeDomain.value.from && time <= timeDomain.value.to ? timeX(time) : null
})
function axisTime(time: number, full = false) {
  return new Date(time).toLocaleString('zh-CN', { timeZone: 'America/New_York',
    ...(full || (timeDomain.value && timeDomain.value.to - timeDomain.value.from > 86400000) ? { month: 'numeric', day: 'numeric' } : {}),
    hour: '2-digit', minute: '2-digit', hour12: false })
}
const timeTicks = computed(() => {
  if (!timeDomain.value) return []
  const count = width.value < 520 ? 3 : 5
  return timeAxis.value!.ticks(count).map(({ at, position }, index, ticks) => ({ at,
    x: left.value + position * plotWidth.value, label: axisTime(at),
    anchor: index === 0 ? 'start' : index === ticks.length - 1 ? 'end' : 'middle' }))
})
const temporalMarkers = computed(() => {
  const groups: Array<{ x: number; y: number; events: TimedChartAnnotation[] }> = []
  if (!timeDomain.value) return groups
  for (const item of props.temporalAnnotations) {
    const at = Date.parse(item.eventTime)
    if (at < timeDomain.value.from || at > timeDomain.value.to) continue
    const x = timeX(at), markerY = y(item.price)
    const existing = groups.find(group => Math.abs(group.x - x) < 9 && Math.abs(group.y - markerY) < 9)
    if (existing) existing.events.push(item)
    else groups.push({ x, y: markerY, events: [item] })
  }
  return groups
})

function timeLabel(index: number): string | null {
  const raw = props.timestamps[index]
  if (!raw) return null
  const date = new Date(raw)
  if (Number.isNaN(date.getTime())) return null
  return date.toLocaleTimeString('zh-CN', { timeZone: 'America/New_York', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false })
}

const tickIndexes = computed(() => {
  const count = Math.min(width.value < 520 ? 3 : 5, props.values.length)
  if (count < 2) return props.values.length ? [0] : []
  return [...new Set(Array.from({ length: count }, (_, index) => Math.round(index * (props.values.length - 1) / (count - 1))))]
})
const sequenceAxis = computed(() => {
  const labels = tickIndexes.value.map(timeLabel)
  return labels.some(label => !label) || new Set(labels).size !== labels.length
})
const xTitle = computed(() => timeDomain.value ? `${axisBreaks.value.length ? '交易时间（休市已压缩）' : '纽约时间'} · America/New_York` : sequenceAxis.value ? '行情点（按时间顺序）' : '时间')
const activePoint = computed(() => activeIndex.value == null ? null : dataPoints.value[activeIndex.value] ?? null)
const eventIndex = computed(() => indexPointEvents(props.temporalAnnotations))
const inspectedPoint = computed(() => inspection.value && 'events' in inspection.value ? null
  : dataPoints.value[inspection.value && 'index' in inspection.value ? inspection.value.index : props.values.length - 1] ?? null)
const inspectedEvents = computed(() => inspection.value && 'events' in inspection.value ? inspection.value.events
  : eventIndex.value.get(instantKey(props.timestamps[inspectedPoint.value?.index ?? -1] ?? '') ?? '') ?? [])
const inspectedEvent = computed(() => inspection.value && 'events' in inspection.value ? inspection.value.events[0] : null)
const inspectedTime = computed(() => inspectedEvent.value?.eventTime ?? props.timestamps[inspectedPoint.value?.index ?? -1] ?? '')
const inspectedPrice = computed(() => inspectedEvent.value?.price ?? inspectedPoint.value?.value ?? null)
const inspectedRawPrice = computed(() => inspectedEvent.value?.rawPrice ?? props.exactPrices[inspectedPoint.value?.index ?? -1]
  ?? inspectedPrice.value?.toFixed(4) ?? '—')
const inspectedSequence = computed(() => inspectedEvent.value?.pointSeq ?? props.pointSeqs[inspectedPoint.value?.index ?? -1] ?? null)
const inspectedOrigin = computed(() => !!props.originTime && instantKey(inspectedTime.value) === instantKey(props.originTime))
const inspectedSimulation = computed(() => inspectedEvent.value ? props.eventScope === 'SIMULATION'
  : props.pointKinds[inspectedPoint.value?.index ?? -1] === 'SIMULATION')
const inspectedChange = computed(() => {
  if (inspectedPrice.value == null) return null
  const index = inspectedPoint.value?.index ?? props.timestamps.findIndex(time => (instantKey(time) != null &&
    BigInt(instantKey(time)!) >= BigInt(instantKey(inspectedTime.value) ?? '0')))
  const before = index < 0 ? props.values.length : index
  const previous = inspectedPoint.value ? props.previousValues[before] ?? previousValidPrice(props.values, before) : previousValidPrice(props.values, before)
  return priceDifference(inspectedPrice.value, previous)
})
const inspectedOffset = computed(() => inspectedPrice.value == null ? null : priceDifference(inspectedPrice.value, props.originPrice ? Number(props.originPrice) : null))
const inspectedLegacy = computed(() => props.annotations.filter(item => item.index === inspectedPoint.value?.index))
const cursorPoint = computed(() => {
  const event = hoveredEvents.value[0] ?? pinnedEvent.value
  const point = activePoint.value ?? (pinnedPoint.value == null ? null : dataPoints.value[pinnedPoint.value] ?? null)
  return event && timeDomain.value ? { x: timeX(Date.parse(event.eventTime)), y: y(event.price), value: event.price, time: event.eventTime }
    : point ? { ...point, time: props.timestamps[point.index] } : null
})
function signed(value: number) { return `${value >= 0 ? '+' : ''}${value.toFixed(4)}` }
function selectPoint(index: number | null) {
  pinnedEvent.value = null; pinnedPoint.value = null
  hoveredEvents.value = []; activeIndex.value = index
  if (index != null) inspection.value = { index }
}
function inspectEvents(events: TimedChartAnnotation[], select = false) {
  pinnedPoint.value = null; pinnedEvent.value = select ? events[0] ?? null : null
  activeIndex.value = null; hoveredEvents.value = events; inspection.value = { events }
  if (select) { selectedMarkers.value = events; emit('event-select', events.map(event => event.id)) }
}
function clearCursor() { activeIndex.value = null; hoveredEvents.value = [] }
function leaveChart() {
  clearCursor()
  if (!pinnedEvent.value && pinnedPoint.value == null) inspection.value = null
}
function pinPoint() { pinnedPoint.value = activeIndex.value }
function focusEvent(event: TimedChartAnnotation) {
  inspectEvents([event], true)
  container.value?.scrollIntoView({ block: 'center', behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' })
}
function closeEventDetails() {
  selectedMarkers.value = []; pinnedEvent.value = null; pinnedPoint.value = null; inspection.value = null
  clearCursor(); emit('event-select', [])
}
defineExpose({ focusEvent })
function inspectOrigin() {
  if (!props.originTime) return
  const key = instantKey(props.originTime), index = props.timestamps.findIndex(time => instantKey(time) === key)
  if (index >= 0) selectPoint(index)
}
const markers = computed(() => props.annotations.flatMap(item => {
  const point = dataPoints.value[item.index]
  return point ? [{ ...item, x: point.x, y: point.y }] : []
}))
const markerTags = computed(() => {
  const reserved = props.thresholds.map(line => ({ x: width.value - right - 132, y: y(line.value) - 19, width: 132, height: 17 }))
  const tags: Array<(typeof markers.value)[number] & { tagX: number; tagY: number }> = []
  for (const marker of markers.value) {
    const tagX = Math.max(left.value, Math.min(marker.x - 23, width.value - right - 46))
    const tagY = [marker.y - 30, marker.y + 11, marker.y - 54, marker.y + 35]
      .map(value => Math.max(top - 6, Math.min(value, bottom - 21)))
      .find(value => [...reserved, ...tags.map(tag => ({ x: tag.tagX, y: tag.tagY, width: 46, height: 19 }))]
        .every(box => tagX + 49 <= box.x || tagX >= box.x + box.width + 3 || value + 22 <= box.y || value >= box.y + box.height + 3))
    if (tagY != null) tags.push({ ...marker, tagX, tagY })
  }
  return tags
})
const cooldownBands = computed(() => {
  const bands: Array<{ x: number; width: number }> = []
  let start: number | null = null
  for (const point of dataPoints.value) {
    if (props.pointStates[point.index] === 'COOLDOWN' && start == null) start = point.x
    if (props.pointStates[point.index] !== 'COOLDOWN' && start != null) {
      bands.push({ x: start, width: point.x - start }); start = null
    }
  }
  if (start != null) bands.push({ x: start, width: width.value - right - start })
  return bands
})
const color = (kind: ChartAnnotation['kind']) => kind === 'ABOVE' ? '#278b68' : kind === 'BELOW' ? '#d76358' : '#537dad'

function inspect(event: PointerEvent) {
  const bounds = (event.currentTarget as SVGSVGElement).getBoundingClientRect()
  const x = (event.clientX - bounds.left) * width.value / bounds.width
  const cursorY = (event.clientY - bounds.top) * height / bounds.height
  if (x < left.value || x > width.value - right || cursorY < top || cursorY > bottom) {
    clearCursor()
    return
  }
  const ratio = Math.max(0, Math.min(1, (x - left.value) / plotWidth.value))
  selectPoint(timeDomain.value ? nearestTimeIndex(mappedTimes.value, ratio)
    : props.values.length ? Math.round(ratio * (props.values.length - 1)) : null)
}

function moveSelection(direction: number) {
  if (props.values.length) selectPoint(Math.max(0, Math.min(props.values.length - 1, (activeIndex.value ?? props.values.length - 1) + direction)))
}

watch([() => props.timestamps, () => props.values], ([timestamps, values], [oldTimes, oldValues]) => {
  clearCursor()
  if (inspection.value && 'index' in inspection.value) {
    const oldIndex = inspection.value.index
    const index = timestamps.findIndex((time, i) => time === oldTimes[oldIndex] && values[i] === oldValues[oldIndex])
    inspection.value = index < 0 ? null : { index }
    if (pinnedPoint.value != null) pinnedPoint.value = index < 0 ? null : index
  } else if (inspection.value && 'events' in inspection.value) {
    const current = new Map(props.temporalAnnotations.map(event => [event.id, event]))
    inspection.value = { events: inspection.value.events.map(event => current.get(event.id) ?? event) }
  }
})
watch(() => props.temporalAnnotations, entries => {
  const current = new Map(entries.map(event => [event.id, event]))
  selectedMarkers.value = selectedMarkers.value.map(item => current.get(item.id) ?? item)
})
watch(() => props.scopeKey, () => { clearCursor(); pinnedEvent.value = null; pinnedPoint.value = null; inspection.value = null; selectedMarkers.value = [] })

onMounted(() => {
  if (!container.value) return
  width.value = container.value.clientWidth
  resizeObserver = new ResizeObserver(entries => {
    const entry = entries[0]
    if (entry) width.value = entry.contentRect.width
  })
  resizeObserver.observe(container.value)
})
onBeforeUnmount(() => resizeObserver?.disconnect())

const rising = computed(() => {
  const first = props.values[0]
  const last = props.values.at(-1)
  return first !== undefined && last !== undefined ? last >= first : true
})
</script>

<template>
  <div class="price-chart-wrap">
    <div class="chart-heading-grid" :class="{ 'without-latest': !$slots.latest }">
      <div class="chart-heading-context"><slot name="heading"><h3>{{ eventScope === 'SIMULATION' ? '真实前缀与模拟结果' : '价格走势' }}</h3></slot></div>
      <ChartInfoPanel :time="inspectedTime" :price="inspectedPrice" :currency="currency" :change="inspectedChange"
        :kind="inspectedOrigin ? '模拟起点' : inspectedSimulation ? `模拟行情 · 点 #${inspectedSequence ?? '—'}` : eventScope === 'SIMULATION' ? '真实前缀' : inspectedEvent ? '实际触发事件' : '当前行情'" />
      <div v-if="$slots.latest" class="chart-heading-latest"><slot name="latest" /></div>
    </div>
    <div ref="container" class="price-chart" :class="{ falling: !rising }">
      <div v-if="!hasChart" class="chart-empty">{{ emptyLabel }}</div>
      <svg
        v-else
        :viewBox="`0 0 ${width} ${height}`"
        role="img"
        :data-axis-mode="compressNonTrading ? 'TRADING' : 'ELAPSED'" :data-axis-break-count="axisBreaks.length"
        :aria-label="`近期价格走势，横轴${xTitle}，纵轴价格。可使用左右方向键查看行情点。`"
        tabindex="0"
        @pointermove="inspect"
        @pointerdown="inspect"
        @pointerleave="leaveChart"
        @focus="values.length && selectPoint(activeIndex ?? values.length - 1)"
        @blur="leaveChart"
        @click="pinPoint"
        @keydown.enter.prevent="pinPoint"
        @keydown.left.prevent="moveSelection(-1)"
        @keydown.right.prevent="moveSelection(1)"
        @keydown.home.prevent="values.length && selectPoint(0)"
        @keydown.end.prevent="values.length && selectPoint(values.length - 1)"
      >
      <defs>
        <linearGradient :id="gradientId" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="currentColor" stop-opacity="0.2" />
          <stop offset="100%" stop-color="currentColor" stop-opacity="0" />
        </linearGradient>
      </defs>
      <text :x="left" y="14" class="axis-title">价格{{ currency ? `（${currency}）` : '' }}</text>
      <g v-for="tick in yTicks" :key="tick">
        <line :x1="left" :x2="width - right" :y1="y(tick)" :y2="y(tick)" class="grid-line" />
        <text :x="left - 12" :y="y(tick) + 4" text-anchor="end" class="axis-label">{{ formatPrice(tick) }}</text>
      </g>
      <line :x1="left" :x2="left" :y1="top" :y2="bottom" class="axis-line" />
      <line :x1="left" :x2="width - right" :y1="bottom" :y2="bottom" class="axis-line" />
      <g v-for="gap in axisBreaks" :key="`closure-${gap.from}`" class="axis-break" :data-from="new Date(gap.from).toISOString()" :data-to="new Date(gap.to).toISOString()">
        <title>{{ gap.label }}</title>
        <rect :x="gap.x" :y="top" :width="gap.width" :height="bottom - top" fill="#e9ece5" opacity="0.65" />
        <rect :x="gap.center - 7" :y="bottom - 7" width="14" height="14" fill="#fafbf7" />
        <path :d="`M ${gap.center - 5} ${bottom + 4} l 4 -8 M ${gap.center + 1} ${bottom + 4} l 4 -8`" fill="none" stroke="#68776e" stroke-width="1.6" />
      </g>
      <rect v-for="(band, index) in cooldownBands" :key="`cool-${index}`" :x="band.x" :y="top" :width="band.width" :height="bottom - top" fill="#e8bd5b" opacity="0.12" />
      <polyline v-for="(segment, index) in lineSegments" :key="`area-${index}`" :points="segment.area" :fill="`url(#${gradientId})`" stroke="none" />
      <g v-for="(line, index) in thresholds" :key="`threshold-${index}`" class="threshold-line">
        <line :x1="left" :x2="width - right" :y1="y(line.value)" :y2="y(line.value)" :stroke="line.color ?? color(line.kind)" stroke-dasharray="6 5" stroke-width="1.2" :opacity="line.color ? 0.85 : 0.65" />
        <text :x="width - right - 3" :y="y(line.value) - 6" text-anchor="end" :fill="line.color ?? color(line.kind)">{{ line.label }} {{ formatPrice(line.value) }}</text>
      </g>
      <polyline v-for="(segment, index) in lineSegments" :key="`line-${index}`" :points="segment.points" fill="none"
        :stroke="segment.simulation ? '#a46c32' : originTime ? '#537dad' : 'currentColor'" :stroke-dasharray="segment.simulation ? '6 4' : undefined" stroke-width="2" stroke-linejoin="round" />
      <line v-for="(jump, index) in sessionJumps" :key="`jump-${index}`" class="session-jump" :x1="jump.from.x" :y1="jump.from.y" :x2="jump.to.x" :y2="jump.to.y"
        stroke="#86948c" stroke-width="1.2" stroke-dasharray="2 4"><title>休市跳空：仅连接两端已知价格，期间没有生成行情点</title></line>
      <g v-if="originX != null" class="event-marker" role="button" tabindex="0" aria-label="检查模拟起点" @pointerenter="inspectOrigin" @pointermove.stop="inspectOrigin" @click.stop="inspectOrigin" @focus="inspectOrigin" @keydown.enter.prevent="inspectOrigin" @keydown.space.prevent="inspectOrigin"><line :x1="originX" :x2="originX" :y1="top" :y2="bottom" stroke="#a46c32" stroke-dasharray="3 4" />
        <text :x="Math.max(left, Math.min(originX + 5, width - right - 60))" :y="top - 7" fill="#936129" font-size="10">模拟起点</text></g>
      <g v-for="(group, index) in temporalMarkers" :key="`real-event-${index}`" class="event-marker" role="button" tabindex="0"
        :aria-label="`${group.events.length} 条${eventScope === 'SIMULATION' ? '模拟' : '真实'}触发记录`" @pointerenter="inspectEvents(group.events)" @pointermove.stop="inspectEvents(group.events)" @click.stop="inspectEvents(group.events, true)"
        @focus="inspectEvents(group.events)" @blur="clearCursor" @keydown.enter.prevent="inspectEvents(group.events, true)" @keydown.space.prevent="inspectEvents(group.events, true)">
        <circle :cx="group.x" :cy="group.y" r="10" fill="white" :stroke="color(group.events[0]!.kind)" stroke-width="1.5" />
        <text :x="group.x" :y="group.y + 4" text-anchor="middle" :fill="color(group.events[0]!.kind)">{{ group.events.length > 1 ? group.events.length : group.events[0]!.kind === 'ABOVE' ? '▲' : '▼' }}</text>
      </g>
      <circle v-if="values.length === 1 && dataPoints[0]" :cx="dataPoints[0].x" :cy="dataPoints[0].y" r="4" fill="currentColor" />
      <g v-for="(marker, index) in markers" :key="`marker-${index}`" class="event-marker" :aria-label="marker.detail" @pointermove.stop="selectPoint(marker.index)" @pointerdown.stop="selectPoint(marker.index)">
        <circle :cx="marker.x" :cy="marker.y" r="5" :fill="color(marker.kind)" stroke="white" stroke-width="2" />
      </g>
      <g v-for="(marker, index) in markerTags" :key="`tag-${index}`" class="event-marker" @pointermove.stop="selectPoint(marker.index)" @pointerdown.stop="selectPoint(marker.index)">
        <rect :x="marker.tagX" :y="marker.tagY" width="46" height="19" rx="5" :fill="color(marker.kind)" />
        <text :x="marker.tagX + 23" :y="marker.tagY + 13" text-anchor="middle" fill="white">{{ marker.label }}</text>
      </g>
      <g v-for="tick in timeTicks" :key="`time-${tick.at}`">
        <text :x="tick.x" :y="bottom + 20" :text-anchor="tick.anchor" class="axis-label">{{ tick.label }}</text>
      </g>
      <g v-for="index in timeDomain ? [] : tickIndexes" :key="index">
        <text :x="dataPoints[index]?.x" :y="bottom + 20" :text-anchor="values.length === 1 ? 'middle' : index === 0 ? 'start' : index === values.length - 1 ? 'end' : 'middle'" class="axis-label">{{ sequenceAxis ? `第 ${index + 1} 点` : timeLabel(index) }}</text>
      </g>
      <text :x="left + plotWidth / 2" :y="height - 7" text-anchor="middle" class="axis-title">{{ xTitle }}</text>
      <g v-if="cursorPoint" class="chart-cursor">
        <line :x1="cursorPoint.x" :x2="cursorPoint.x" :y1="top" :y2="bottom" />
        <line :x1="left" :x2="width - right" :y1="cursorPoint.y" :y2="cursorPoint.y" />
        <circle :cx="cursorPoint.x" :cy="cursorPoint.y" r="4.5" fill="white" stroke="currentColor" stroke-width="2" />
        <rect x="0" :y="cursorPoint.y - 10" :width="left - 3" height="20" rx="4" fill="#223b35" />
        <text :x="left - 8" :y="cursorPoint.y + 4" text-anchor="end" fill="white" font-size="10">{{ formatPrice(cursorPoint.value) }}</text>
        <g v-if="timeDomain && cursorPoint.time"><rect :x="Math.max(left, Math.min(cursorPoint.x - 60, width - right - 120))" :y="bottom + 3" width="120" height="21" rx="4" fill="#223b35" />
          <text :x="Math.max(left, Math.min(cursorPoint.x - 60, width - right - 120)) + 60" :y="bottom + 17" text-anchor="middle" fill="white" font-size="10">{{ axisTime(Date.parse(cursorPoint.time), true) }}</text></g>
      </g>
      </svg>
    </div>
    <div v-if="selectedMarkers.length" class="temporal-event-details"><button class="text-button" @click="closeEventDetails">收起事件详情 ×</button>
      <article v-for="item in selectedMarkers" :key="item.id"><strong>{{ item.ruleName ?? item.label }} · {{ item.rawPrice ?? formatPrice(item.price) }}</strong><time>{{ formatNewYorkTimestamp(item.eventTime) }}</time><p>{{ item.kind === 'ABOVE' ? '▲ 上破' : '▼ 下破' }} · 阈值 {{ item.threshold ?? '—' }} · {{ item.result ?? item.label }}</p>
        <p v-if="item.label === '开盘跳空'">{{ item.detail }}</p>
        <details v-if="item.ruleSnapshot"><summary>规则快照详情</summary><dl class="event-snapshot"><div><dt>规则名称</dt><dd>{{ item.ruleSnapshot.name }}</dd></div><div><dt>触发方式</dt><dd>{{ item.ruleSnapshot.triggerMode === 'ONCE' ? '一次性' : '重复' }}</dd></div><div><dt>重新激活价</dt><dd>{{ item.ruleSnapshot.rearmPrice ?? '—' }}</dd></div><div><dt>快照状态</dt><dd>{{ item.ruleSnapshot.status }}</dd></div><div><dt>版本</dt><dd>{{ item.ruleSnapshot.version }}</dd></div><div><dt>备注</dt><dd>{{ item.ruleSnapshot.note || '—' }}</dd></div></dl></details>
      </article>
    </div>
    <details v-if="hasChart" class="chart-point-details"><summary>点位详情</summary><dl class="event-snapshot"><div><dt>纽约时间</dt><dd>{{ formatNewYorkTimestamp(inspectedTime) }}</dd></div><div><dt>原始时间</dt><dd>{{ inspectedTime }}</dd></div><div><dt>精确价格</dt><dd>{{ inspectedRawPrice }}</dd></div><template v-if="eventScope === 'SIMULATION'"><div><dt>起点原价</dt><dd>{{ originPrice }}</dd></div><div><dt>较起点偏移</dt><dd>{{ inspectedOffset ? signed(inspectedOffset.value) : '—' }}</dd></div><div><dt>模拟点</dt><dd>{{ inspectedSequence ?? '真实前缀' }}</dd></div></template><div v-if="pointDetails[inspectedPoint?.index ?? -1]"><dt>数据说明</dt><dd>{{ pointDetails[inspectedPoint!.index] }}</dd></div><div v-if="pointStates[inspectedPoint?.index ?? -1]"><dt>规则状态</dt><dd>{{ ruleStatusLabel[pointStates[inspectedPoint!.index]!] }}</dd></div></dl><p v-for="event in inspectedEvents" :key="event.id">{{ event.ruleName ?? event.label }} · {{ event.detail }}</p><p v-for="(annotation, index) in inspectedLegacy" :key="index">{{ annotation.detail }}</p></details>
    <div v-if="annotations.length || cooldownBands.length" class="chart-legend"><span v-if="annotations.some(item => item.kind !== 'REARMED')"><i :style="{ background: color(annotations.find(item => item.kind !== 'REARMED')!.kind) }"></i>触发点</span><span v-if="annotations.some(item => item.kind === 'REARMED')"><i class="legend-rearm"></i>重新激活</span><span v-if="cooldownBands.length"><i class="legend-cooldown"></i>冷却区间</span></div>
    <p v-if="values.length" class="chart-hint">悬停查看 · 点击触发标记展开详情 · ← / → 切换点位<template v-if="axisBreaks.length"> · // 省略休市</template></p>
  </div>
</template>

<style scoped>
.price-chart-wrap { padding: 16px 20px 10px; container-type: inline-size; }
.chart-heading-grid { display: grid; grid-template-columns: minmax(150px, 1fr) minmax(240px, 280px) minmax(105px, auto); gap: 14px; align-items: start; margin-bottom: 6px; }
.chart-heading-grid.without-latest { grid-template-columns: minmax(0, 1fr) minmax(240px, 280px); }
.chart-heading-context { min-width: 0; }
.chart-heading-context :deep(h2), .chart-heading-context h3 { margin: 0 0 5px; font-size: 20px; line-height: 1.25; }
.chart-heading-latest { text-align: right; min-width: 0; }
.chart-heading-latest :deep(strong) { display: block; font-size: 29px; line-height: 1.3; font-variant-numeric: tabular-nums; }
.chart-heading-latest :deep(small) { display: block; color: var(--muted); font-size: 11px; margin-top: 3px; }
.chart-heading-latest :deep(.price-return) { margin-top: 6px; font-size: 14px; }
.price-chart { position: relative; min-width: 0; color: #39aa83; }
.price-chart.falling { color: #e36b60; }
.price-chart svg { display: block; width: 100%; height: 310px; touch-action: pan-y; border-radius: 6px; }
.price-chart svg:focus-visible { outline: 2px solid #55a180; outline-offset: 3px; }
.grid-line { stroke: #e8ebe5; stroke-width: 1; stroke-dasharray: 3 5; }
.axis-line { stroke: #ced5cd; stroke-width: 1; }
.axis-label { fill: #78827e; font-size: 11px; font-variant-numeric: tabular-nums; }
.axis-title { fill: #5b6962; font-size: 11px; font-weight: 600; }
.threshold-line text { font-size: 10px; paint-order: stroke; stroke: #fafbf7; stroke-width: 3px; }
.event-marker { cursor: pointer; }
.event-marker text { font-size: 10px; font-weight: 600; }
.chart-cursor { pointer-events: none; }
.chart-cursor line { stroke: #7d8b83; stroke-width: 1; stroke-dasharray: 4 4; opacity: .7; }
.chart-point-details { margin-top: 8px; font-size: 11px; color: var(--muted); }
.event-snapshot { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 8px 20px; font-size: 11px; }
.event-snapshot dt { color: var(--muted); } .event-snapshot dd { margin: 4px 0 0; overflow-wrap: anywhere; }
.chart-legend { display: flex; justify-content: center; flex-wrap: wrap; gap: 14px; margin-top: 7px; font-size: 10px; color: #7d887f; }
.chart-legend span { display: inline-flex; align-items: center; gap: 5px; }
.chart-legend i { width: 7px; height: 7px; border-radius: 50%; background: #278b68; }
.chart-legend .legend-rearm { background: #537dad; }
.chart-legend .legend-cooldown { width: 12px; border-radius: 2px; background: #eddaaa; }
.chart-hint { margin: 7px 0 0; text-align: center; color: #8a938d; font-size: 10px; }
.chart-empty { height: 272px; }
.temporal-event-details { margin-top: 12px; padding: 12px; background: #f1f7f1; border-radius: 10px; max-height: 260px; overflow: auto; }
.temporal-event-details article { padding: 8px 0; border-bottom: 1px solid #dce6dc; font-size: 12px; }
.temporal-event-details time { display: block; margin-top: 5px; font-size: 11px; color: var(--muted); }
.temporal-event-details p { margin: 5px 0; line-height: 1.6; }
@container (max-width: 550px) { .chart-heading-grid, .chart-heading-grid.without-latest { grid-template-columns: minmax(0, 1fr) auto; } .chart-heading-context { grid-column: 1 / -1; } }
@container (max-width: 390px) { .chart-heading-grid, .chart-heading-grid.without-latest { grid-template-columns: minmax(0, 1fr); } .chart-heading-latest { text-align: left; } }
@media (max-width: 660px) { .price-chart-wrap { padding-right: 12px; padding-left: 12px; } }
</style>
