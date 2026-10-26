<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ values: number[] }>()

const points = computed(() => {
  if (props.values.length < 2) return ''
  const min = Math.min(...props.values)
  const max = Math.max(...props.values)
  const range = max - min || 1
  return props.values
    .map((value, index) => `${(index / (props.values.length - 1)) * 100},${52 - ((value - min) / range) * 44}`)
    .join(' ')
})

const rising = computed(() => {
  const first = props.values[0]
  const last = props.values.at(-1)
  return first !== undefined && last !== undefined ? last >= first : true
})
</script>

<template>
  <div class="sparkline" :class="{ falling: !rising }">
    <div v-if="values.length < 2" class="chart-empty">生成行情后显示走势</div>
    <svg v-else viewBox="0 0 100 56" preserveAspectRatio="none" role="img" aria-label="近期价格走势">
      <defs>
        <linearGradient id="chart-fill" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="currentColor" stop-opacity="0.28" />
          <stop offset="100%" stop-color="currentColor" stop-opacity="0" />
        </linearGradient>
      </defs>
      <polyline :points="`${points} 100,56 0,56`" fill="url(#chart-fill)" stroke="none" />
      <polyline :points="points" fill="none" stroke="currentColor" stroke-width="1.7" vector-effect="non-scaling-stroke" />
    </svg>
  </div>
</template>
