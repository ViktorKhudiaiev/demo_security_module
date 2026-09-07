param([switch]$Offline)
. (Join-Path $PSScriptRoot 'common.ps1')
$arguments = @('clean', 'verify')
if ($Offline) { $arguments = @('-o') + $arguments }
Invoke-DemoMaven -Arguments $arguments
