import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const helper = fileURLToPath(new URL('./notification-settings.ps1', import.meta.url));
const quote = text => "'" + text.replaceAll("'", "''") + "'";
async function settings(configuration, overrides = {}) {
  const directory = await mkdtemp(path.join(tmpdir(), 'integrity-notification-settings-'));
  try {
    if (configuration !== undefined) await writeFile(path.join(directory, 'notifications.json'), JSON.stringify(configuration));
    const env = Object.fromEntries(Object.entries(process.env).filter(([name]) => !name.startsWith('NOTIFICATION_')));
    const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command',
      `$ErrorActionPreference='Stop'; . ${quote(helper)}; Get-DemoNotificationEnvironment -LocalDirectory ${quote(directory)} | ConvertTo-Json -Compress`],
      { encoding: 'utf8', windowsHide: true, timeout: 15000, env: { ...env, ...overrides } });
    assert.ifError(result.error);
    return { code: result.status, output: result.stdout, error: result.stderr };
  } finally {
    // Remove only this test's unique, explicitly resolved temporary directory.
    assert.equal(path.dirname(directory), path.resolve(tmpdir()));
    assert(path.basename(directory).startsWith('integrity-notification-settings-'));
    await rm(directory, { recursive: true, force: true });
  }
}

test('private notification settings have capture-only defaults', { skip: process.platform !== 'win32' }, async () => {
  const result = await settings();
  assert.equal(result.code, 0, result.error);
  const values = JSON.parse(result.output);
  assert.equal(values.NOTIFICATION_MODE, 'mailpit');
  assert.equal(values.NOTIFICATION_RECIPIENTS, 'security@example.test');
  assert.equal(values.NOTIFICATION_SMTP_HOST, '127.0.0.1');
  assert.equal(values.NOTIFICATION_SMTP_PASSWORD, undefined);
});

test('environment overrides private notification settings', { skip: process.platform !== 'win32' }, async () => {
  const result = await settings({ NOTIFICATION_MODE: 'disabled', NOTIFICATION_RECIPIENTS: 'first@example.test' },
    { NOTIFICATION_MODE: 'mailpit', NOTIFICATION_RECIPIENTS: 'second@example.test' });
  assert.equal(result.code, 0, result.error);
  assert.equal(JSON.parse(result.output).NOTIFICATION_RECIPIENTS, 'second@example.test');
  assert.equal(JSON.parse(result.output).NOTIFICATION_MODE, 'mailpit');
});

test('invalid settings fail without printing supplied values', { skip: process.platform !== 'win32' }, async () => {
  for (const configuration of [[], null, { NOTIFICATION_MODE: 'invalid' }, { NOTIFICATION_MODE: 5 },
    { UNKNOWN_SETTING: 'sensitive-value-must-not-leak' }, { NOTIFICATION_MODE: 'smtp' }]) {
    const result = await settings(configuration);
    assert.notEqual(result.code, 0);
    assert(!result.output.includes('sensitive-value-must-not-leak'));
    assert(!result.error.includes('sensitive-value-must-not-leak'));
  }
});

test('real SMTP cannot inherit example recipients or loopback relay defaults', { skip: process.platform !== 'win32' }, async () => {
  const result = await settings({ NOTIFICATION_MODE: 'smtp', NOTIFICATION_SMTP_USERNAME: 'test-user',
    NOTIFICATION_SMTP_PASSWORD: 'test-secret-never-print' });
  assert.notEqual(result.code, 0);
  assert(!result.output.includes('test-secret-never-print'));
  assert(!result.error.includes('test-secret-never-print'));
});
