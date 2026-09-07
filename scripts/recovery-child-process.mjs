import { spawn } from 'node:child_process';

// A child can EXIT while a background descendant still holds its stdout/stderr
// pipe handles. In particular, PowerShell Start-Process can leave such handles
// in the demo JVMs. Waiting indefinitely for 'close' then mistakes a successful
// start command for a hung command. Bound output draining after the child exits;
// close only our read ends, never kill the descendant/background service.
export function runRecoveryChild(executable, parameters, {
  cwd, input = '', timeoutMs = 300_000, drainTimeoutMs = 250,
} = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(executable, parameters, { cwd, shell: false, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    let stdout = '', stderr = '', finished = false, exited = false;
    let drainTimer;
    function finish(error, result) {
      if (finished) return;
      finished = true;
      clearTimeout(processTimer);
      clearTimeout(drainTimer);
      child.stdin.destroy();
      child.stdout.destroy();
      child.stderr.destroy();
      if (error) reject(error);
      else resolve({ ...result, stdout: stdout.trim(), stderr: stderr.trim() });
    }
    const processTimer = setTimeout(() => {
      // Never send a signal to an already exited child just because an inherited
      // pipe is still open. Its lifetime and the output drain have separate timers.
      if (exited) return;
      child.kill();
      finish(new Error('Child process execution timed out; normal-mode restoration will still be attempted.'));
    }, timeoutMs);
    child.stdout.on('data', chunk => { stdout += chunk; });
    child.stderr.on('data', chunk => { stderr += chunk; });
    child.stdin.on('error', () => {});
    child.stdout.on('error', error => finish(error));
    child.stderr.on('error', error => finish(error));
    child.on('error', error => finish(error));
    child.on('exit', (code, signal) => {
      exited = true;
      clearTimeout(processTimer);
      // Usually 'close' follows immediately, once all data events were consumed.
      // Only the inherited-handle case needs this bounded fallback.
      drainTimer = setTimeout(() => finish(null, { code, signal, stdioDrainTimedOut: true }), drainTimeoutMs);
    });
    child.on('close', (code, signal) => finish(null, { code, signal, stdioDrainTimedOut: false }));
    child.stdin.end(input);
  });
}
