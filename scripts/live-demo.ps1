param([switch]$StartStack)
. (Join-Path $PSScriptRoot 'common.ps1')
if ($StartStack) {
    & (Join-Path $PSScriptRoot 'start.ps1') -SkipBuild
    if (-not $?) { throw 'Could not start the existing local stack.' }
}
$labNode = Get-DemoNode
Write-Host 'The live lab binds only to http://127.0.0.1:8090. Keep this terminal open.'
Write-Host 'No experiment runs until you click an action. Ctrl+C stops the helper, not the databases.'
Write-Host 'Existing JVM settings are retained. Do not run fault-injection, recovery or load tests during a presentation.'
& $labNode (Join-Path $script:DemoRoot 'docs\live\server.mjs')
if ($LASTEXITCODE -ne 0) { throw "Live lab exited with code $LASTEXITCODE." }
