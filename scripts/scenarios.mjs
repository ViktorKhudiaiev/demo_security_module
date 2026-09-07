// Adversarial LOCAL-DEMO scenarios. All mutations target UUIDs created by this
// run, in the primary database only. No database/table/volume is ever dropped.
import assert from 'node:assert/strict';
import { readFile, mkdir, writeFile, access } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { performance } from 'node:perf_hooks';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const argumentsList = process.argv.slice(2);
function option(name, fallback) {
  const index = argumentsList.indexOf(`--${name}`);
  return index < 0 ? fallback : argumentsList[index + 1];
}
const runId = `${new Date().toISOString().replaceAll(':', '-').replaceAll('.', '-')}-${randomUUID().slice(0, 8)}`;
const output = path.resolve(root, option('output', `.local/scenarios-${runId}`));
assert(output.toLowerCase().startsWith(root.toLowerCase() + path.sep), 'Report directory must stay within this project.');
await mkdir(output, { recursive: true });
const report = { runId, startedAt: new Date().toISOString(), passed: false, checks: [], fixtureAccounts: [], fixtureOperations: [], limitations: [
  'Requires the local demo started with -EnableTestFaults; these hooks are disabled by default.',
  'The harness uses authorized observer/admin credentials for evidence and test hooks; the simulated attacker mutates only primary DB rows.',
  'All fixture data and attack evidence are retained; no unrelated rows or databases are changed.',
] };
let secrets = {};
const ownedOperations = new Set();
const ownedOutbox = new Set();
const appUrl = 'http://127.0.0.1:8080';
const keyUrl = 'http://127.0.0.1:8081';
const processorUrl = 'http://127.0.0.1:8082';
const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
const nowMicros = () => Date.now() * 1000;
const key = label => `scenario-${runId}-${label}`;
function redact(text) {
  let safe = String(text);
  for (const value of Object.values(secrets)) if (typeof value === 'string' && value.length > 12) safe = safe.split(value).join('[REDACTED]');
  return safe;
}
async function check(name, body) {
  const started = performance.now();
  try {
    const evidence = await body();
    report.checks.push({ name, passed: true, durationMs: performance.now() - started, evidence });
    console.log(`PASS ${name}`);
    return evidence;
  } catch (error) {
    report.checks.push({ name, passed: false, durationMs: performance.now() - started, error: redact(error.message) });
    console.error(`FAIL ${name}: ${redact(error.message)}`);
    throw error;
  }
}
async function http(base, token, route, { method = 'GET', body, idempotencyKey, expectedStatus } = {}) {
  const headers = {};
  if (token) headers.authorization = `Bearer ${token}`;
  if (body !== undefined) headers['content-type'] = 'application/json';
  if (idempotencyKey) headers['idempotency-key'] = idempotencyKey;
  const response = await fetch(base + route, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(12_000) });
  const raw = await response.text();
  let data;
  try { data = raw ? JSON.parse(raw) : {}; } catch { data = { nonJsonResponse: true }; }
  if (expectedStatus !== undefined) {
    assert.equal(response.status, expectedStatus, `${route}: expected HTTP ${expectedStatus}, received ${response.status}`);
  } else if (!response.ok) {
    throw new Error(`${route}: HTTP ${response.status}; ${redact(JSON.stringify(data)).slice(0, 300)}`);
  }
  return { status: response.status, data };
}
const app = (route, options) => http(appUrl, secrets.APP_API_TOKEN, route, options).then(result => result.data);
const processor = (route, options) => http(processorUrl, secrets.PROCESSOR_APP_TOKEN, route, options).then(result => result.data);
const admin = (route, options) => http(processorUrl, secrets.PROCESSOR_ADMIN_TOKEN, route, options).then(result => result.data);
const keys = (route, options) => http(keyUrl, secrets.KEY_WRITER_TOKEN, route, options).then(result => result.data);
async function until(description, poll, timeoutMs = 30_000, intervalMs = 100) {
  const deadline = performance.now() + timeoutMs;
  while (performance.now() < deadline) {
    const result = await poll();
    if (result) return result;
    await delay(intervalMs);
  }
  throw new Error(`Timeout: ${description}`);
}
async function operationState(id) {
  const response = await http(processorUrl, secrets.PROCESSOR_APP_TOKEN, `/internal/operations/${id}`, { expectedStatus: undefined }).catch(error => {
    if (error.message.includes('HTTP 404')) return null;
    throw error;
  });
  return response?.data ?? null;
}
async function terminal(id, expected = 'COMPLETED', timeoutMs = 30_000) {
  return until(`${id} -> durable ${expected}`, async () => {
    const state = await operationState(id);
    if (!state || !['COMPLETED', 'QUARANTINED', 'REJECTED'].includes(state.status)) return false;
    assert.equal(state.status, expected, `Unexpected terminal status for ${id}: ${state.reason ?? ''}`);
    if (state.outcomeRelayed !== true) return false;
    assert.equal(state.postings.length, expected === 'COMPLETED' ? 2 : 0, `${id}: wrong posting count`);
    if (expected === 'COMPLETED') assert.equal(state.postings.reduce((sum, posting) => sum + posting.amountCents, 0), 0, 'Double-entry postings must sum to zero.');
    return state;
  }, timeoutMs);
}
function remember(id) {
  assert.match(id, /^[0-9a-f-]{36}$/i);
  ownedOperations.add(id);
  if (!report.fixtureOperations.includes(id)) report.fixtureOperations.push(id);
  return id;
}
function requireOwned(id) { assert(ownedOperations.has(id), 'Refusing to mutate a non-fixture operation.'); }
function literal(value) { return `'${String(value).replaceAll("'", "''")}'`; }
let dockerPath;
async function findDocker() {
  if (process.env.DEMO_DOCKER_PATH) { await access(process.env.DEMO_DOCKER_PATH); return process.env.DEMO_DOCKER_PATH; }
  const candidates = [
    process.env.LOCALAPPDATA ? path.join(process.env.LOCALAPPDATA, 'Programs', 'DockerDesktop', 'resources', 'bin', 'docker.exe') : null,
    'C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe',
  ].filter(Boolean);
  for (const candidate of candidates) { try { await access(candidate); return candidate; } catch {} }
  throw new Error('Installed Docker CLI not found; set DEMO_DOCKER_PATH to its existing executable.');
}
function runDocker(args, input = '') {
  return new Promise((resolve, reject) => {
    const child = spawn(dockerPath, args, { cwd: root, windowsHide: true, shell: false, stdio: ['pipe', 'pipe', 'pipe'] });
    let stdout = '', stderr = '';
    const timeout = setTimeout(() => { child.kill(); reject(new Error('Docker CLI timed out; no alternate launcher was attempted.')); }, 30_000);
    child.stdout.on('data', chunk => { stdout += chunk; });
    child.stderr.on('data', chunk => { stderr += chunk; });
    child.on('error', error => { clearTimeout(timeout); reject(new Error(`Docker CLI could not run: ${redact(error.message)}`)); });
    child.on('close', code => { clearTimeout(timeout); resolve({ code, stdout: stdout.trim(), stderr: redact(stderr.trim()) }); });
    child.stdin.on('error', () => {});
    child.stdin.end(input);
  });
}
async function primarySql(sql, { expectedFailure = false } = {}) {
  assert(!/\b(DROP|TRUNCATE|ALTER|GRANT|REVOKE|COPY)\b/i.test(sql), 'Destructive/privilege SQL is prohibited in this harness.');
  const result = await runDocker(['compose', '--project-name', 'secure-integrity-demo', '--project-directory', root,
    '--env-file', path.join(root, '.local', 'compose.env'), '-f', path.join(root, 'compose.yaml'),
    'exec', '-T', 'primary-db', 'psql', '-X', '-q', '-t', '-A', '-1', '-v', 'ON_ERROR_STOP=1', '-U', 'primary_admin', '-d', 'primary_db'], sql);
  if (expectedFailure) {
    assert.notEqual(result.code, 0, 'Expected the restricted runtime role to be denied.');
    assert.match(result.stderr, /permission denied/i, 'Expected a permission failure, not a SQL syntax/configuration error.');
  } else if (result.code !== 0) throw new Error(`Primary fixture SQL failed: ${result.stderr.slice(0, 500)}`);
  return result.stdout;
}
function eventSql(id) {
  requireOwned(id);
  const eventId = randomUUID();
  ownedOutbox.add(eventId);
  return { eventId, sql: `INSERT INTO transaction_outbox(id,transaction_id,event_type,created_at_micros) VALUES(${literal(eventId)},${literal(id)},'SCENARIO_READY',${nowMicros()});` };
}
async function publish(signed, { includeOutbox = true } = {}) {
  const id = signed.operation.id;
  requireOwned(id);
  const insert = `INSERT INTO transactions(id,operation_json,key_id,content_hash,mac,created_at_micros) VALUES(${literal(id)},${literal(JSON.stringify(signed.operation))},${literal(signed.keyId)},${literal(signed.contentHash)},${literal(signed.mac)},${signed.operation.createdAtMicros});`;
  await primarySql(insert + (includeOutbox ? '\n' + eventSql(id).sql : ''));
}
async function changeAmount(id, amountMinor) {
  requireOwned(id);
  assert(Number.isSafeInteger(amountMinor) && amountMinor > 0);
  await primarySql(`UPDATE transactions SET operation_json=jsonb_set(operation_json::jsonb,'{amountMinor}',to_jsonb(${amountMinor}::bigint))::text WHERE id=${literal(id)};`);
}
async function deleteSource(id) {
  requireOwned(id);
  await primarySql(`DELETE FROM transactions WHERE id=${literal(id)};`);
}
async function configureFault(id, pauseAfterVerifyMillis = 0, failAfterDebit = false) {
  requireOwned(id);
  return admin('/internal/test/faults', { method: 'POST', body: { operationId: id, pauseAfterVerifyMillis, failAfterDebit } });
}
async function waitPaused(id) {
  return until(`processor pause after successful verification of ${id}`, async () => (await admin(`/internal/test/faults/${id}`)).paused, 15_000, 50);
}
async function balances(accounts) { return Promise.all(accounts.map(id => processor(`/internal/accounts/${id}`).then(account => ({ id, balanceCents: account.balanceCents })))); }
async function incident(id) {
  return until(`independent integrity incident for ${id}`, async () => {
    const events = await processor(`/internal/audit?operationId=${id}`);
    return events.find(event => (event.event_type ?? event.eventType) === 'INTEGRITY_INCIDENT');
  }, 60_000);
}

