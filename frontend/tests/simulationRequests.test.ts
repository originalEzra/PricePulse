import test from 'node:test'
import assert from 'node:assert/strict'
import { reactive, ref } from 'vue'
import { acceptsRunVersion, RetriableSimulationRequest, simulationSeed } from '../src/utils/simulationRequests.ts'

test('full-width server seeds survive copy and retry without JavaScript rounding', () => {
  assert.equal(simulationSeed('9223372036854775807'), '9223372036854775807')
  assert.equal(simulationSeed('-9223372036854775808'), '-9223372036854775808')
  assert.equal(simulationSeed('0042'), '42')
  assert.equal(simulationSeed(''), null)
  assert.throws(() => simulationSeed('9223372036854775808'))
})

test('lost responses retry the frozen original command until acknowledgement', () => {
  let keys = 0
  const request = new RetriableSimulationRequest<{ runId: string; expectedVersion: number }>(() => `key-${++keys}`)
  const body = { runId: 'first', expectedVersion: 0 }
  const pending = request.prepare(body)
  body.runId = 'edited'
  request.fail(0)
  assert.deepEqual(request.prepare({ runId: 'second', expectedVersion: 5 }), pending)
  assert.equal(pending.body.runId, 'first')
  request.fail(503)
  assert.equal(request.prepare({ runId: 'third', expectedVersion: 9 }).key, 'key-1')
  request.acknowledge()
  assert.equal(request.prepare({ runId: 'first', expectedVersion: 1 }).key, 'key-2')
  request.fail(409)
  assert.equal(request.pending, null)
})

test('reactive replacement parameters freeze a JSON command, including nested proxies and exact seeds', () => {
  const nested = reactive({ kind: 'SIMULATION_REFERENCE', label: 'synthetic' })
  const replacement = ref({ input: { stockId: 9, origin: nested, seed: '9223372036854775807',
    expectedCurrentRunId: 'legacy-run', openingGap: { source: 'CUSTOM', referencePrice: '345.0000', openingPrice: '355.0000' } } })
  const request = reactive(new RetriableSimulationRequest<typeof replacement.value.input>(() => 'replacement-key'))
  const pending = request.prepare(replacement.value.input)
  replacement.value.input.stockId = 10
  nested.label = 'edited'
  replacement.value.input.openingGap.openingPrice = '999.0000'
  assert.equal(pending.body.stockId, 9)
  assert.equal(pending.body.origin.label, 'synthetic')
  assert.equal(pending.body.openingGap.openingPrice, '355.0000')
  assert.equal(pending.body.seed, '9223372036854775807')
  assert.equal(pending.body.expectedCurrentRunId, 'legacy-run')
  request.fail(503)
  assert.equal(request.prepare(replacement.value.input).key, 'replacement-key')
  assert.equal(request.pending!.body.stockId, 9)
})

test('late reads cannot restore an old experiment or lower the current version', async () => {
  const current = { runId: 'new', version: 2 }
  const delayed = Promise.resolve({ runId: 'old', version: 50 })
  assert.equal(acceptsRunVersion(current, await delayed, 1, 2), false)
  assert.equal(acceptsRunVersion(current, { runId: 'new', version: 1 }, 2, 2), false)
  assert.equal(acceptsRunVersion(current, { runId: 'new', version: 3 }, 2, 2), true)
  assert.equal(acceptsRunVersion(current, { runId: 'replacement', version: 0 }, 3, 3), true)
})
