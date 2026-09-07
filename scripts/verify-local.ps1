param(
    [ValidateRange(10, 3600)][int]$DurationSeconds = 120,
    [ValidateRange(0, 0.05)][double]$RateTolerance = 0.02,
    [switch]$Offline
)
. (Join-Path $PSScriptRoot 'common.ps1')
$runId = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH-mm-ss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$relativeReportDirectory = '.local/verification-' + $runId
$reportDirectory = Join-Path $script:DemoRoot $relativeReportDirectory
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
$reportPath = Join-Path $reportDirectory 'report.json'
$report = [ordered]@{
    runId = $runId
    startedAt = [DateTime]::UtcNow.ToString('o')
    passed = $false
    targetTps = 20
    durationSeconds = $DurationSeconds
    explicitRateTolerance = $RateTolerance
    build = 'NOT_RUN'
    unitTests = $null
    harnessTests = 'NOT_RUN'
    nodeRuntime = $null
    scenarios = 'NOT_RUN'
    load = 'NOT_RUN'
    normalRestart = 'NOT_REQUIRED'
    scenariosEvidence = (Join-Path $reportDirectory 'scenarios/report.json')
    loadEvidence = (Join-Path $reportDirectory 'load/report.json')
    errors = @()
}
$testStartupAttempted = $false
$allChecksPassed = $false
try {
    $node = Get-DemoNode
    $report.nodeRuntime = [ordered]@{ path=$node; version=(Get-DemoNodeVersion -Path $node) }
    Write-Host "Node.js $($report.nodeRuntime.version): $node"
    Write-Host 'Verify load-evidence assertions before running the benchmark.'
    & $node --test (Join-Path $PSScriptRoot 'load-evidence.test.mjs')
    if ($LASTEXITCODE -ne 0) { $report.harnessTests = 'FAILED'; throw 'Load-evidence assertion tests failed.' }
    $report.harnessTests = 'PASSED'
    # Windows keeps running JARs open. Stop only tracked demo JVMs before clean,
    # not after the build; repeated verification must work without manual cleanup.
    & (Join-Path $PSScriptRoot 'stop.ps1') -KeepDatabases
    if (-not $?) { throw 'Could not stop previous owned demo JVMs safely before rebuilding.' }
    Write-Host '1/4 Build and automated tests (Maven verify).'
    $buildStarted = [DateTime]::UtcNow.AddSeconds(-1)
    & (Join-Path $PSScriptRoot 'test.ps1') -Offline:$Offline
    if (-not $?) { throw 'Build/unit/component verification failed.' }
    $unitSummary = [ordered]@{ suites=0; tests=0; failures=0; errors=0; skipped=0 }
    foreach ($module in @('tokenization-module', 'transaction-security-module', 'account-transfer-app')) {
        $moduleTests = 0
        $surefire = Join-Path $script:DemoRoot "$module/target/surefire-reports"
        if (Test-Path -LiteralPath $surefire) {
            foreach ($file in @(Get-ChildItem -LiteralPath $surefire -Filter 'TEST-*.xml' | Where-Object { $_.LastWriteTimeUtc -ge $buildStarted })) {
                [xml]$suite = Get-Content -Raw -LiteralPath $file.FullName
                $unitSummary.suites++
                $moduleTests += [int]$suite.testsuite.tests
                foreach ($field in @('tests', 'failures', 'errors', 'skipped')) { $unitSummary[$field] += [int]$suite.testsuite.$field }
            }
        }
        if ($moduleTests -eq 0) { throw "No fresh executed tests for $module." }
    }
    $report.unitTests = $unitSummary
    if ($unitSummary.tests -eq 0 -or $unitSummary.failures -gt 0 -or $unitSummary.errors -gt 0 -or $unitSummary.skipped -gt 0) { throw 'Fresh successful, unskipped test-result XML evidence was not produced.' }
    $report.build = 'PASSED'

    Write-Host '2/4 Start this demo with explicit fault-injection hooks.'
    $testStartupAttempted = $true
    & (Join-Path $PSScriptRoot 'start.ps1') -SkipBuild -EnableTestFaults
    if (-not $?) { throw 'Test-mode startup failed.' }

    Write-Host '3/4 Functional and adversarial scenarios.'
    & $node (Join-Path $PSScriptRoot 'scenarios.mjs') --output ($relativeReportDirectory + '/scenarios')
    if ($LASTEXITCODE -ne 0) { $report.scenarios = 'FAILED'; throw "Scenarios failed (exit $LASTEXITCODE)." }
    $report.scenarios = 'PASSED'

    Write-Host "4/4 Load test: 20 offered TPS for $DurationSeconds seconds, durable completion required."
    $toleranceArgument = $RateTolerance.ToString([Globalization.CultureInfo]::InvariantCulture)
    & $node (Join-Path $PSScriptRoot 'load.mjs') --rate 20 --seconds $DurationSeconds --rate-tolerance $toleranceArgument --output ($relativeReportDirectory + '/load')
    if ($LASTEXITCODE -ne 0) { $report.load = 'FAILED'; throw "Load acceptance failed (exit $LASTEXITCODE)." }
    $report.load = 'PASSED'
    $allChecksPassed = $true
} catch {
    $report.errors += $_.Exception.Message
    if ($report.build -eq 'NOT_RUN') { $report.build = 'FAILED_OR_BLOCKED' }
    Write-Warning $_.Exception.Message
} finally {
    if ($testStartupAttempted) {
        Write-Host 'Restoring normal mode with test hooks disabled; preserving all databases, keys and evidence.'
        try {
            & (Join-Path $PSScriptRoot 'stop.ps1') -KeepDatabases
            if (-not $?) { throw 'Could not safely stop test-mode JVMs.' }
            & (Join-Path $PSScriptRoot 'start.ps1') -SkipBuild
            if (-not $?) { throw 'Normal-mode startup failed.' }
            $report.normalRestart = 'PASSED'
        } catch {
            $report.normalRestart = 'FAILED'
            $report.errors += ('Normal-mode restoration: ' + $_.Exception.Message)
            Write-Warning ('Normal mode could not be restored: ' + $_.Exception.Message)
        }
    }
    $report.passed = $allChecksPassed -and $report.normalRestart -eq 'PASSED'
    $report.endedAt = [DateTime]::UtcNow.ToString('o')
    [IO.File]::WriteAllText($reportPath, (ConvertTo-Json -InputObject $report -Depth 8), (New-Object Text.UTF8Encoding($false)))
    Write-Host "Combined report: $reportPath"
    Write-Host 'No volumes or unrelated data were deleted. Scenario fixtures and audit evidence are retained.'
}
if (-not $report.passed) { exit 1 }
Write-Host 'Verification passed; normal local demo is running on http://127.0.0.1:8080.'
