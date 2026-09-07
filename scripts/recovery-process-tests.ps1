# Offline safety regressions. No process inspection/stops, service calls, or
# file writes. Run with Windows PowerShell 5.1 as well as newer PowerShell.
. (Join-Path $PSScriptRoot 'common.ps1')
$script:RecoveryTestPassed = 0
$script:RecoveryTestFailed = @()
function Assert-RecoveryEqual {
    param($Actual, $Expected)
    if ($Actual -cne $Expected) { throw "Expected '$Expected', received '$Actual'." }
}
function Assert-RecoveryThrows {
    param([scriptblock]$Body, [string]$Pattern)
    $caught = $null
    try { & $Body | Out-Null } catch { $caught = $_.Exception.Message }
    if ($null -eq $caught -or $caught -notmatch $Pattern) { throw "Expected error /$Pattern/, received '$caught'." }
}
function Test-RecoveryCase {
    param([string]$Name, [scriptblock]$Body)
    try {
        & $Body
        $script:RecoveryTestPassed++
        Write-Host "PASS $Name"
    } catch {
        $script:RecoveryTestFailed += "${Name}: $($_.Exception.Message)"
        Write-Host "FAIL ${Name}: $($_.Exception.Message)"
    }
}

# Exercise the actual assignment from the stop helper without executing its
# process-stop/write statements. Get-Content below is replaced by an in-memory
# fixture provider. Parsing the AST itself only reads the source file.
$helperPath = Join-Path $PSScriptRoot 'recovery-stop-service.ps1'
$parseTokens = $null
$parseErrors = $null
$helperAst = [Management.Automation.Language.Parser]::ParseFile($helperPath, [ref]$parseTokens, [ref]$parseErrors)
if ($parseErrors.Count -ne 0) { throw 'The recovery stop helper has syntax errors.' }
$readStatements = @($helperAst.FindAll({
    param($node)
    $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Extent.Text.StartsWith('[array]$entries =')
}, $true))
if ($readStatements.Count -ne 1) { throw 'Cannot identify the actual typed process-list assignment for regression testing.' }
# Enumerate as start.ps1 does: PS7 represents [] as a null typed array, which
# foreach correctly treats as zero entries (not one explicitly emitted null).
$readEntries = [scriptblock]::Create($readStatements[0].Extent.Text + '; foreach ($fixtureEntry in $entries) { $fixtureEntry }')
$statePath = 'in-memory-process-state.json'

foreach ($count in @(0, 1, 3)) {
    Test-RecoveryCase "Windows process JSON array with $count entries is flat" {
        $objects = @()
        for ($index = 0; $index -lt $count; $index++) { $objects += [pscustomobject]@{ name="fixture-$index"; pid=(4200+$index) } }
        $fixtureJson = ConvertTo-Json -InputObject $objects
        function Get-Content { param([switch]$Raw, [string]$LiteralPath) return $fixtureJson }
        $actual = @(& $readEntries)
        Assert-RecoveryEqual $actual.Count $count
        for ($index = 0; $index -lt $count; $index++) {
            Assert-RecoveryEqual ($actual[$index] -is [array]) $false
            Assert-RecoveryEqual $actual[$index].pid (4200+$index)
        }
    }
}
Test-RecoveryCase 'single retained process serializes as a JSON array' {
    $fixtureJson = ConvertTo-Json -InputObject @([pscustomobject]@{ name='fixture'; pid=4200 })
    Assert-RecoveryEqual $fixtureJson.TrimStart().StartsWith('[') $true
    function Get-Content { param([switch]$Raw, [string]$LiteralPath) return $fixtureJson }
    $actual = @(& $readEntries)
    Assert-RecoveryEqual $actual.Count 1
    Assert-RecoveryEqual $actual[0].pid 4200
}

$started = [DateTime]::SpecifyKind([DateTime]::Parse('2026-01-02T03:04:05'), [DateTimeKind]::Utc)
$record = [pscustomobject]@{ pid=4242; startedAtTicks=$started.Ticks.ToString(); jar=(Join-Path $script:DemoRoot 'fixture-only.jar') }
# This guard is intentionally never reached: these tests exercise ownership
# checks only. It makes an accidental future direct process stop fail loudly.
function Stop-Process { throw 'Offline regression tests must never stop a process.' }

Test-RecoveryCase 'missing tracked PID returns no owned process' {
    function Get-Process { param($Id, $ErrorAction) Assert-RecoveryEqual $Id 4242; return $null }
    function Get-CimInstance { throw 'No command-line lookup expected for absent PID.' }
    Assert-RecoveryEqual ($null -eq (Get-DemoOwnedProcess $record)) $true
}
Test-RecoveryCase 'reused PID is rejected before command-line lookup' {
    function Get-Process { param($Id, $ErrorAction) return [pscustomobject]@{ Id=$Id; StartTime=$started.AddSeconds(1) } }
    function Get-CimInstance { throw 'Reused PID must be rejected before CIM lookup.' }
    Assert-RecoveryThrows { Get-DemoOwnedProcess $record } 'was reused'
}
Test-RecoveryCase 'matching PID and start time but another JAR is rejected' {
    function Get-Process { param($Id, $ErrorAction) return [pscustomobject]@{ Id=$Id; StartTime=$started } }
    function Get-CimInstance { param($ClassName, $Filter) return [pscustomobject]@{ CommandLine='java.exe -jar C:\unrelated.jar' } }
    Assert-RecoveryThrows { Get-DemoOwnedProcess $record } 'does not match'
}
Test-RecoveryCase 'missing command-line evidence is rejected' {
    function Get-Process { param($Id, $ErrorAction) return [pscustomobject]@{ Id=$Id; StartTime=$started } }
    function Get-CimInstance { param($ClassName, $Filter) return [pscustomobject]@{ CommandLine=$null } }
    Assert-RecoveryThrows { Get-DemoOwnedProcess $record } 'does not match'
}
Test-RecoveryCase 'PID plus start time plus recorded JAR returns the owned process' {
    function Get-Process { param($Id, $ErrorAction) Assert-RecoveryEqual $Id 4242; return [pscustomobject]@{ Id=$Id; StartTime=$started } }
    function Get-CimInstance {
        param($ClassName, $Filter)
        Assert-RecoveryEqual $ClassName 'Win32_Process'
        Assert-RecoveryEqual $Filter 'ProcessId=4242'
        return [pscustomobject]@{ CommandLine=('java.exe -jar "' + $record.jar + '"') }
    }
    Assert-RecoveryEqual (Get-DemoOwnedProcess $record).Id 4242
}
Write-Host "Recovery process safety: $($script:RecoveryTestPassed) passed, $($script:RecoveryTestFailed.Count) failed; no real process was inspected or stopped."
if ($script:RecoveryTestFailed.Count -gt 0) { throw ($script:RecoveryTestFailed -join "`n") }
