param([switch]$SkipBuild)
& (Join-Path $PSScriptRoot 'start.ps1') -SkipBuild:$SkipBuild
if (-not $?) { exit 1 }
