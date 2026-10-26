<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const isAuthPage = computed(() => route.name === 'auth')

async function logout() {
  await auth.logout()
  await router.replace({ name: 'auth' })
}
</script>

<template>
  <RouterView v-if="isAuthPage" />
  <div v-else class="app-shell">
    <aside class="sidebar">
      <RouterLink class="brand" to="/">
        <span class="brand-mark"><i></i><i></i><i></i></span>
        <span>PricePulse</span>
      </RouterLink>
      <nav class="main-nav" aria-label="主导航">
        <RouterLink to="/" class="nav-link">
          <span class="nav-icon">⌁</span><span>行情与提醒</span>
        </RouterLink>
        <RouterLink to="/notifications" class="nav-link">
          <span class="nav-icon">◉</span><span>通知中心</span>
        </RouterLink>
        <RouterLink to="/simulation" class="nav-link">
          <span class="nav-icon">↗</span><span>模拟行情</span>
        </RouterLink>
      </nav>
      <div class="sidebar-foot">
        <div class="avatar">{{ auth.session?.user.displayName.slice(0, 1).toUpperCase() }}</div>
        <div class="user-meta">
          <strong>{{ auth.session?.user.displayName }}</strong>
          <span>{{ auth.session?.user.email }}</span>
        </div>
        <button class="icon-button" title="退出登录" @click="logout">↪</button>
      </div>
    </aside>
    <main class="main-content">
      <RouterView />
    </main>
  </div>
</template>
