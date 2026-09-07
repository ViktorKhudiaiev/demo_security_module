import assert from 'node:assert/strict';
import test from 'node:test';
import { performance } from 'node:perf_hooks';
import path from 'node:path';
import os from 'node:os';
import { mkdtemp, readFile, unlink, rmdir } from 'node:fs/promises';
import { runRecoveryChild } from './recovery-child-process.mjs';

test('collects normal stdout/stderr and the child exit code', async () => {
  const result = await runRecoveryChild(process.execPath, ['-e', 'process.stdout.write("out"); process.stderr.write("err");']);
  assert.equal(result.code, 0);
  assert.equal(result.stdout, 'out');
  assert.equal(result.stderr, 'err');
  assert.equal(result.stdioDrainTimedOut, false);
});

test('returns a nonzero exit code for the caller to reject', async () => {
  const result = await runRecoveryChild(process.execPath, ['-e', 'process.exitCode = 17;']);
  assert.equal(result.code, 17);
});

test('does not wait for a harmless descendant that inherited output handles', async () => {
  // No service or socket is started. This temporary grandchild exits by itself
  // after two seconds and is never killed by the runner.
  const fixture = await mkdtemp(path.join(os.tmpdir(), 'recovery-child-test-'));
  const stdoutPath = path.join(fixture, 'stdout.log'), stderrPath = path.join(fixture, 'stderr.log');
  const quote = value => `'${value.replaceAll("'", "''")}'`;
  try {
    const program = process.platform === 'win32'
      ? `$ErrorActionPreference = 'Stop'; $recoveryFixture = Start-Process -FilePath ${quote(process.execPath)} -ArgumentList @('-e','"setTimeout(() => process.stdout.write(''done''), 2000)"') -WindowStyle Hidden -PassThru -RedirectStandardOutput ${quote(stdoutPath)} -RedirectStandardError ${quote(stderrPath)}; Write-Output 'launcher completed'`
      : `const { spawn } = require('node:child_process'); const descendant = spawn(process.execPath, ['-e', 'setTimeout(() => {}, 2000)'], { stdio: 'inherit' }); descendant.unref(); process.stdout.write('launcher completed');`;
    const executable = process.platform === 'win32' ? path.join(process.env.SystemRoot, 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe') : process.execPath;
    const parameters = process.platform === 'win32' ? ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', program] : ['-e', program];
    const started = performance.now();
    const result = await runRecoveryChild(executable, parameters, { timeoutMs: 5000, drainTimeoutMs: 100 });
    assert.equal(result.code, 0);
    assert.equal(result.stdout, 'launcher completed');
    assert.equal(result.stdioDrainTimedOut, true);
    assert(performance.now() - started < 1700, 'Must finish from launcher exit, before the descendant closes its pipes.');
  } finally {
    // The fixture child exits naturally; it is not a service and is never killed.
    // Only its two exact temporary log files and empty temporary directory go away.
    await new Promise(resolve => setTimeout(resolve, 2500));
    if (process.platform === 'win32') {
      assert.equal(await readFile(stdoutPath, 'utf8'), 'done');
      await unlink(stdoutPath);
      await unlink(stderrPath);
    }
    await rmdir(fixture);
  }
});

test('spawn failures reject without waiting for the execution timeout', async () => {
  await assert.rejects(runRecoveryChild('recovery-test-nonexistent-executable-123456789', [], { timeoutMs: 5000 }), /ENOENT/);
});

test('execution timeout rejects and stops only its own short-lived child', async () => {
  await assert.rejects(runRecoveryChild(process.execPath, ['-e', 'setTimeout(() => {}, 2000)'], { timeoutMs: 100 }), /execution timed out/);
});
