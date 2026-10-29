import { spawn } from 'node:child_process'
// Run with node --env-file=.local/alpaca.env. Credentials are inherited, never printed or passed as CLI arguments.
const args = process.argv.slice(2)
if (args.some(arg => arg !== '--open-session')) throw new Error('Supported option: --open-session')
const test = args.includes('--open-session') ? 'AlpacaOpenSessionReadOnlyIT' : 'AlpacaReadOnlySmokeIT'
const child = spawn('./mvnw', ['-B', '-ntp', `-Dit.test=${test}`, 'test-compile', 'failsafe:integration-test', 'failsafe:verify'], {
  cwd: new URL('..', import.meta.url), stdio: 'inherit', env: { ...process.env, PRICEPULSE_RUN_READONLY_SMOKE: 'true', PRICEPULSE_ALPACA_ENABLED: 'false' },
})
child.on('exit', code => { process.exitCode = code ?? 1 })
child.on('error', () => { process.stderr.write('Could not start the read-only verification.\n'); process.exitCode = 1 })
