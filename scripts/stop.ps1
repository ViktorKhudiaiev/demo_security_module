param([switch]$KeepDatabases)
. (Join-Path $PSScriptRoot 'common.ps1')
$statePath = Join-Path $script:DemoLocal 'processes.json'
if (Test-Path -LiteralPath $statePath) {
    # Windows PowerShell 5.1 emits a JSON array as one pipeline object.
    # An array subexpression around that pipeline would nest the process list.
    [array]$entries = Get-Content -Raw -LiteralPath $statePath | ConvertFrom-Json
    if ($null -eq $entries) { $entries = @() }
    [array]::Reverse($entries)
    foreach ($entry in $entries) {
        $process = Get-DemoOwnedProcess $entry
        if ($process) { Stop-Process -Id $process.Id -ErrorAction Stop; Write-Host "Stopped $($entry.name)." }
    }
    [IO.File]::WriteAllText($statePath, '[]')
}
if (-not $KeepDatabases -and (Test-Path -LiteralPath (Join-Path $script:DemoLocal 'compose.env'))) {
    Invoke-DemoCompose -Arguments @('stop')
}
Write-Host 'Demo stopped. Database volumes, keys, receipts and checkpoints were preserved.'
