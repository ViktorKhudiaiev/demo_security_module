# Dependency-free regression checks for Node discovery. Only temporary version
# fixtures and process-local environment variables are changed; services are not started.
. (Join-Path $PSScriptRoot 'common.ps1')

$savedPath = $env:PATH
$savedOverride = $env:DEMO_NODE_PATH
$fixtureDirectory = Join-Path ([IO.Path]::GetTempPath()) ('demo node tests ' + [Guid]::NewGuid().ToString('N'))
$fixturePaths = @()
$script:NodeTestPassed = 0
$script:NodeTestFailures = @()

function Assert-NodeEqual {
    param($Actual, $Expected)
    if ($Actual -cne $Expected) { throw "Expected '$Expected', received '$Actual'." }
}

function Assert-NodeThrows {
    param([scriptblock]$Body, [string]$MessagePattern = '.')
    $caught = $null
    try { & $Body | Out-Null } catch { $caught = $_.Exception.Message }
    if ($null -eq $caught) { throw 'Expected an error, but the call succeeded.' }
    if ($caught -notmatch $MessagePattern) { throw "Unexpected error: $caught" }
}

function Test-NodeCase {
    param([string]$Name, [scriptblock]$Body)
    $env:DEMO_NODE_PATH = $null
    $env:PATH = $savedPath
    try {
        & $Body
        $script:NodeTestPassed++
        Write-Host "PASS $Name"
    } catch {
        $script:NodeTestFailures += ($Name + ': ' + $_.Exception.Message)
        Write-Host "FAIL ${Name}: $($_.Exception.Message)"
    } finally {
        $env:DEMO_NODE_PATH = $savedOverride
        $env:PATH = $savedPath
    }
}

