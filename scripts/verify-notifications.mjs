// Local integration verification only. Retains its own fixtures, audit evidence and captured mail.
import fs from 'node:fs/promises';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {LiveBridge, ROOT, assess, validateManifest} from '../docs/live/bridge.mjs';

const runId = `${new Date().toISOString().replaceAll(':', '-')}-${randomUUID().slice(0, 8)}`;
const directory = path.join(ROOT, '.local', `notification-verification-${runId}`);
const report = {version: 1, runId, startedAt: new Date().toISOString(), passed: false,
  deliveryScope: 'Local Mailpit capture only; no external email delivery.', checks: {}};
const bridge = new LiveBridge();
const sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
let phase = 'preflight';
class CheckFailure extends Error {}
function check(condition, message) { if (!condition) throw new CheckFailure(message); }
async function getJson(url, token) {
  const response = await fetch(url, {headers: token ? {authorization: `Bearer ${token}`} : {},
    signal: AbortSignal.timeout(10000)});
  check(response.ok, 'A required local endpoint did not accept the request.');
  return response.json();
}
async function docker(args) {
  const {stdout} = await promisify(execFile)(bridge.docker, args,
    {cwd: ROOT, windowsHide: true, timeout: 12000, maxBuffer: 256000});
  return stdout.trim();
}
async function capturePreflight() {
  const compose = ['compose', '--project-name', 'secure-integrity-demo', '--project-directory', ROOT,
    '--env-file', path.join(ROOT, '.local/compose.env'), '-f', path.join(ROOT, 'compose.yaml')];
  const id = await docker([...compose, '--profile', 'mail', 'ps', '-q', 'mailpit']);
  check(/^[0-9a-f]{12,64}$/.test(id), 'Exactly one running Mailpit capture container is required.');
  const [container] = JSON.parse(await docker(['inspect', id]));
  const environment = container.Config.Env ?? [];
  const command = [...(container.Config.Entrypoint ?? []), ...(container.Config.Cmd ?? [])].join(' ');
  check(container.State.Running && container.Config.Image.startsWith('axllent/mailpit:'),
    'The capture container is not a running Mailpit image.');
  check(!environment.some(value => /^MP_.*(?:RELAY|FORWARD|CONFIG)/i.test(value.split('=')[0]))
    && !/relay|forward|config/i.test(command), 'Mailpit relay, forwarding or custom configuration is not permitted.');
  const bindings = container.HostConfig.PortBindings;
  for (const port of ['1025/tcp', '8025/tcp']) {
    check(bindings?.[port]?.length === 1 && bindings[port][0].HostIp === '127.0.0.1'
      && bindings[port][0].HostPort === port.split('/')[0], 'Mailpit must use the standard loopback-only ports.');
  }
  check((container.Mounts ?? []).every(mount => mount.Destination === '/data'),
    'Unexpected Mailpit configuration mount; no fixtures were created.');
  const info = await getJson('http://127.0.0.1:8025/api/v1/info');
  check(typeof info.Version === 'string', 'Mailpit did not return its API version.');
  return {running: true, loopbackOnly: true, relayOrForwardConfigured: false, version: info.Version};
}
async function notificationRows() {
  const manifest = validateManifest(bridge.manifest);
  const ids = [manifest.fundingId, manifest.validId, manifest.forgedId].filter(Boolean);
  return JSON.parse(await bridge.dockerSql('audit', `SELECT COALESCE(json_agg(n),'[]'::json) FROM (
    SELECT operation_id,event_id,event_seq,reason,attempts,delivered_at_micros FROM notification_outbox
    WHERE operation_id IN (${ids.map(id => `'${id}'`).join(',')}) ORDER BY operation_id) n;`));
}
async function messagesFor(row) {
  const messageId = `integrity-${row.event_id}@record-integrity.invalid`;
  const result = await getJson(`http://127.0.0.1:8025/api/v1/search?query=${encodeURIComponent(`message-id:${messageId}`)}`);
  check(Array.isArray(result.messages), 'Mailpit search returned an unexpected response.');
  return result.messages;
}
async function awaitNotification(operationId, reasons) {
  const deadline = Date.now() + 120000;
  while (Date.now() < deadline) {
    const rows = (await notificationRows()).filter(row => row.operation_id === operationId);
    check(rows.length <= 1, 'An operation has multiple notification outbox rows.');
    if (rows.length === 1 && rows[0].delivered_at_micros != null) {
      const row = rows[0];
      check(reasons.includes(row.reason), 'Notification reason is not the expected safe description.');
      const messages = await messagesFor(row);
      if (messages.length) {
        check(messages.length === 1, 'Multiple SMTP captures were observed during this normal-delivery test.');
        const message = await getJson(`http://127.0.0.1:8025/api/v1/message/${encodeURIComponent(messages[0].ID)}`);
        const text = message.Text;
        check(typeof text === 'string' && text.includes(`Operation ID: ${operationId}`)
          && text.includes(`Event ID: ${row.event_id}`) && text.includes(`Reason: ${row.reason}`),
          'Captured email does not identify the protected incident correctly.');
        check(String(message.MessageID).replace(/^<|>$/g, '') === `integrity-${row.event_id}@record-integrity.invalid`,
          'Captured email does not use the stable incident Message-ID.');
        check(!message.HTML && !(message.Attachments ?? []).length && !(message.Inline ?? []).length
          && !/amountMinor|amountCents|operation_json|signed_json|HMAC|contentHash|keyId|\b(?:2500|97500|100000|USD)\b|[a-f0-9]{64}/i.test(text)
          && !text.includes(bridge.manifest.alice) && !text.includes(bridge.manifest.bob)
          && !Object.values(bridge.secrets).filter(value => typeof value === 'string' && value.length >= 20).some(value => text.includes(value)),
          'Captured email contains unexpected payload, account, amount or secret material.');
        check(text.includes('not proof of fraud') && text.includes('after settlement') && text.includes('at-least-once'),
          'Captured email is missing its incident and delivery limitations.');
        return {row, capturedMessages: messages.length, safeBodyVerified: true};
      }
    }
    await sleep(1000);
  }
  throw new CheckFailure('No accepted and captured incident notification arrived within 120 seconds.');
}
function protectedState(snapshot) {
  return JSON.stringify({accounts: snapshot.settlement.accounts, results: snapshot.settlement.results
    .filter(row => row.operation_id !== bridge.manifest.forgedId).sort((a, b) => a.operation_id.localeCompare(b.operation_id)),
  postings: snapshot.settlement.postings, journal: snapshot.settlement.journal
    .sort((a, b) => a.operation_id.localeCompare(b.operation_id))});
}

