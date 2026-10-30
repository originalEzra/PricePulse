import test from 'node:test'
import assert from 'node:assert/strict'
import { createAssetSearch, confirmedMonitoring, subscriptionLabels } from '../src/utils/monitoring.ts'
const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms))

test('pending and failed requests cannot be presented as confirmed monitoring', () => {
  assert.equal(confirmedMonitoring('CONFIRMED'), true)
  assert.equal(confirmedMonitoring('PENDING'), false)
  assert.equal(confirmedMonitoring('FAILED'), false)
  assert.equal(confirmedMonitoring(undefined), false)
  assert.equal(subscriptionLabels.PENDING, '订阅中')
})
test('rapid input debounces upstream reads to the final query', async () => {
  const calls: string[] = [], values: string[] = []
  const search = createAssetSearch(async query => { calls.push(query); return query }, value => { if (value) values.push(value) }, 15)
  search.query('P'); search.query('PL'); search.query('PLTR')
  await sleep(50); search.dispose()
  assert.deepEqual(calls, ['PLTR']); assert.deepEqual(values, ['PLTR'])
})
test('late responses cannot replace current query or survive account disposal', async () => {
  const pending: Array<(value: string) => void> = [], values: string[] = []
  const search = createAssetSearch(() => new Promise<string>(resolve => pending.push(resolve)), value => { if (value) values.push(value) }, 1)
  search.query('OLD'); await sleep(10); search.query('NEW'); await sleep(10)
  pending[1]!('new'); await sleep(1); pending[0]!('old'); await sleep(1)
  assert.deepEqual(values, ['new'])
  search.query('ANOTHER'); await sleep(10); search.dispose(); pending[2]!('other account'); await sleep(1)
  assert.deepEqual(values, ['new'])
})
