import { spawnSync } from 'node:child_process'
import { closeSync, existsSync, mkdirSync, openSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'

// Explicit operator action. Dump bytes and private configuration never go to stdout.
const root = fileURLToPath(new URL('..', import.meta.url))
const stamp = new Date().toISOString().replace(/[:.]/g, '-')
const directory = resolve(root, '.local/backups', `p2-readonly-${stamp}`)
const sql = resolve(directory, 'development-before-v4-v6.sql')
mkdirSync(directory, { recursive: true, mode: 0o700 })
const fd = openSync(sql, 'wx', 0o600)
let result
try {
  result = spawnSync('docker', ['compose', 'exec', '-T', 'mysql', 'sh', '-c',
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --events --triggers --hex-blob --set-gtid-purged=OFF --no-tablespaces "$MYSQL_DATABASE"'],
  { cwd: root, stdio: ['ignore', fd, 'pipe'], timeout: 60_000 })
} finally { closeSync(fd) }
if (result.error || result.status !== 0) {
  console.error('BACKUP_FAILED; private partial dump retained for inspection, backend unchanged.')
  process.exitCode = 1
} else {
  const bytes = readFileSync(sql)
  const complete = bytes.includes(Buffer.from('-- Dump completed on '))
    && bytes.includes(Buffer.from('CREATE TABLE `flyway_schema_history`'))
    && bytes.includes(Buffer.from('CREATE TABLE `quote_events`'))
  if (!complete) throw new Error('BACKUP_COMPLETENESS_CHECK_FAILED')
  const fingerprint = path => existsSync(path) ? createHash('sha256').update(readFileSync(path)).digest('hex') : null
  const manifest = {
    createdAt: new Date().toISOString(), sql, bytes: bytes.length,
    sha256: createHash('sha256').update(bytes).digest('hex'), dumpCompleted: complete,
    sqlMode: (statSync(sql).mode & 0o777).toString(8),
    privateConfigurationFingerprints: Object.fromEntries([
      '.local/alpaca.env', '.run/PricePulse Local.run.xml', 'frontend/.env.local',
    ].map(path => [path, fingerprint(resolve(root, path))])),
    restoreRehearsed: false,
  }
  writeFileSync(resolve(directory, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n', { mode: 0o600, flag: 'wx' })
  console.log(JSON.stringify({ backupDirectory: directory, bytes: bytes.length, dumpCompleted: complete, sqlMode: manifest.sqlMode }))
}
