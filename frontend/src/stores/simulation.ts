import { onScopeDispose, reactive, watch } from 'vue'
import { defineStore } from 'pinia'
import { simulationApi } from '../api'
import { useAuthStore } from './auth'
import { SimulationSession } from '../utils/simulationRequests'
import { collectSimulationEvents } from '../utils/simulationDisplay'
import type { SimulationMode } from '../api/types'

export const useSimulationStore = defineStore('simulation', () => {
  const auth = useAuthStore()
  const controller = reactive(new SimulationSession({ ...simulationApi,
    readEvents: async (id, from, to, signal) => collectSimulationEvents(
      await simulationApi.events(id, from, to, undefined, signal),
      cursor => simulationApi.events(id, from, to, cursor, signal)),
  }))
  const defaults = () => ({ browseStockId: 0, originKind: 'HISTORICAL_POINT' as 'LATEST' | 'HISTORICAL_POINT',
    mode: 'NORMAL' as SimulationMode, percent: 0.3, seedInput: '', referenceSource: 'REAL_HISTORY' as 'REAL_HISTORY' | 'CUSTOM',
    customReference: '', openingPrice: '', continuationMode: 'NORMAL' as Exclude<SimulationMode, 'OPENING_GAP'> })
  const draft = reactive(defaults())
  const shownByRun = reactive(new Map<string, Set<number>>())
  function reset() { controller.reset(); Object.assign(draft, defaults()); shownByRun.clear() }
  watch(() => auth.session?.accessToken, reset, { flush: 'sync' })
  onScopeDispose(reset)
  async function enter() { if (auth.authenticated && document.visibilityState === 'visible') await controller.enter() }
  return { controller, draft, shownByRun, enter }
})
