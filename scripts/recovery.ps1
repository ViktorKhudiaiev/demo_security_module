param()
. (Join-Path $PSScriptRoot 'common.ps1')
# Run only after verify-local.ps1 has finished. These real process outages are
# deliberately not concurrent with a presentation, load test or another harness.
$node = Get-DemoNode
$docker = Get-DemoDocker
& (Join-Path $PSScriptRoot 'recovery-process-tests.ps1')
if (-not $?) { throw 'Recovery process-ownership regressions failed.' }
& $node --test (Join-Path $PSScriptRoot 'recovery-child-process.test.mjs')
if ($LASTEXITCODE -ne 0) { throw 'Recovery child-process lifecycle regressions failed.' }
Write-Warning 'Recovery tests stop/restart this demo JVMs. Do not run concurrently with verify-local, load tests or a presentation.'
& $node (Join-Path $PSScriptRoot 'recovery.mjs') --docker $docker
if ($LASTEXITCODE -ne 0) { throw "Recovery tests or normal-mode restoration failed (exit $LASTEXITCODE); inspect the printed report path." }
