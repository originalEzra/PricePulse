<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { errorMessage } from '../api/client'
import { ruleApi } from '../api'
import type { AlertRule, SaveAlertRule, Stock } from '../api/types'

const props = defineProps<{
  open: boolean
  stock: Stock | null
  rule?: AlertRule | null
  initialDraft?: SaveAlertRule | null
}>()
const emit = defineEmits<{ close: []; saved: [rule: AlertRule] }>()
const submitting = ref(false)
const error = ref('')
const form = reactive<SaveAlertRule>({
  stockId: 0,
  name: '',
  note: '',
  direction: 'ABOVE',
  thresholdPrice: 100,
  triggerMode: 'ONCE',
  rearmPrice: null,
})

watch(
  () => [props.open, props.stock, props.rule, props.initialDraft] as const,
  () => {
    if (!props.open || !props.stock) return
    const source = props.rule ?? props.initialDraft
    Object.assign(form, {
      stockId: props.stock.id,
      name: source?.name ?? `${props.stock.symbol} 价格提醒`,
      note: source?.note ?? '',
      direction: source?.direction ?? 'ABOVE',
      thresholdPrice: source?.thresholdPrice ?? Number(Number(props.stock.latestPrice ?? 100).toFixed(2)),
      triggerMode: source?.triggerMode ?? 'ONCE',
      rearmPrice: source?.rearmPrice ?? null,
    })
    error.value = ''
  },
  { immediate: true },
)

const rearmHint = computed(() =>
  form.direction === 'ABOVE' ? '重新激活价必须低于触发价' : '重新激活价必须高于触发价',
)

async function submit() {
  error.value = ''
  if (form.triggerMode === 'REPEAT') {
    if (!form.rearmPrice) {
      error.value = '重复提醒需要填写重新激活价'
      return
    }
    if (form.direction === 'ABOVE' && form.rearmPrice >= form.thresholdPrice) {
      error.value = rearmHint.value
      return
    }
    if (form.direction === 'BELOW' && form.rearmPrice <= form.thresholdPrice) {
      error.value = rearmHint.value
      return
    }
  }
  submitting.value = true
  try {
    const payload = { ...form, rearmPrice: form.triggerMode === 'REPEAT' ? form.rearmPrice : null }
    const saved = props.rule
      ? await ruleApi.update(props.rule.id, props.rule.version, payload)
      : await ruleApi.create(payload)
    emit('saved', saved)
    emit('close')
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="modal-backdrop" @click.self="emit('close')">
      <section class="modal-card" role="dialog" aria-modal="true" aria-labelledby="rule-modal-title">
        <header class="modal-head">
          <div>
            <p class="eyebrow">{{ stock?.symbol }}</p>
            <h2 id="rule-modal-title">{{ rule ? '编辑提醒规则' : '创建提醒规则' }}</h2>
          </div>
          <button class="icon-button" aria-label="关闭" @click="emit('close')">×</button>
        </header>
        <form class="form-stack" @submit.prevent="submit">
          <label>规则名称<input v-model.trim="form.name" maxlength="100" required /></label>
          <div class="form-grid">
            <label>触发方向
              <select v-model="form.direction">
                <option value="ABOVE">价格上破</option><option value="BELOW">价格下破</option>
              </select>
            </label>
            <label>触发价格<input v-model.number="form.thresholdPrice" min="0.0001" step="0.0001" type="number" required /></label>
          </div>
          <label>提醒方式
            <select v-model="form.triggerMode">
              <option value="ONCE">仅提醒一次</option><option value="REPEAT">离开区间后可再次提醒</option>
            </select>
          </label>
          <label v-if="form.triggerMode === 'REPEAT'">重新激活价
            <input v-model.number="form.rearmPrice" min="0.0001" step="0.0001" type="number" required />
            <small>{{ rearmHint }}</small>
          </label>
          <label>备注（可选）<textarea v-model.trim="form.note" maxlength="500" rows="3" /></label>
          <p v-if="error" class="form-error">{{ error }}</p>
          <div class="modal-actions">
            <button type="button" class="button secondary" @click="emit('close')">取消</button>
            <button class="button primary" :disabled="submitting">{{ submitting ? '保存中…' : '保存规则' }}</button>
          </div>
        </form>
      </section>
    </div>
  </Teleport>
</template>