try {
    New-Item -ItemType Directory -Path $fixtureDirectory | Out-Null
    $versions = [ordered]@{
        minimum = 'v20.0.0'
        current = 'v22.14.0'
        old = 'v18.20.8'
        prerelease = 'v20.0.0-rc.1'
        malformed = 'not a node version'
        leadingZeroMajor = 'v020.0.0'
        leadingZeroMinor = 'v20.00.0'
        leadingZeroPatch = 'v20.0.00'
        failure = 'v22.14.0'
    }
    $fixtures = @{}
    foreach ($name in $versions.Keys) {
        $fixturePath = Join-Path $fixtureDirectory ($name + ' node.cmd')
        $exitCode = if ($name -eq 'failure') { 7 } else { 0 }
        $body = "@echo off`r`nif not `"%~1`"==`"--version`" exit /b 9`r`necho $($versions[$name])`r`nexit /b $exitCode`r`n"
        [IO.File]::WriteAllText($fixturePath, $body, [Text.Encoding]::ASCII)
        $fixturePaths += $fixturePath
        $fixtures[$name] = $fixturePath
    }

    Test-NodeCase 'accepts Node 20.0.0 minimum' {
        Assert-NodeEqual (Get-DemoNodeVersion $fixtures.minimum) 'v20.0.0'
    }
    Test-NodeCase 'runs a version probe from a path containing spaces' {
        Assert-NodeEqual (Get-DemoNodeVersion $fixtures.current) 'v22.14.0'
    }
    foreach ($name in @('old', 'prerelease', 'malformed', 'leadingZeroMajor', 'leadingZeroMinor', 'leadingZeroPatch', 'failure')) {
        Test-NodeCase "rejects $name version probe" {
            Assert-NodeThrows { Get-DemoNodeVersion $fixtures[$name] }
        }
    }

    Test-NodeCase 'explicit valid path takes priority' {
        $env:DEMO_NODE_PATH = $fixtures.current
        function Get-Command { throw 'PATH lookup must not run for an explicit override.' }
        Assert-NodeEqual (Get-DemoNode) $fixtures.current
    }
    Test-NodeCase 'relative explicit path resolves to an absolute path' {
        Push-Location -LiteralPath $fixtureDirectory
        try {
            $env:DEMO_NODE_PATH = '.\current node.cmd'
            Assert-NodeEqual (Get-DemoNode) $fixtures.current
        } finally { Pop-Location }
    }
    Test-NodeCase 'explicit nonexistent path fails without fallback' {
        $env:DEMO_NODE_PATH = Join-Path $fixtureDirectory 'missing node.exe'
        function Get-Command { throw 'Unexpected PATH fallback.' }
        Assert-NodeThrows { Get-DemoNode } 'DEMO_NODE_PATH'
    }
    Test-NodeCase 'explicit old runtime fails without fallback' {
        $env:DEMO_NODE_PATH = $fixtures.old
        function Get-Command { throw 'Unexpected PATH fallback.' }
        Assert-NodeThrows { Get-DemoNode } 'DEMO_NODE_PATH'
    }
    Test-NodeCase 'explicit directory is rejected' {
        $env:DEMO_NODE_PATH = $fixtureDirectory
        Assert-NodeThrows { Get-DemoNode } 'DEMO_NODE_PATH'
    }
    Test-NodeCase 'PATH lookup requests applications and uses the selected executable' {
        function Get-Command {
            [CmdletBinding()]
            param([string[]]$Name, [string]$CommandType, [switch]$All)
            Assert-NodeEqual $CommandType 'Application'
            [pscustomobject]@{ Source = $fixtures.current }
        }
        Assert-NodeEqual (Get-DemoNode) $fixtures.current
    }

    $fallbackCandidates = [ordered]@{
        'project .local/tools/node' = (Join-Path $script:DemoRoot '.local/tools/node/node.exe')
        'project .local/tools/node/bin' = (Join-Path $script:DemoRoot '.local/tools/node/bin/node.exe')
        'Program Files installation' = (Join-Path $env:ProgramFiles 'nodejs/node.exe')
        'per-user installation' = (Join-Path $env:LOCALAPPDATA 'Programs/nodejs/node.exe')
        'existing Codex runtime' = (Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe')
    }
    foreach ($label in $fallbackCandidates.Keys) {
        $expectedCandidate = $fallbackCandidates[$label]
        Test-NodeCase "discovers $label when PATH has no Node" {
            function Get-Command {
                [CmdletBinding()]
                param([string[]]$Name, [string]$CommandType, [switch]$All)
            }
            function Test-Path {
                param([string]$LiteralPath, [string]$PathType)
                $LiteralPath.Replace('/', '\') -eq $expectedCandidate.Replace('/', '\')
            }
            function Resolve-Path {
                param([string]$LiteralPath)
                [pscustomobject]@{ ProviderPath = $expectedCandidate }
            }
            function Get-DemoNodeVersion { param([string]$Path) 'v22.14.0' }
            Assert-NodeEqual (Get-DemoNode) $expectedCandidate
        }
    }

    Test-NodeCase 'skips an old PATH runtime and checks fallback candidates' {
        $expectedCandidate = $fallbackCandidates['project .local/tools/node']
        function Get-Command {
            [CmdletBinding()]
            param([string[]]$Name, [string]$CommandType, [switch]$All)
            [pscustomobject]@{ Source = $fixtures.old }
        }
        function Test-Path {
            param([string]$LiteralPath, [string]$PathType)
            $LiteralPath -eq $fixtures.old -or $LiteralPath.Replace('/', '\') -eq $expectedCandidate.Replace('/', '\')
        }
        function Resolve-Path {
            param([string]$LiteralPath)
            [pscustomobject]@{ ProviderPath = $LiteralPath }
        }
        function Get-DemoNodeVersion {
            param([string]$Path)
            if ($Path -eq $fixtures.old) { throw 'Node 18 is too old.' }
            'v22.14.0'
        }
        Assert-NodeEqual ((Get-DemoNode).Replace('/', '\')) ($expectedCandidate.Replace('/', '\'))
    }
    Test-NodeCase 'reports an actionable error when no runtime exists' {
        function Get-Command {
            [CmdletBinding()]
            param([string[]]$Name, [string]$CommandType, [switch]$All)
        }
        function Test-Path { param([string]$LiteralPath, [string]$PathType) $false }
        Assert-NodeThrows { Get-DemoNode } 'DEMO_NODE_PATH'
    }
    Test-NodeCase 'finds and executes an actual installed runtime with PATH empty' {
        $env:PATH = ''
        $resolved = Get-DemoNode
        if (-not [IO.Path]::IsPathRooted($resolved)) { throw 'Node path is not absolute.' }
        $version = Get-DemoNodeVersion $resolved
        Write-Host "  Installed runtime: $resolved ($version)"
    }
} finally {
    $env:PATH = $savedPath
    $env:DEMO_NODE_PATH = $savedOverride
    # Only individually recorded files in this newly created directory are removed.
    foreach ($fixturePath in $fixturePaths) {
        if (Test-Path -LiteralPath $fixturePath) { Remove-Item -LiteralPath $fixturePath }
    }
    if (Test-Path -LiteralPath $fixtureDirectory) { Remove-Item -LiteralPath $fixtureDirectory }
}

Write-Host "Node runtime tests: $script:NodeTestPassed passed, $($script:NodeTestFailures.Count) failed (PowerShell $($PSVersionTable.PSVersion))."
if ($script:NodeTestFailures.Count -gt 0) { throw ($script:NodeTestFailures -join [Environment]::NewLine) }
