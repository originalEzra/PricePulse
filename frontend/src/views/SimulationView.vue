<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { marketApi, stockApi } from '../api'
import { errorMessage } from '../api/client'
import type { SimulationResult, Stock } from '../api/types'

const stocks = ref<Stock[]>([])
const running = ref(false)
const error = ref('')
const result = ref<SimulationResult | null>(null)
const form = reactive({ stockId: 0, runKey: `demo-${Date.now()}`, steps: 40, mode: 'OSCILLATE' as 'NORMAL' | 'RISE' | 'FALL' | 'OSCILLATE', seed: 42, stepRatio: 0.015, initialPrice: 100 })

async function loadStocks() {
  try {
    stocks.value = await stockApi.list()
    const first = stocks.value[0]
    if (first) { form.stockId = first.id; form.initialPrice = Number(first.latestPrice ?? 100) }
  } catch (cause) { error.value = errorMessage(cause) }
}

async function run() {
  running.value = true; error.value = ''; result.value = null
  try {
    const now = new Date()
    const localNow = new Date(now.getTime() - now.getTimezoneOffset() * 60_000).toISOString().slice(0, 19)
    result.value = await marketApi.simulate({ ...form, startTime: localNow })
    form.runKey = `demo-${Date.now()}`
  } catch (cause) { error.value = errorMessage(cause) }
  finally { running.value = false }
}

onMounted(loadStocks)
</script>

<template>
  <div class="page-wrap narrow-page">
    <header class="page-head"><div><p class="eyebrow">MARKET LAB</p><h1>模拟行情</h1><p class="muted">用可复现的价格序列验证规则、状态机和通知链路。</p></div></header>
    <p v-if="error" class="page-error">{{ error }}</p>
    <div class="simulation-grid">
      <section class="panel simulator-card">
        <div class="panel-head"><div><p class="eyebrow">SCENARIO</p><h2>实验参数</h2></div><span class="live-chip">本地模拟器</span></div>
        <form class="form-stack" @submit.prevent="run">
          <label>股票<select v-model.number="form.stockId" required><option v-for="stock in stocks" :key="stock.id" :value="stock.id">{{ stock.symbol }} · {{ stock.name }}</option></select></label>
          <div class="form-grid"><label>行情模式<select v-model="form.mode"><option value="NORMAL">正常波动</option><option value="RISE">快速上涨</option><option value="FALL">急速下跌</option><option value="OSCILLATE">阈值震荡</option></select></label><label>生成步数<input v-model.number="form.steps" type="number" min="1" max="1000" /></label></div>
          <div class="form-grid"><label>初始价格<input v-model.number="form.initialPrice" type="number" min="0.0001" step="0.0001" /></label><label>单步波动比例<input v-model.number="form.stepRatio" type="number" min="0.0001" max="0.2" step="0.0001" /></label></div>
          <div class="form-grid"><label>随机种子<input v-model.number="form.seed" type="number" /></label><label>运行标识<input v-model.trim="form.runKey" pattern="[A-Za-z0-9_-]{1,50}" required /></label></div>
          <button class="button primary wide" :disabled="running || !stocks.length">{{ running ? '正在生成并处理…' : '启动行情实验' }}</button>
        </form>
      </section>
      <section class="panel result-card">
        <div><p class="eyebrow">LAST RUN</p><h2>实验结果</h2></div>
        <div v-if="result" class="result-metrics">
          <div><strong>{{ result.requested }}</strong><span>生成事件</span></div><div><strong>{{ result.processed }}</strong><span>成功处理</span></div><div><strong>{{ result.duplicates }}</strong><span>重复事件</span></div><div><strong>{{ result.discarded }}</strong><span>丢弃事件</span></div>
        </div>
        <div v-else class="empty-state roomy"><span class="lab-orbit">◎</span><strong>等待第一次实验</strong><span>相同 runKey、seed、初始价格与时间可以复现同一组行情。</span></div>
        <div class="experiment-note"><strong>这一步验证什么？</strong><p>行情先经过时序与去重校验，再查询 Redis 阈值索引。真正触发后，MySQL 状态、触发记录和 Outbox 在同一事务提交。</p></div>
      </section>
    </div>
  </div>
</template>
