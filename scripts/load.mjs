// Fixed-arrival-rate local load test. A completion means protected settlement
// plus a durable audit outcome, not merely HTTP 202 or a primary-DB status flag.
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { performance } from 'node:perf_hooks';
import os from 'node:os';
import { assertLoadCompletion, claimLoadOperationId } from './load-evidence.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
function option(name, fallback) {
  const at = args.indexOf(`--${name}`);
  return at < 0 ? fallback : args[at + 1];
}
const rate = Number(option('rate', 20));
const seconds = Number(option('seconds', 120));
const drainSeconds = Number(option('drain-seconds', 30));
const accountCount = Number(option('accounts', 8));
const completionTolerance = Number(option('rate-tolerance', 0.02));
if (![rate, seconds, drainSeconds, accountCount, completionTolerance].every(Number.isFinite)
  || rate <= 0 || rate > 1000 || seconds < 10 || seconds > 3600
  || drainSeconds < 1 || accountCount < 2 || accountCount > 100
  || !Number.isInteger(accountCount) || completionTolerance < 0 || completionTolerance > 0.05) {
  throw new Error('Invalid settings: rate 0..1000, seconds 10..3600, accounts 2..100, tolerance 0..0.05.');
}
const secrets = JSON.parse(await readFile(path.join(root, '.local', 'secrets.json'), 'utf8'));
const appUrl = option('url', 'http://127.0.0.1:8080');
const processorUrl = option('processor-url', 'http://127.0.0.1:8082');
// Do not accidentally send local service credentials to a remote system.
for (const url of [appUrl, processorUrl]) {
  const parsed = new URL(url);
  if (parsed.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(parsed.hostname)) {
    throw new Error('This runner is restricted to local demo HTTP endpoints.');
  }
}
const runId = `${new Date().toISOString().replaceAll(':', '-').replaceAll('.', '-')}-${randomUUID().slice(0, 8)}`;
const outputDirectory = path.resolve(root, option('output', `.local/load-${runId}`));
const rootPrefix = root.toLowerCase() + path.sep;
if (!outputDirectory.toLowerCase().startsWith(rootPrefix)) throw new Error('Output directory must remain inside the project.');
await mkdir(outputDirectory, { recursive: true });
const delay = ms => new Promise(resolve => setTimeout(resolve, Math.max(0, ms)));
async function request(base, token, route, method = 'GET', body, idempotencyKey) {
  const headers = { authorization: `Bearer ${token}` };
  if (body !== undefined) headers['content-type'] = 'application/json';
  if (idempotencyKey) headers['idempotency-key'] = idempotencyKey;
  const response = await fetch(base + route, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(10_000),
  });
  const raw = await response.text();
  let data;
  try { data = raw ? JSON.parse(raw) : {}; } catch { throw new Error(`${route}: non-JSON HTTP ${response.status}`); }
  if (!response.ok) throw Object.assign(new Error(`${route}: HTTP ${response.status}; ${JSON.stringify(data).slice(0, 300)}`), { status: response.status });
  return data;
}
const app = (route, method, body, key) => request(appUrl, secrets.APP_API_TOKEN, route, method, body, key);
const processor = route => request(processorUrl, secrets.PROCESSOR_APP_TOKEN, route);
function operationId(response) {
  const id = response.operationId ?? response.id ?? response.transactionId;
  if (typeof id !== 'string') throw new Error('Submission response has no operation ID.');
  return id;
}
async function waitCompleted(id, timeoutSeconds = drainSeconds) {
  const deadline = performance.now() + timeoutSeconds * 1000;
  while (performance.now() < deadline) {
    let state;
    try { state = await processor(`/internal/operations/${id}`); }
    catch (error) {
      // A committed primary publication may not yet be discovered by the
      // protected inbox. Only this specific 404 is an expected polling state.
      if (error.status === 404) { await delay(100); continue; }
      throw error;
    }
    if (['REJECTED', 'QUARANTINED', 'FAILED'].includes(state.status)) {
      throw new Error(`Operation ${id}: ${state.status}; ${state.reason ?? ''}`);
    }
    if (state.status === 'COMPLETED' && state.outcomeRelayed === true) {
      if (!Array.isArray(state.postings) || state.postings.length !== 2) {
        throw new Error(`Operation ${id}: expected exactly two protected postings.`);
      }
      return state;
    }
    await delay(100);
  }
  throw new Error(`Operation ${id}: no COMPLETED + outcomeRelayed evidence within ${timeoutSeconds}s.`);
}

