param([string]$ResumeReport)
# Explicit, non-destructive maintenance migration from the previous three-DB demo.
. (Join-Path $PSScriptRoot 'common.ps1')
$null = Initialize-DemoSecrets
& (Join-Path $PSScriptRoot 'stop.ps1') -KeepDatabases
if (-not $?) { throw 'Could not stop owned Java services.' }
$migrationNode = Get-DemoNode
$migrationDocker = Get-DemoDocker
$migrationArguments = @('--docker', $migrationDocker)
if ($ResumeReport) { $migrationArguments += @('--resume-report', $ResumeReport) }
& $migrationNode (Join-Path $PSScriptRoot 'migrate-protected-db.mjs') @migrationArguments
if ($LASTEXITCODE -ne 0) { throw 'Migration did not finish. Services remain stopped; no volumes were deleted.' }