try {
  secrets = JSON.parse(await readFile(path.join(root, '.local', 'secrets.json'), 'utf8'));
  dockerPath = await findDocker();
  await check('prerequisites-and-scoped-primary-database', async () => {
    const version = await runDocker(['version', '--format', '{{.Server.Version}}']);
    assert.equal(version.code, 0, 'Docker daemon unavailable.');
    const identity = await primarySql("SELECT current_database() || '|' || current_user;");
    assert.equal(identity, 'primary_db|primary_admin');
    await Promise.all([http(appUrl, null, '/health'), http(keyUrl, null, '/health'), http(processorUrl, null, '/health')]);
    await admin(`/internal/test/faults/${randomUUID()}`);
    return { dockerServerVersion: version.stdout, database: identity, testFaultsEnabled: true };
  });

  await check('missing-app-credential-is-401', async () => {
    await http(appUrl, null, '/api/accounts', { method: 'POST', body: { id: randomUUID(), name: 'unauthorized', initialBalanceCents: 0 }, expectedStatus: 401 });
    return { status: 401 };
  });
  await check('service-credentials-have-separate-authority', async () => {
    await http(keyUrl, secrets.KEY_VERIFIER_TOKEN, '/v1/mac/issue', { method: 'POST', body: {}, expectedStatus: 403 });
    await http(keyUrl, secrets.KEY_WRITER_TOKEN, '/v1/mac/verify', { method: 'POST', body: {}, expectedStatus: 403 });
    await http(keyUrl, secrets.KEY_SIGNER_TOKEN, '/v1/mac/issue', { method: 'POST', body: {}, expectedStatus: 403 });
    await http(processorUrl, secrets.PROCESSOR_APP_TOKEN, '/internal/test/faults', { method: 'POST', body: {}, expectedStatus: 401 });
    return { verifierCannotIssue: true, writerCannotVerify: true, checkpointSignerCannotIssue: true, appCannotConfigureTestFaults: true };
  });

  const accountIds = [];
  await check('zero-balance-registration-and-protected-funding', async () => {
    for (const label of ['A', 'B']) {
      const account = await app('/api/accounts', { method: 'POST', body: { id: randomUUID(), name: `scenario-${runId}-${label}`, initialBalanceCents: 0 }, idempotencyKey: key(`account-${label}`) });
      accountIds.push(account.id);
      report.fixtureAccounts.push(account.id);
      assert.equal((await processor(`/internal/accounts/${account.id}`)).balanceCents, 0);
      const funding = await app('/api/fundings', { method: 'POST', body: { toAccountId: account.id, amountCents: 100_000, currency: 'USD' }, idempotencyKey: key(`fund-${label}`) });
      await terminal(remember(funding.id));
    }
    const funded = await balances(accountIds);
    assert(funded.every(account => account.balanceCents === 100_000));
    return { accounts: funded };
  });
  const [fromAccountId, toAccountId] = accountIds;
  const transferBody = { fromAccountId, toAccountId, amountCents: 100, currency: 'USD' };
  let completedId;
  await check('transfer-and-idempotent-retry-have-one-financial-effect', async () => {
    const first = await app('/api/transfers', { method: 'POST', body: transferBody, idempotencyKey: key('normal-transfer') });
    completedId = remember(first.id);
    await terminal(completedId);
    const retry = await app('/api/transfers', { method: 'POST', body: transferBody, idempotencyKey: key('normal-transfer') });
    assert.equal(retry.id, completedId);
    const state = await terminal(completedId);
    const actual = await balances(accountIds);
    assert.deepEqual(actual.map(account => account.balanceCents), [99_900, 100_100]);
    return { operationId: completedId, postingCount: state.postings.length, balances: actual };
  });
  await check('same-idempotency-key-different-amount-is-409', async () => {
    await http(appUrl, secrets.APP_API_TOKEN, '/api/transfers', { method: 'POST', body: { ...transferBody, amountCents: 101 }, idempotencyKey: key('normal-transfer'), expectedStatus: 409 });
    return { status: 409 };
  });
  await check('twenty-concurrent-retries-return-one-operation', async () => {
    const before = await balances(accountIds);
    const responses = await Promise.all(Array.from({ length: 20 }, () => app('/api/transfers', { method: 'POST', body: { ...transferBody, amountCents: 7 }, idempotencyKey: key('concurrent') })));
    const ids = [...new Set(responses.map(response => response.id))];
    assert.equal(ids.length, 1);
    await terminal(remember(ids[0]));
    const after = await balances(accountIds);
    assert.deepEqual(after.map((value, index) => value.balanceCents - before[index].balanceCents), [-7, 7]);
    return { operationId: ids[0], concurrentRequests: 20, distinctOperations: ids.length };
  });
  await check('primary-runtime-role-cannot-edit-operation-content', async () => {
    requireOwned(completedId);
    await primarySql(`SET LOCAL ROLE primary_app; UPDATE transactions SET content_hash=content_hash WHERE id=${literal(completedId)};`, { expectedFailure: true });
    return { permissionDenied: true };
  });

  const original = await keys(`/v1/issuances/${completedId}`);
  await check('forged-primary-row-with-fake-mac-is-quarantined', async () => {
    const id = remember(randomUUID());
    const before = await balances(accountIds);
    const forged = { ...original, operation: { ...original.operation, id, amountMinor: 999, createdAtMicros: nowMicros(), idempotencyKey: key('forged') }, mac: '0'.repeat(64) };
    await publish(forged);
    const state = await terminal(id, 'QUARANTINED');
    assert.deepEqual(await balances(accountIds), before);
    return { operationId: id, reason: state.reason, postingCount: state.postings.length };
  });

  function newOperation(label, amountMinor = 13) {
    const id = remember(randomUUID());
    return { id, schemaVersion: 1, ledgerId: '00000000-0000-0000-0000-000000000010', type: 'TRANSFER', fromAccountId, toAccountId, amountMinor, currency: 'USD', createdAtMicros: nowMicros(), relatedOperationId: null, idempotencyKey: key(label) };
  }
  await check('issued-record-missing-before-publication-is-detected', async () => {
    const operation = newOperation('missing');
    const before = await balances(accountIds);
    // No source row/outbox is published: receipt reconciliation must discover
    // the missing operation without trusting a primary-DB notification.
    await keys('/v1/mac/issue', { method: 'POST', body: operation });
    // No outbox: detection requires a bounded full-history traversal, not the fast delivery path.
    // Retained history grows across runs; this is a 120-second test budget, not a detection SLA.
    const state = await terminal(operation.id, 'QUARANTINED', 120_000);
    await incident(operation.id);
    assert.deepEqual(await balances(accountIds), before);
    return { operationId: operation.id, reason: state.reason, sourcePublished: false, postingCount: 0, detectionBudgetMs: 120_000 };
  });
  await check('tamper-after-verification-before-use-is-quarantined', async () => {
    const operation = newOperation('toctou');
    const before = await balances(accountIds);
    await configureFault(operation.id, 5000, false);
    const signed = await keys('/v1/mac/issue', { method: 'POST', body: operation });
    await publish(signed);
    await waitPaused(operation.id);
    await changeAmount(operation.id, 9999);
    const state = await terminal(operation.id, 'QUARANTINED');
    assert.deepEqual(await balances(accountIds), before);
    return { operationId: operation.id, pausedAfterSuccessfulVerification: true, reason: state.reason, postingCount: 0 };
  });
  await check('delete-after-verification-is-quarantined', async () => {
    const operation = newOperation('delete');
    const before = await balances(accountIds);
    await configureFault(operation.id, 5000, false);
    await publish(await keys('/v1/mac/issue', { method: 'POST', body: operation }));
    await waitPaused(operation.id);
    await deleteSource(operation.id);
    const state = await terminal(operation.id, 'QUARANTINED');
    assert.deepEqual(await balances(accountIds), before);
    return { operationId: operation.id, deletedRowsScope: 'one fixture primary transaction', reason: state.reason, postingCount: 0 };
  });
  await check('failure-after-debit-rolls-back-and-retry-settles-once', async () => {
    const operation = newOperation('rollback', 19);
    const before = await balances(accountIds);
    await configureFault(operation.id, 2000, true);
    await publish(await keys('/v1/mac/issue', { method: 'POST', body: operation }));
    await waitPaused(operation.id);
    const retry = await until('injected debit failure and pending retry evidence', async () => {
      const state = await operationState(operation.id);
      return state?.status === 'PENDING' && String(state.reason).includes('Injected failure after debit') ? state : false;
    }, 15_000, 25);
    assert.equal(retry.postings.length, 0);
    assert.deepEqual(await balances(accountIds), before);
    const completed = await terminal(operation.id);
    const after = await balances(accountIds);
    assert.deepEqual(after.map((value, index) => value.balanceCents - before[index].balanceCents), [-19, 19]);
    return { operationId: operation.id, publicStatusDuringRetry: retry.status, retryReason: retry.reason, postingCountDuringRetry: retry.postings.length, finalPostingCount: completed.postings.length };
  });
  await check('replayed-primary-outbox-does-not-repeat-settlement', async () => {
    const before = await balances(accountIds);
    const event = eventSql(completedId);
    await primarySql(event.sql);
    await until('replayed outbox observed by durable inbox', async () => (await primarySql(`SELECT processed_at_micros IS NOT NULL FROM transaction_outbox WHERE id=${literal(event.eventId)};`)) === 't');
    const state = await terminal(completedId);
    assert.deepEqual(await balances(accountIds), before);
    return { operationId: completedId, replayEventId: event.eventId, postingCount: state.postings.length };
  });
  await check('completed-source-tampering-raises-incident-without-another-debit', async () => {
    const before = await balances(accountIds);
    await changeAmount(completedId, 8888);
    const auditEvent = await incident(completedId);
    const state = await terminal(completedId);
    assert.deepEqual(await balances(accountIds), before);
    return { operationId: completedId, postingCount: state.postings.length, incidentEventId: auditEvent.event_id ?? auditEvent.eventId, incidentType: 'INTEGRITY_INCIDENT' };
  });
  report.passed = report.checks.every(item => item.passed);
} catch (error) {
  report.error = redact(error.message);
  if (report.checks.length === 0) report.checks.push({ name: 'prerequisites', passed: false, error: report.error });
  process.exitCode = 1;
} finally {
  report.endedAt = new Date().toISOString();
  await writeFile(path.join(output, 'report.json'), JSON.stringify(report, null, 2));
  console.log(`Scenarios ${report.passed ? 'PASSED' : 'FAILED / NOT COMPLETED'}; ${report.checks.filter(item => item.passed).length}/${report.checks.length} passed.`);
  console.log(`Evidence: ${output}`);
  if (report.fixtureOperations.length) console.log('Retained fixture rows/evidence. The deletion test removed only its own source row; the issuance receipt and independent audit remain.');
}
