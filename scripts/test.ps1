$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = Join-Path $root 'tools\jdk-21.0.11+10'
$maven = Join-Path $root 'tools\apache-maven-3.9.9\bin\mvn.cmd'

& $maven test
