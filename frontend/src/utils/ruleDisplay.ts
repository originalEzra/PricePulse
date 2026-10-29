import type { AlertRule, SimulationSnapshot } from '../api/types'
import type { ChartThreshold } from './chart'

type RuleSummary = Pick<AlertRule, 'id' | 'stockId' | 'status'>

/** Defaults run once per stock. Refreshes prune deleted/moved rules without resetting explicit choices. */
export function reconcileShownRules(previous: ReadonlySet<number> | undefined, rules: RuleSummary[]): Set<number> {
  const available = rules.filter(rule => rule.status !== 'DELETED')
  return previous === undefined
    ? new Set(available.filter(rule => rule.status === 'ACTIVE' || rule.status === 'COOLDOWN').slice(0, 3).map(rule => rule.id))
    : new Set(available.filter(rule => previous.has(rule.id)).map(rule => rule.id))
}

/** Stable by identity, including when the list is reordered, refreshed, or another stock is viewed. */
export function ruleLineColor(id: number): string {
  return `hsl(${(id * 137.508 % 360).toFixed(1)} 58% 38%)`
}

export function shownRuleThresholds(rules: AlertRule[], shown: ReadonlySet<number>): ChartThreshold[] {
  return rules.filter(rule => shown.has(rule.id) && rule.status !== 'DELETED').map(rule => ({
    value: rule.thresholdPrice, label: `${rule.name} · ${rule.direction === 'ABOVE' ? '上破' : '下破'}`,
    kind: rule.direction, color: ruleLineColor(rule.id),
  }))
}

export function simulationThresholds(snapshots: SimulationSnapshot[], shown: ReadonlySet<number>): ChartThreshold[] {
  return snapshots.filter(rule => shown.has(rule.snapshotRuleId)).map(rule => ({ value: Number(rule.config.thresholdPrice),
    label: `${rule.config.name} · ${rule.config.direction === 'ABOVE' ? '上破' : '下破'}`, kind: rule.config.direction,
    color: ruleLineColor(rule.sourceRuleId) }))
}

/** The existing list is capped at 100. Timeline references include every owned, non-deleted rule. */
export async function completeStockRules(stockId: number, references: Array<{ id: number; version: number }>,
  known: AlertRule[], read: (id: number) => Promise<AlertRule | null>): Promise<AlertRule[]> {
  const cached = new Map<number, AlertRule>()
  for (const rule of known) if (!cached.has(rule.id) || cached.get(rule.id)!.version <= rule.version) cached.set(rule.id, rule)
  const refs = [...new Map(references.map(rule => [rule.id, rule])).values()]
  const missing = refs.filter(ref => !cached.has(ref.id) || cached.get(ref.id)!.version < ref.version)
  for (let offset = 0; offset < missing.length; offset += 8) {
    const batch = await Promise.all(missing.slice(offset, offset + 8).map(async ref => ({ id: ref.id, rule: await read(ref.id) })))
    for (const item of batch) {
      if (item.rule?.id === item.id) cached.set(item.id, item.rule)
      else cached.delete(item.id)
    }
  }
  return refs.flatMap(ref => {
    const rule = cached.get(ref.id)
    return rule?.stockId === stockId && rule.status !== 'DELETED' ? [rule] : []
  })
}
