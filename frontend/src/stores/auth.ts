import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { AUTH_STORAGE_KEY } from '../api/client'
import { authApi } from '../api'
import type { AuthPayload } from '../api/types'

function restore(): AuthPayload | null {
  try {
    const raw = localStorage.getItem(AUTH_STORAGE_KEY)
    if (!raw) return null
    const saved = JSON.parse(raw) as AuthPayload
    if (new Date(saved.expiresAt).getTime() > Date.now()) return saved
    localStorage.removeItem(AUTH_STORAGE_KEY)
    return null
  } catch {
    localStorage.removeItem(AUTH_STORAGE_KEY)
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  const session = ref<AuthPayload | null>(restore())
  const authenticated = computed(() => Boolean(session.value?.accessToken))

  function save(payload: AuthPayload) {
    session.value = payload
    localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(payload))
  }

  async function login(email: string, password: string) {
    save(await authApi.login(email, password))
  }

  async function register(email: string, password: string, displayName: string) {
    save(await authApi.register(email, password, displayName))
  }

  async function logout() {
    try {
      if (authenticated.value) await authApi.logout()
    } finally {
      clear()
    }
  }

  function clear() {
    session.value = null
    localStorage.removeItem(AUTH_STORAGE_KEY)
  }

  return { session, authenticated, login, register, logout, clear }
})
