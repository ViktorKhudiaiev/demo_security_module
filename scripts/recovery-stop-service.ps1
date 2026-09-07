param([Parameter(Mandatory)][ValidateSet('processor', 'key-service')][string]$Name)
. (Join-Path $PSScriptRoot 'common.ps1')

# Only a recorded JVM from this checkout may be stopped. No process-name,
# port-based or container-wide kills; never accept an arbitrary PID or JAR.
$statePath = Join-Path $script:DemoLocal 'processes.json'
if (-not (Test-Path -LiteralPath $statePath -PathType Leaf)) { throw 'No tracked demo process state; start the demo first.' }
# Windows PowerShell 5.1 emits a JSON array as one pipeline object; @(...)
# alone nests that array. An explicit array assignment keeps each PID separate.
[array]$entries = Get-Content -Raw -LiteralPath $statePath | ConvertFrom-Json
$matching = @($entries | Where-Object { $_.name -eq $Name })
if ($matching.Count -ne 1) { throw "Expected exactly one tracked $Name process." }
$module = if ($Name -eq 'processor') { 'transaction-security-module' } else { 'tokenization-module' }
$expectedJar = [IO.Path]::GetFullPath((Join-Path $script:DemoRoot "$module\target\$module-0.0.1-SNAPSHOT-exec.jar"))
$recordedJar = [IO.Path]::GetFullPath([string]$matching[0].jar)
if (-not [string]::Equals($expectedJar, $recordedJar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Tracked JAR is not the expected service in this checkout; refusing to stop it.'
}
$owned = Get-DemoOwnedProcess $matching[0]
if (-not $owned) { throw "$Name is not running; an actual outage test requires a live tracked service." }
Stop-Process -InputObject $owned -ErrorAction Stop
if (-not $owned.WaitForExit(10000)) { throw "Stopped $Name did not exit within 10 seconds." }
$remaining = @($entries | Where-Object { $_.name -ne $Name })
[IO.File]::WriteAllText($statePath, (ConvertTo-Json -InputObject $remaining), (New-Object Text.UTF8Encoding($false)))
Write-Host "Stopped only tracked $Name (PID $($owned.Id)); databases and all other services preserved."
