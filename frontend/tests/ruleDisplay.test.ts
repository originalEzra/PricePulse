import test from 'node:test'
import assert from 'node:assert/strict'
import type { AlertRule, RuleStatus } from '../src/api/types.ts'
import { completeStockRules, reconcileShownRules, ruleLineColor, shownRuleThresholds } from '../src/utils/ruleDisplay.ts'

const rule = (id: number, status: RuleStatus = 'ACTIVE', stockId = 1, version = 0): AlertRule => ({
  id, stockId, status, version, name: `Rule ${id}`, direction: id % 2 ? 'ABOVE' : 'BELOW', thresholdPrice: 100 + id,
  triggerMode: 'REPEAT', rearmPrice: 99, stockSymbol: 'TEST', stockName: 'Synthetic',
  lastTriggeredAt: null, createdAt: '', updatedAt: '',
})

test('initial display chooses at most three ACTIVE or COOLDOWN rules and retains the complete table input', () => {
  const rules = [rule(1, 'COMPLETED'), rule(2), rule(3, 'COOLDOWN'), rule(4), rule(5), rule(6, 'PAUSED')]
  const before = structuredClone(rules)
  assert.deepEqual([...reconcileShownRules(undefined, rules)], [2, 3, 4])
  assert.deepEqual(rules, before)
  assert.deepEqual([...reconcileShownRules(undefined, [rule(1, 'COMPLETED'), rule(2, 'PAUSED')])], [])
})

test('polling preserves explicit hidden and completed selections; deleted or moved rows are pruned', () => {
  const rules = [rule(1), rule(2, 'COOLDOWN'), rule(3, 'COMPLETED'), rule(4, 'PAUSED')]
  assert.deepEqual([...reconcileShownRules(new Set([3, 4, 90]), rules)], [3, 4])
  assert.deepEqual([...reconcileShownRules(new Set(), rules)], [])
  const byStock = new Map([[1, new Set([3])], [2, new Set([20])]])
  byStock.set(1, reconcileShownRules(byStock.get(1), rules))
  assert.deepEqual([...byStock.get(2)!], [20])
})

test('multiple threshold lines use the same identity colors as the table without changing rule state', () => {
  const rules = [rule(1), rule(2, 'COOLDOWN'), rule(3, 'COMPLETED')], before = structuredClone(rules)
  const lines = shownRuleThresholds(rules, new Set([1, 2, 3]))
  assert.equal(lines.length, 3)
  assert.equal(new Set(lines.map(line => line.color)).size, 3)
  lines.forEach((line, i) => {
    assert.equal(line.color, ruleLineColor(rules[i]!.id))
    assert.equal(line.value, rules[i]!.thresholdPrice)
    assert.equal(line.kind, rules[i]!.direction)
  })
  assert.deepEqual(shownRuleThresholds(rules, new Set()), [])
  assert.deepEqual(rules, before)
  assert.equal(ruleLineColor(1), ruleLineColor([...rules].reverse()[2]!.id))
  assert.notEqual(ruleLineColor(1), ruleLineColor(9))
})

test('the full stock list includes entries past the 100-row cap and refreshes stale versions with bounded GETs', async () => {
  const all = Array.from({ length: 110 }, (_, i) => rule(i + 1, i === 109 ? 'COMPLETED' : 'ACTIVE', 1, 2))
  const known = all.slice(0, 100).map(r => ({ ...r })); known[40]!.version = 1
  const reads: number[] = []; let active = 0, peak = 0
  const result = await completeStockRules(1, all, known, async id => {
    reads.push(id); peak = Math.max(peak, ++active)
    await new Promise(resolve => setTimeout(resolve, 0)); active--
    return all.find(r => r.id === id)!
  })
  assert.deepEqual(result, all)
  assert.equal(result.at(-1)!.status, 'COMPLETED')
  assert.deepEqual(reads, [41, ...Array.from({ length: 10 }, (_, i) => 101 + i)])
  assert.ok(peak <= 8)
})

test('known newer results do not regress; deleted and moved rules are excluded without fabricating rows', async () => {
  const result = await completeStockRules(1, [rule(1), rule(2), rule(3), rule(4)], [rule(1, 'ACTIVE', 1, 5), rule(1)],
    async id => id === 2 ? null : rule(id, id === 4 ? 'DELETED' : 'ACTIVE', id === 3 ? 2 : 1))
  assert.deepEqual(result.map(r => [r.id, r.version]), [[1, 5]])
  await assert.rejects(completeStockRules(1, [rule(1)], [], async () => { throw new Error('unavailable') }), /unavailable/)
})
