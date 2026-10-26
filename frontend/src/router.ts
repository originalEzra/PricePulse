import { createRouter, createWebHistory } from 'vue-router'
import { AUTH_STORAGE_KEY } from './api/client'
import AuthView from './views/AuthView.vue'
import DashboardView from './views/DashboardView.vue'
import NotificationsView from './views/NotificationsView.vue'
import SimulationView from './views/SimulationView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/auth', name: 'auth', component: AuthView, meta: { public: true } },
    { path: '/', name: 'dashboard', component: DashboardView },
    { path: '/notifications', name: 'notifications', component: NotificationsView },
    { path: '/simulation', name: 'simulation', component: SimulationView },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach((to) => {
  let authenticated = false
  try {
    const raw = localStorage.getItem(AUTH_STORAGE_KEY)
    const expiresAt = raw ? (JSON.parse(raw) as { expiresAt?: string }).expiresAt : undefined
    authenticated = Boolean(expiresAt && new Date(expiresAt).getTime() > Date.now())
    if (!authenticated) localStorage.removeItem(AUTH_STORAGE_KEY)
  } catch {
    localStorage.removeItem(AUTH_STORAGE_KEY)
  }
  if (!to.meta.public && !authenticated) return { name: 'auth' }
  if (to.name === 'auth' && authenticated) return { name: 'dashboard' }
})

window.addEventListener('pricepulse:unauthorized', () => router.replace({ name: 'auth' }))

export default router