console.log(`Preparing isolated run ${runId}: ${rate} offered TPS for ${seconds}s, ${accountCount} new accounts.`);
const accounts = [];
const initialBalance = 1_000_000;
for (let n = 0; n < accountCount; n++) {
  const proposedId = randomUUID();
  const created = await app('/api/accounts', 'POST', { id: proposedId, name: `load-${runId}-${n}`, initialBalanceCents: 0 }, `load-account-${runId}-${n}`);
  const id = created.id ?? created.accountId;
  if (typeof id !== 'string') throw new Error('Account response has no ID.');
  accounts.push({ id, expectedBalance: initialBalance });
  const funded = await app('/api/fundings', 'POST', { toAccountId: id, amountCents: initialBalance, currency: 'USD' }, `load-funding-${runId}-${n}`);
  await waitCompleted(operationId(funded));
}

const expectedCount = Math.floor(rate * seconds);
const records = [];
const samples = [];
const seenOperationIds = new Set();
let settled = 0;
let completed = 0;
let submitted = 0;
let lastCompletion = 0;
const warmupSeconds = Math.min(10, seconds / 4);
const startedAt = new Date().toISOString();
const started = performance.now();
const sampler = setInterval(() => {
  const elapsed = (performance.now() - started) / 1000;
  const sample = { elapsedSeconds: elapsed, submitted, completed, settled, inFlight: submitted - settled };
  samples.push(sample);
  if (Math.floor(elapsed) % 10 === 0) console.log(`t=${elapsed.toFixed(1)}s submitted=${submitted} durableCompleted=${completed} inFlight=${sample.inFlight}`);
}, 1000);
async function execute(index, scheduled) {
  const sent = performance.now();
  const fromIndex = index % accountCount;
  const toIndex = (fromIndex + 1) % accountCount;
  const record = { index, scheduledMs: scheduled - started, sentMs: sent - started, fromAccountId: accounts[fromIndex].id, toAccountId: accounts[toIndex].id, amountCents: 1, currency: 'USD', idempotencyKey: `load-${runId}-${index}` };
  records.push(record);
  try {
    const response = await app('/api/transfers', 'POST', { fromAccountId: record.fromAccountId, toAccountId: record.toAccountId, amountCents: record.amountCents, currency: record.currency }, record.idempotencyKey);
    record.acceptedMs = performance.now() - started;
    record.operationId = operationId(response);
    record.operationId = claimLoadOperationId(seenOperationIds, record.operationId);
    const state = await waitCompleted(record.operationId);
    assertLoadCompletion(record, state);
    record.postings = state.postings;
    record.completedMs = performance.now() - started;
    record.latencyMs = record.completedMs - record.sentMs;
    record.status = 'COMPLETED';
    completed++;
    lastCompletion = Math.max(lastCompletion, record.completedMs);
    accounts[fromIndex].expectedBalance--;
    accounts[toIndex].expectedBalance++;
  } catch (error) {
    record.status = 'ERROR';
    record.error = error.message;
  } finally { settled++; }
}
const pending = [];
try {
  for (let index = 0; index < expectedCount; index++) {
    const scheduled = started + index * 1000 / rate;
    await delay(scheduled - performance.now());
    // No silent closed-loop rate reduction: excessive backlog fails this run.
    if (submitted - settled > Math.max(200, rate * 10)) throw new Error('Backlog safety limit exceeded.');
    submitted++;
    pending.push(execute(index, scheduled));
  }
  await Promise.all(pending);
} catch (error) {
  records.push({ status: 'RUN_ERROR', error: error.message });
  await Promise.allSettled(pending);
} finally { clearInterval(sampler); }

