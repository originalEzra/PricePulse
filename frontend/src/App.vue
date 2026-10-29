<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth'
import { useNotificationsStore } from './stores/notifications'
import { useSimulationStore } from './stores/simulation'
import { AUTH_STORAGE_KEY } from './api/client'
import NotificationToasts from './components/NotificationToasts.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const notifications = useNotificationsStore()
const simulation = useSimulationStore()
const sessionEpoch = ref(0)
watch(() => auth.session?.accessToken, () => { ++sessionEpoch.value }, { flush: 'sync' })
const isAuthPage = computed(() => route.name === 'auth')

watch(() => !isAuthPage.value && auth.authenticated ? auth.session?.accessToken : null, token => {
  if (token && auth.session) notifications.start(auth.session.user.id)
  else notifications.stop()
}, { immediate: true })

function unauthorized() { auth.clear() }
function storageChanged(event: StorageEvent) {
  if (event.key !== AUTH_STORAGE_KEY && event.key !== null) return
  auth.syncFromStorage()
  if (!auth.authenticated) void router.replace({ name: 'auth' })
  else if (isAuthPage.value) void router.replace('/')
}
window.addEventListener('pricepulse:unauthorized', unauthorized)
window.addEventListener('storage', storageChanged)
onBeforeUnmount(() => {
  notifications.stop()
  window.removeEventListener('pricepulse:unauthorized', unauthorized)
  window.removeEventListener('storage', storageChanged)
})

async function logout() {
  simulation.controller.leave('已退出，自动播放已暂停。')
  try { await auth.logout() }
  finally { await router.replace({ name: 'auth' }) }
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
        <RouterLink to="/notifications" class="nav-link" :aria-label="notifications.unreadCount ? `通知中心，${notifications.unreadCount} 条未读` : '通知中心'">
          <span class="nav-icon notification-nav-icon">◉<span v-if="notifications.badgeText" class="notification-nav-badge" aria-hidden="true">{{ notifications.badgeText }}</span></span><span>通知中心</span>
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
      <RouterView :key="sessionEpoch" />
    </main>
    <NotificationToasts />
  </div>
</template>

<style scoped>
.notification-nav-icon { position: relative; }
.notification-nav-badge { position: absolute; top: -7px; right: -11px; display: inline-flex; align-items: center; justify-content: center; min-width: 17px; height: 17px; padding: 0 4px; border: 2px solid var(--navy); border-radius: 20px; background: #ef655d; color: white; font-size: 9px; line-height: 1; font-weight: 700; }
.router-link-active .notification-nav-badge { border-color: #e9f9f3; }
</style>
