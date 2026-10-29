import test from 'node:test'
import assert from 'node:assert/strict'
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

test('late reads cannot restore an old experiment or lower the current version', async () => {
  const current = { runId: 'new', version: 2 }
  const delayed = Promise.resolve({ runId: 'old', version: 50 })
  assert.equal(acceptsRunVersion(current, await delayed, 1, 2), false)
  assert.equal(acceptsRunVersion(current, { runId: 'new', version: 1 }, 2, 2), false)
  assert.equal(acceptsRunVersion(current, { runId: 'new', version: 3 }, 2, 2), true)
  assert.equal(acceptsRunVersion(current, { runId: 'replacement', version: 0 }, 3, 3), true)
})
