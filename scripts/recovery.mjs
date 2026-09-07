// Real LOCAL-DEMO process outages, not simulated exceptions. Invoke through
// recovery.ps1, exclusively: no concurrent verify/load/start/stop commands.
// Only fresh fixture accounts/operations are written through authorized APIs.
// SQL is read-only observer evidence, never an attacker capability.
import assert from 'node:assert/strict';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { performance } from 'node:perf_hooks';
import path from 'node:path';
import { assertLoadCompletion } from './load-evidence.mjs';
import { runRecoveryChild } from './recovery-child-process.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
assert.equal(process.platform, 'win32', 'This harness uses the existing Windows process ownership/start scripts.');
assert.equal(args.length, 2, 'Usage: recovery.ps1 (or recovery.mjs --docker <existing docker.exe>).');
assert.equal(args[0], '--docker');
const docker = args[1];
const powershell = path.join(process.env.SystemRoot ?? 'C:\\Windows', 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe');
const runId = `${new Date().toISOString().replaceAll(':', '-').replaceAll('.', '-')}-${randomUUID().slice(0, 8)}`;
const output = path.join(root, '.local', `recovery-${runId}`);
await mkdir(output, { recursive: true });
let secrets = {};
let restoreRequired = false;
let commandNumber = 0;
const report = {
  runId, startedAt: new Date().toISOString(), passed: false, checks: [], fixtureAccounts: [], fixtureOperations: [],
  normalRestart: 'NOT_REQUIRED', limitations: [
    'Local Windows JVM process stops, not host power loss, partition, Docker/database crash, or production failover.',
    'Run exclusively, after verify-local.ps1. Existing start/stop scripts restore normal mode with test hooks disabled.',
    'Key-service outage covers refusal to issue NEW operations; it does not test an already verified in-flight settlement.',
    'Database admin access is a read-only observer for fixture evidence, not part of the simulated attacker authority.',
    'Fixtures, database volumes, key material and evidence are retained. A forced termination of this harness may prevent finally restoration.',
  ],
};
const ledger = '00000000-0000-0000-0000-000000000010';
const bases = { app: 'http://127.0.0.1:8080', key: 'http://127.0.0.1:8081', processor: 'http://127.0.0.1:8082' };
const sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
const idem = label => `recovery-${runId}-${label}`;
function redact(value) {
  let result = String(value);
  for (const secret of Object.values(secrets)) if (typeof secret === 'string' && secret.length > 12) result = result.split(secret).join('[REDACTED]');
  return result;
}
async function save() { await writeFile(path.join(output, 'report.json'), JSON.stringify(report, null, 2)); }
async function check(name, action) {
  const started = performance.now();
  try {
    const evidence = await action();
    report.checks.push({ name, passed: true, durationMs: performance.now() - started, evidence });
    console.log(`PASS ${name}`);
  } catch (error) {
    report.checks.push({ name, passed: false, durationMs: performance.now() - started, error: redact(error.message) });
    throw error;
  } finally { await save(); }
}
async function run(executable, parameters, input = '', timeoutMs = 300_000) {
  const result = await runRecoveryChild(executable, parameters, { cwd: root, input, timeoutMs });
  return { ...result, stdout: redact(result.stdout), stderr: redact(result.stderr) };
}
async function script(name, parameters = []) {
  assert(['start.ps1', 'stop.ps1', 'recovery-stop-service.ps1'].includes(name));
  const result = await run(powershell, ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(root, 'scripts', name), ...parameters]);
  const log = `${++commandNumber}-${name}.log`;
  await writeFile(path.join(output, log), `${result.stdout}\n${result.stderr}\n`);
  assert.equal(result.code, 0, `${name} failed; see ${log}`);
}
async function http(service, route, { method = 'GET', body, key, expected = 200, token } = {}) {
  const credentials = { app: secrets.APP_API_TOKEN, key: secrets.KEY_WRITER_TOKEN, processor: secrets.PROCESSOR_APP_TOKEN };
  const headers = { authorization: `Bearer ${token ?? credentials[service]}` };
  if (body !== undefined) headers['content-type'] = 'application/json';
  if (key) headers['idempotency-key'] = key;
  const response = await fetch(bases[service] + route, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15_000) });
  assert.equal(response.status, expected, `${service}${route}: expected HTTP ${expected}, received ${response.status}`);
  return response.json();
}
async function stopped(service) {
  let reachable = false;
  try { await fetch(bases[service] + '/health', { signal: AbortSignal.timeout(2000) }); reachable = true; } catch {}
  assert.equal(reachable, false, `${service} must actually be unavailable during this check.`);
}
async function sql(database, query) {
  assert(['primary', 'audit', 'settlement'].includes(database));
  assert(/^SELECT\b/i.test(query) && !/\b(INSERT|UPDATE|DELETE|DROP|TRUNCATE|ALTER|GRANT|REVOKE|COPY|CALL|DO)\b/i.test(query), 'Observer SQL must be read-only.');
  const physical = database === 'settlement' ? 'audit' : database;
  const result = await run(docker, ['compose', '--project-name', 'secure-integrity-demo', '--project-directory', root,
    '--env-file', path.join(root, '.local', 'compose.env'), '-f', path.join(root, 'compose.yaml'),
    'exec', '-T', `${physical}-db`, 'psql', '-X', '-q', '-t', '-A', '-v', 'ON_ERROR_STOP=1',
    '-U', `${physical}_admin`, '-d', `${physical}_db`], `BEGIN READ ONLY;\n${query}\nCOMMIT;\n`, 30_000);
  assert.equal(result.code, 0, `${database} observer SQL failed: ${result.stderr.slice(0, 250)}`);
  return result.stdout;
}
function literal(value) { return `'${String(value).replaceAll("'", "''")}'`; }
async function balanceEvidence() {
  const ids = report.fixtureAccounts.map(literal).join(',');
  assert.equal(report.fixtureAccounts.length, 2, 'Only this run\'s two accounts may be observed.');
  return JSON.parse(await sql('settlement', `SELECT json_agg(row_to_json(a) ORDER BY a.id) FROM (SELECT id,balance_cents FROM accounts WHERE id IN (${ids})) a;`));
}
async function postingCount(id) {
  assert(report.fixtureOperations.includes(id), 'Only this run\'s operation may be observed.');
  return Number(await sql('settlement', `SELECT count(*) FROM ledger_postings WHERE operation_id=${literal(id)};`));
}
function remember(id) {
  assert.match(id, /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i);
  if (!report.fixtureOperations.includes(id)) report.fixtureOperations.push(id);
  return id;
}
async function completed(id) {
  const deadline = performance.now() + 60_000;
  while (performance.now() < deadline) {
    const response = await fetch(bases.processor + `/internal/operations/${id}`, { headers: { authorization: `Bearer ${secrets.PROCESSOR_APP_TOKEN}` }, signal: AbortSignal.timeout(5000) });
    if (response.status === 404) { await sleep(100); continue; }
    assert.equal(response.status, 200);
    const state = await response.json();
    assert(!['QUARANTINED', 'REJECTED'].includes(state.status), `${id}: unexpected ${state.status}: ${state.reason}`);
    if (state.status === 'COMPLETED' && state.outcomeRelayed === true) {
      assert.equal(state.postings.length, 2);
      assert.equal(state.postings.reduce((sum, leg) => sum + leg.amountCents, 0), 0);
      return state;
    }
    await sleep(100);
  }
  throw new Error(`No durable completion and audit relay within 60 seconds: ${id}`);
}
async function assertMovement(before, body) {
  const after = await balanceEvidence();
  assert.equal(before.length, 2, 'Both fixture accounts must exist before the operation.');
  assert.equal(after.length, 2, 'Both fixture accounts must still exist after the operation.');
  for (const account of after) {
    const previous = before.find(value => value.id === account.id);
    assert(previous, 'Fixture account disappeared.');
    assert.equal(account.balance_cents - previous.balance_cents, account.id === body.fromAccountId ? -body.amountCents : body.amountCents);
  }
  return after;
}
async function retryOnce(body, key, expectedId) {
  const retry = await http('app', '/api/transfers', { method: 'POST', body, key, expected: 202 });
  const id = remember(retry.id);
  if (expectedId) assert.equal(id, expectedId, 'Ambiguous response retry must return the original operation.');
  const expected = { ...body, operationId: id, idempotencyKey: key };
  assertLoadCompletion(expected, await completed(id));
  const duplicate = await http('app', '/api/transfers', { method: 'POST', body, key, expected: 202 });
  assert.equal(duplicate.id, id);
  assertLoadCompletion(expected, await completed(id));
  assert.equal(await postingCount(id), 2, 'Retries must not create additional postings.');
  return id;
}

