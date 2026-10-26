<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { errorMessage } from '../api/client'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()
const mode = ref<'login' | 'register'>('login')
const busy = ref(false)
const error = ref('')
const form = reactive({ displayName: '', email: '', password: '' })

async function submit() {
  busy.value = true
  error.value = ''
  try {
    if (mode.value === 'login') await auth.login(form.email, form.password)
    else await auth.register(form.email, form.password, form.displayName)
    await router.replace('/')
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-story">
      <div class="brand brand-light"><span class="brand-mark"><i></i><i></i><i></i></span><span>PricePulse</span></div>
      <div class="story-copy">
        <p class="eyebrow light">STAY AHEAD OF THE MOVE</p>
        <h1>价格变化发生时，<br />让提醒主动找到你。</h1>
        <p>创建清晰的价格边界，验证每一次穿越，并用可靠的异步链路送达通知。</p>
      </div>
      <div class="pulse-visual" aria-hidden="true"><span></span><span></span><span></span></div>
      <p class="story-foot">行情模拟 · 规则匹配 · 可靠通知</p>
    </section>
    <section class="auth-panel">
      <div class="auth-form-wrap">
        <p class="eyebrow">PRICEPULSE CONSOLE</p>
        <h2>{{ mode === 'login' ? '欢迎回来' : '创建账户' }}</h2>
        <p class="muted">{{ mode === 'login' ? '登录后查看你的股票提醒和最新通知。' : '开始创建属于你的价格监控规则。' }}</p>
        <div class="segmented">
          <button :class="{ active: mode === 'login' }" @click="mode = 'login'; error = ''">登录</button>
          <button :class="{ active: mode === 'register' }" @click="mode = 'register'; error = ''">注册</button>
        </div>
        <form class="form-stack" @submit.prevent="submit">
          <label v-if="mode === 'register'">显示名称<input v-model.trim="form.displayName" maxlength="64" required autocomplete="name" /></label>
          <label>邮箱<input v-model.trim="form.email" type="email" required autocomplete="email" placeholder="you@example.com" /></label>
          <label>密码<input v-model="form.password" type="password" minlength="8" required :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" placeholder="至少 8 位" /></label>
          <p v-if="error" class="form-error">{{ error }}</p>
          <button class="button primary wide" :disabled="busy">{{ busy ? '请稍候…' : mode === 'login' ? '进入控制台' : '注册并进入' }}</button>
        </form>
        <p class="security-note">JWT 仅保存在当前浏览器；退出后客户端会清除凭证。</p>
      </div>
    </section>
  </main>
</template>