try {
  await fs.mkdir(directory, {recursive: true});
  await bridge.load();
  bridge.statePath = path.join(directory, 'fixture.json');
  bridge.manifest = null;
  const status = await getJson('http://127.0.0.1:8082/internal/notifications/status', bridge.secrets.PROCESSOR_ADMIN_TOKEN);
  check(status.mode === 'mailpit' && status.enabled === true, 'Notifications must be enabled in local Mailpit mode; SMTP mode is refused.');
  check((await bridge.health()).every(service => service.ready), 'All three local services must be healthy before fixture creation.');
  report.checks.preflight = {processorMode: status.mode, servicesHealthy: true, mailpit: await capturePreflight()};

  phase = 'valid operation';
  await bridge.setup();
  await bridge.valid();
  const before = await bridge.readSnapshot();
  check(assess(before, bridge.manifest).valid.passed, 'The valid fixture did not complete with matching financial evidence.');
  check((await notificationRows()).length === 0, 'A valid fixture unexpectedly generated an incident notification.');
  const protectedBefore = protectedState(before);
  report.checks.valid = {completed: true, notificationRows: 0};

  phase = 'forged operation';
  await bridge.forged();
  const first = await awaitNotification(bridge.manifest.forgedId, ['No independent issuance receipt']);
  const after = await bridge.readSnapshot();
  check(assess(after, bridge.manifest).forged.passed && protectedState(after) === protectedBefore,
    'The forged fixture was not quarantined without changing protected financial state.');
  report.checks.forged = {quarantined: true, protectedFinancialStateUnchanged: true, ...first};

  phase = 'repeat forged operation';
  await bridge.forged();
  await sleep(5000);
  const repeated = (await notificationRows()).filter(row => row.operation_id === bridge.manifest.forgedId);
  check(repeated.length === 1 && JSON.stringify(repeated[0]) === JSON.stringify(first.row), 'A repeated observation changed the first notification row.');
  check((await messagesFor(first.row)).length === 1 && protectedState(await bridge.readSnapshot()) === protectedBefore,
    'Repeated observation created an extra captured email or changed financial state.');
  report.checks.repeated = {sameNotificationRow: true, capturedMessages: 1, protectedFinancialStateUnchanged: true,
    observationSeconds: 5, limitation: 'Normal-delivery observation only; SMTP remains at-least-once, not exactly-once.'};

  phase = 'post-settlement tampering';
  const validId = validateManifest(bridge.manifest).validId;
  await bridge.dockerSql('primary', `UPDATE transactions SET mac=repeat('0',64) WHERE id='${validId}';`, false);
  const tampered = await awaitNotification(validId, ['Primary record differs from independent issuance receipt']);
  check(protectedState(await bridge.readSnapshot()) === protectedBefore,
    'Post-settlement source tampering changed the original protected financial result.');
  report.checks.postSettlementTampering = {detected: true, originalCompletedResultPreserved: true, ...tampered};
  report.passed = true;
} catch (error) {
  report.failure = {phase, message: error instanceof CheckFailure ? error.message : 'Local dependency or fixture action failed; inspect service status before retrying.'};
  process.exitCode = 1;
} finally {
  report.finishedAt = new Date().toISOString();
  report.retention = 'Fresh fixture manifest, financial evidence, source tamper and captured emails are retained. No data was deleted.';
  await fs.mkdir(directory, {recursive: true});
  await fs.writeFile(path.join(directory, 'report.json'), JSON.stringify(report, null, 2) + '\n');
  console.log(`Notification verification ${report.passed ? 'PASSED' : 'FAILED'}: ${path.join(directory, 'report.json')}`);
}