try {
  secrets = JSON.parse(await readFile(path.join(root, '.local', 'secrets.json'), 'utf8'));
  await check('healthy-local-demo-and-fresh-funded-fixtures', async () => {
    await Promise.all(Object.keys(bases).map(service => http(service, '/health')));
    for (const label of ['A', 'B']) {
      const id = randomUUID();
      await http('app', '/api/accounts', { method: 'POST', body: { id, name: `recovery-${runId}-${label}`, initialBalanceCents: 0 }, key: idem(`account-${label}`), expected: 201 });
      report.fixtureAccounts.push(id);
    }
    const funding = await http('app', '/api/fundings', { method: 'POST', body: { toAccountId: report.fixtureAccounts[0], amountCents: 100_000, currency: 'USD' }, key: idem('fund'), expected: 202 });
    await completed(remember(funding.id));
    const balances = await balanceEvidence();
    assert.equal(balances.find(account => account.id === report.fixtureAccounts[0]).balance_cents, 100_000);
    assert.equal(balances.find(account => account.id === report.fixtureAccounts[1]).balance_cents, 0);
    return { balances, fundingOperationId: funding.id };
  });
  const pair = { fromAccountId: report.fixtureAccounts[0], toAccountId: report.fixtureAccounts[1], currency: 'USD' };

  await check('processor-outage-ambiguous-503-recovers-exactly-once', async () => {
    const body = { ...pair, amountCents: 17 }, key = idem('processor-outage');
    const before = await balanceEvidence();
    restoreRequired = true; // Set before stopping, including failures halfway through a lifecycle command.
    await script('recovery-stop-service.ps1', ['-Name', 'processor']);
    await stopped('processor');
    await http('app', '/api/transfers', { method: 'POST', body, key, expected: 503 });
    const signed = await http('key', `/v1/issuances?ledgerId=${ledger}&idempotencyKey=${encodeURIComponent(key)}`);
    const id = remember(signed.operation.id);
    assert.equal(signed.operation.idempotencyKey, key);
    assert.equal(signed.operation.fromAccountId, body.fromAccountId);
    assert.equal(signed.operation.toAccountId, body.toAccountId);
    assert.equal(signed.operation.amountMinor, body.amountCents);
    assert.equal(await sql('primary', `SELECT count(*) FROM transactions WHERE id=${literal(id)};`), '1');
    assert.equal(await sql('primary', `SELECT count(*) FROM transaction_outbox WHERE transaction_id=${literal(id)};`), '1');
    assert.equal(await postingCount(id), 0);
    assert.deepEqual(await balanceEvidence(), before);
    await script('start.ps1', ['-SkipBuild']);
    // Recovery must occur without a client re-publication; then retries stay idempotent.
    assertLoadCompletion({ ...body, operationId: id, idempotencyKey: key }, await completed(id));
    await retryOnce(body, key, id);
    return { operationId: id, initialHttpStatus: 503, publishedRowsDuringOutage: 1, postingCountDuringOutage: 0, finalPostingCount: 2, balances: await assertMovement(before, body) };
  });

  await check('key-service-outage-fails-closed-then-new-operation-retry-succeeds', async () => {
    const body = { ...pair, amountCents: 23 }, key = idem('key-outage');
    const before = await balanceEvidence();
    await script('recovery-stop-service.ps1', ['-Name', 'key-service']);
    await stopped('key');
    await http('app', '/api/transfers', { method: 'POST', body, key, expected: 503 });
    assert.equal(await sql('audit', `SELECT count(*) FROM issuance_receipts WHERE ledger_id=${literal(ledger)} AND idempotency_key=${literal(key)};`), '0');
    assert.equal(await sql('primary', `SELECT count(*) FROM transactions WHERE operation_json::jsonb->>'idempotencyKey'=${literal(key)};`), '0');
    assert.deepEqual(await balanceEvidence(), before);
    await script('start.ps1', ['-SkipBuild']);
    const id = await retryOnce(body, key);
    return { operationId: id, initialHttpStatus: 503, receiptsDuringOutage: 0, publishedRowsDuringOutage: 0, finalPostingCount: 2, balances: await assertMovement(before, body) };
  });
} catch (error) {
  report.error = redact(error.message);
  console.error(`FAIL ${report.error}`);
} finally {
  if (restoreRequired) {
    try {
      console.log('Restoring normal mode; preserving databases, fixtures, keys and evidence.');
      await script('stop.ps1', ['-KeepDatabases']);
      await script('start.ps1', ['-SkipBuild']);
      await Promise.all(Object.keys(bases).map(service => http(service, '/health')));
      await http('processor', `/internal/test/faults/${randomUUID()}`, { token: secrets.PROCESSOR_ADMIN_TOKEN, expected: 404 });
      report.normalRestart = 'PASSED';
    } catch (error) {
      report.normalRestart = 'FAILED';
      report.restorationError = redact(error.message);
      console.error(`Normal-mode restoration failed: ${report.restorationError}`);
    }
  }
  report.passed = !report.error && report.checks.length === 3 && report.checks.every(check => check.passed) && report.normalRestart === 'PASSED';
  report.endedAt = new Date().toISOString();
  await save();
  console.log(`Recovery ${report.passed ? 'PASSED' : 'FAILED / NOT COMPLETED'}. Evidence: ${path.join(output, 'report.json')}`);
  if (!report.passed) process.exitCode = 1;
}
