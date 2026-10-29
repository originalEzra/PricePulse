<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { assistantApi } from '../api'
import { errorMessage } from '../api/client'
import type { AlertRuleDraft } from '../api/types'
import { formatPrice } from '../utils/format'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: []; useDraft: [draft: AlertRuleDraft] }>()

const text = ref('')
const loading = ref(false)
const error = ref('')
const clarification = ref('')
const draft = ref<AlertRuleDraft | null>(null)
let pendingRequest: AbortController | null = null

function cancelRequest() {
  pendingRequest?.abort()
  pendingRequest = null
  loading.value = false
}

onBeforeUnmount(cancelRequest)

watch(text, () => {
  draft.value = null
  clarification.value = ''
  error.value = ''
})

watch(
  () => props.open,
  () => {
    cancelRequest()
    error.value = ''
    clarification.value = ''
    draft.value = null
  },
)

async function generate() {
  if (loading.value || !text.value.trim()) return
  const request = new AbortController()
  pendingRequest = request
  loading.value = true
  error.value = ''
  clarification.value = ''
  draft.value = null
  try {
    const result = await assistantApi.draftAlert(text.value.trim(), request.signal)
    if (pendingRequest !== request) return
    if (result.ready && result.draft) draft.value = result.draft
    else clarification.value = result.clarificationQuestion ?? '请补充股票代码、触发方向和目标价格。'
  } catch (cause) {
    if (pendingRequest === request) error.value = errorMessage(cause)
  } finally {
    if (pendingRequest === request) {
      pendingRequest = null
      loading.value = false
    }
  }
}

const directionLabel = (value: AlertRuleDraft['direction']) => value === 'ABOVE' ? '价格上破' : '价格下破'
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="modal-backdrop" @click.self="emit('close')">
      <section class="modal-card ai-draft-modal" role="dialog" aria-modal="true" aria-labelledby="ai-draft-title">
        <header class="modal-head">
          <div>
            <p class="eyebrow">AI RULE DRAFT</p>
            <h2 id="ai-draft-title">用一句话描述提醒</h2>
          </div>
          <button class="icon-button" aria-label="关闭" @click="emit('close')">×</button>
        </header>

        <form class="form-stack" @submit.prevent="generate">
          <label>
            提醒需求
            <textarea
              v-model="text"
              maxlength="1000"
              rows="5"
              :disabled="loading"
              placeholder="例如：001 涨到 100 时提醒我，只提醒一次。"
              required
            />
            <small>支持股票名称或代码片段，多只匹配时会请你补充。AI 整理草稿后，由你确认保存。</small>
          </label>

          <div v-if="clarification" class="clarification-card">
            <strong>还需要一点信息</strong>
            <span>{{ clarification }}</span>
          </div>

          <div v-if="draft" class="draft-preview">
            <div class="draft-title">
              <span class="ticker-avatar">{{ draft.stockSymbol.slice(0, 2) }}</span>
              <div><strong>{{ draft.stockSymbol }} · {{ draft.stockName }}</strong><small>{{ draft.name }}</small></div>
            </div>
            <dl>
              <div><dt>条件</dt><dd>{{ directionLabel(draft.direction) }} {{ formatPrice(draft.thresholdPrice) }}</dd></div>
              <div><dt>模式</dt><dd>{{ draft.triggerMode === 'ONCE' ? '仅提醒一次' : '可重复提醒' }}</dd></div>
              <div v-if="draft.rearmPrice != null"><dt>重新激活</dt><dd>{{ formatPrice(draft.rearmPrice) }}</dd></div>
            </dl>
            <p v-if="draft.note">{{ draft.note }}</p>
          </div>

          <p v-if="error" class="form-error">{{ error }}</p>
          <div class="modal-actions ai-actions">
            <button type="button" class="button secondary" @click="emit('close')">取消</button>
            <button class="button primary" :disabled="loading || !text.trim()">
              {{ loading ? '正在理解…' : draft ? '重新生成' : '生成草稿' }}
            </button>
            <button v-if="draft" type="button" class="button mint" @click="emit('useDraft', draft)">填入规则表单</button>
          </div>
        </form>
      </section>
    </div>
  </Teleport>
</template>
