<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useNotificationsStore } from '../stores/notifications'

const notifications = useNotificationsStore()
const router = useRouter()

function open(id: number) {
  notifications.dismissToast(id)
  void router.push('/notifications')
}
</script>

<template>
  <Teleport to="body">
    <section class="notification-toasts" aria-label="新价格提醒" aria-live="polite" aria-relevant="additions">
      <TransitionGroup name="notification-toast" tag="div" class="toast-stack">
        <article v-for="toast in notifications.toasts" :key="toast.id" class="notification-toast" @mouseenter="notifications.pauseToast(toast.id)" @mouseleave="notifications.resumeToast(toast.id)" @focusin="notifications.pauseToast(toast.id)" @focusout="notifications.resumeToast(toast.id)">
          <button class="toast-body" @click="open(toast.id)">
            <span class="toast-eyebrow">PRICE ALERT · 新提醒</span>
            <strong>{{ toast.title }}</strong>
            <span class="toast-message">{{ toast.message }}</span>
            <span class="toast-link">查看通知 →</span>
          </button>
          <button class="toast-close" aria-label="关闭这条提醒" @click="notifications.dismissToast(toast.id)">×</button>
        </article>
      </TransitionGroup>
    </section>
  </Teleport>
</template>

<style scoped>
.notification-toasts { position: fixed; z-index: 45; top: 22px; right: 24px; width: min(370px, calc(100vw - 32px)); pointer-events: none; }
.toast-stack { display: grid; gap: 12px; }
.notification-toast { position: relative; overflow: hidden; border: 1px solid #cde2d6; border-left: 4px solid #39aa83; border-radius: 13px; background: #fffffff7; box-shadow: 0 12px 38px #152b261f; pointer-events: auto; }
.toast-body { display: grid; gap: 8px; width: 100%; padding: 17px 42px 17px 17px; border: 0; background: transparent; color: var(--ink); text-align: left; }
.toast-eyebrow { color: #287b5c; font-size: 9px; font-weight: 700; letter-spacing: 1.2px; }
.toast-body strong { font-size: 15px; line-height: 1.5; }
.toast-message { color: #6b7770; font-size: 12px; line-height: 1.6; overflow-wrap: anywhere; }
.toast-link { color: #177a58; font-size: 11px; font-weight: 600; }
.toast-close { position: absolute; top: 10px; right: 10px; display: grid; place-items: center; width: 27px; height: 27px; padding: 0; border: 0; border-radius: 6px; color: #87958c; background: transparent; font-size: 22px; }
.toast-close:hover { background: #edf3ed; color: var(--ink); }
.toast-body:focus-visible, .toast-close:focus-visible { outline: 2px solid #55a180; outline-offset: -3px; }
.notification-toast-enter-active, .notification-toast-leave-active { transition: opacity .18s, transform .18s; }
.notification-toast-enter-from, .notification-toast-leave-to { transform: translateX(18px); opacity: 0; }
@media (max-width: 660px) { .notification-toasts { top: 12px; right: 16px; } }
@media (prefers-reduced-motion: reduce) { .notification-toast-enter-active, .notification-toast-leave-active { transition: none; } }
</style>