const balances = [];
for (const account of accounts) {
  try {
    const current = await processor(`/internal/accounts/${account.id}`);
    balances.push({ id: account.id, expected: account.expectedBalance, actual: current.balanceCents, matches: current.balanceCents === account.expectedBalance });
  } catch (error) { balances.push({ id: account.id, matches: false, error: error.message }); }
}
function percentile(values, quantile) {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted.length ? sorted[Math.ceil(quantile * sorted.length) - 1] : null;
}
const successful = records.filter(record => record.status === 'COMPLETED');
const errors = records.filter(record => record.status !== 'COMPLETED');
const steadyCount = successful.filter(record => record.completedMs >= warmupSeconds * 1000 && record.completedMs < seconds * 1000).length;
const steadyCompletedTps = steadyCount / (seconds - warmupSeconds);
const offeredTps = submitted / seconds;
const earlySamples = samples.filter(s => s.elapsedSeconds >= warmupSeconds && s.elapsedSeconds < seconds / 2);
const lateSamples = samples.filter(s => s.elapsedSeconds >= seconds / 2 && s.elapsedSeconds <= seconds);
const average = values => values.length ? values.reduce((sum, n) => sum + n, 0) / values.length : 0;
const backlogGrowth = average(lateSamples.map(s => s.inFlight)) - average(earlySamples.map(s => s.inFlight));
const criteria = {
  allScheduledSubmitted: submitted === expectedCount,
  uniqueOperationIds: seenOperationIds.size === expectedCount,
  allCompletedWithAudit: completed === expectedCount && errors.length === 0,
  balancesMatchProtectedLedger: balances.every(account => account.matches),
  steadyThroughput: steadyCompletedTps >= rate * (1 - completionTolerance),
  boundedBacklog: backlogGrowth <= Math.max(2, rate),
};
const report = {
  runId, startedAt, endedAt: new Date().toISOString(), nodeVersion: process.version, platform: process.platform,
  host: { cpuModel: os.cpus()[0]?.model ?? 'unknown', logicalCpus: os.availableParallelism(), totalMemoryBytes: os.totalmem(), osRelease: os.release() },
  configuration: { targetTps: rate, seconds, warmupSeconds, drainSeconds, accountCount, completionTolerance, acceptedTpsFloor: rate * (1 - completionTolerance) },
  expectedCount, submitted, completed, uniqueOperationCount: seenOperationIds.size, failures: errors.length, offeredTps, steadyCompletedTps,
  totalCompletedTpsIncludingDrain: lastCompletion > 0 ? completed * 1000 / lastCompletion : 0,
  drainTimeMs: Math.max(0, lastCompletion - seconds * 1000), backlogGrowth,
  latencyMs: { p50: percentile(successful.map(r => r.latencyMs), 0.50), p95: percentile(successful.map(r => r.latencyMs), 0.95), p99: percentile(successful.map(r => r.latencyMs), 0.99), max: successful.length ? Math.max(...successful.map(r => r.latencyMs)) : null },
  criteria, passed: Object.values(criteria).every(Boolean), balances, errors,
  limitations: ['Local software-key demo on one trusted host, not a production KMS/HSM benchmark.', 'Steady-state completion rate excludes the declared warmup and uses an explicit finite-window tolerance.', 'This test adds fresh accounts and simulated funding; it does not delete existing data.'],
};
await writeFile(path.join(outputDirectory, 'report.json'), JSON.stringify(report, null, 2));
await writeFile(path.join(outputDirectory, 'operations.json'), JSON.stringify(records, null, 2));
await writeFile(path.join(outputDirectory, 'samples.json'), JSON.stringify(samples, null, 2));
console.log(JSON.stringify(report, null, 2));
console.log(`Evidence: ${outputDirectory}`);
if (!report.passed) process.exitCode = 1;
