param([switch]$SkipBuild, [switch]$EnableTestFaults)
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'notification-settings.ps1')
$secrets = Initialize-DemoSecrets
$notificationEnvironment = Get-DemoNotificationEnvironment -LocalDirectory $script:DemoLocal
if (-not $SkipBuild) {
    # Rebuilding live JARs fails on Windows. Preserve databases and stop only
    # this project's tracked JVMs before replacing their build artifacts.
    & (Join-Path $PSScriptRoot 'stop.ps1') -KeepDatabases
    if (-not $?) { throw 'Could not stop owned demo JVMs safely before rebuilding.' }
    Invoke-DemoMaven -Arguments @('clean', 'verify')
}

$java = Join-Path $script:DemoRoot '.local\tools\jdk-21.0.11+10\bin\java.exe'
if (-not (Test-Path -LiteralPath $java)) {
    $command = Get-Command java -ErrorAction SilentlyContinue
    if (-not $command) { throw 'Java 21 is required.' }
    $java = $command.Source
}
$keyJar = Join-Path $script:DemoRoot 'tokenization-module\target\tokenization-module-0.0.1-SNAPSHOT-exec.jar'
$processorJar = Join-Path $script:DemoRoot 'transaction-security-module\target\transaction-security-module-0.0.1-SNAPSHOT-exec.jar'
$appJar = Join-Path $script:DemoRoot 'account-transfer-app\target\account-transfer-app-0.0.1-SNAPSHOT-exec.jar'
foreach ($jar in @($keyJar, $processorJar, $appJar)) {
    if (-not (Test-Path -LiteralPath $jar)) { throw "JAR missing: $jar. Run scripts/test.ps1 first." }
}

$composeArguments = @('up', '-d', '--wait', '--wait-timeout', '60')
if ($notificationEnvironment.NOTIFICATION_MODE -eq 'mailpit') { $composeArguments = @('--profile', 'mail') + $composeArguments }
Invoke-DemoCompose -Arguments $composeArguments
& (Join-Path $PSScriptRoot 'assert-protected-migration.ps1')
if (-not $?) { throw 'Protected database migration check failed.' }
& (Join-Path $PSScriptRoot 'bootstrap-databases.ps1')
if (-not $?) { throw 'Database bootstrap failed.' }

$logDirectory = Join-Path $script:DemoLocal 'logs'
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$statePath = Join-Path $script:DemoLocal 'processes.json'
$script:DemoProcesses = @()
if (Test-Path -LiteralPath $statePath) {
    [array]$recordedProcesses = Get-Content -Raw -LiteralPath $statePath | ConvertFrom-Json
    foreach ($entry in $recordedProcesses) {
        if (Get-DemoOwnedProcess $entry) { $script:DemoProcesses += $entry }
    }
}

function Start-DemoJava {
    param([string]$Name, [string]$Jar, [int]$Port, [hashtable]$Environment)
    $existing = @($script:DemoProcesses | Where-Object { $_.name -eq $Name })
    if ($existing.Count -gt 0) {
        Wait-DemoHttp "http://127.0.0.1:$Port/health"
        Write-Host "$Name already running."
        return
    }
    $socket = New-Object Net.Sockets.TcpClient
    $occupied = $false
    try { $socket.Connect('127.0.0.1', $Port); $occupied = $true } catch { } finally { $socket.Dispose() }
    if ($occupied) { throw "Port $Port is occupied by an untracked process; refusing to replace it." }

    # Each service inherits only the credentials it needs from this temporary
    # process environment; no secrets are placed on the Java command line.
    $managed = @('PRIMARY_URL','PRIMARY_USER','PRIMARY_PASSWORD','AUDIT_URL','AUDIT_USER','AUDIT_PASSWORD',
        'SETTLEMENT_URL','SETTLEMENT_USER','SETTLEMENT_PASSWORD','KEY_SERVICE_URL','KEY_DIRECTORY',
        'KEY_WRITER_TOKEN','KEY_VERIFIER_TOKEN','KEY_ADMIN_TOKEN','KEY_SIGNER_TOKEN','PROCESSOR_URL',
        'PROCESSOR_APP_TOKEN','PROCESSOR_ADMIN_TOKEN','APP_API_TOKEN','KEY_SCHEMA_INIT','PROCESSOR_SCHEMA_INIT',
        'PROCESSOR_TEST_FAULTS_ENABLED','BROKER_DIRECTORY','SERVER_ADDRESS','SERVER_PORT','KEY_PORT','KEY_BIND_ADDRESS') + @($secrets.PSObject.Properties.Name) + @(Get-DemoNotificationSettingNames)
    $managed = @($managed | Select-Object -Unique)
    $previous = @{}
    $logStamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfff') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 6)
    $stdoutLog = Join-Path $logDirectory "$Name.$logStamp.stdout.log"
    $stderrLog = Join-Path $logDirectory "$Name.$logStamp.stderr.log"
    try {
        foreach ($setting in $managed) {
            $previous[$setting] = [Environment]::GetEnvironmentVariable($setting, 'Process')
            [Environment]::SetEnvironmentVariable($setting, $null, 'Process')
        }
        foreach ($setting in $Environment.Keys) { [Environment]::SetEnvironmentVariable($setting, [string]$Environment[$setting], 'Process') }
        $process = Start-Process -FilePath $java -ArgumentList @('-Xms128m','-Xmx512m','-jar',('"' + $Jar + '"')) -WorkingDirectory $script:DemoRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog
    } finally {
        foreach ($setting in $managed) { [Environment]::SetEnvironmentVariable($setting, $previous[$setting], 'Process') }
    }
    $script:DemoProcesses += [pscustomobject]@{ name=$Name; pid=$process.Id; startedAtTicks=$process.StartTime.ToUniversalTime().Ticks.ToString(); jar=$Jar; stdoutLog=$stdoutLog; stderrLog=$stderrLog }
    [IO.File]::WriteAllText($statePath, (ConvertTo-Json -InputObject @($script:DemoProcesses)), (New-Object Text.UTF8Encoding($false)))
    Wait-DemoHttp "http://127.0.0.1:$Port/health"
    Write-Host "$Name ready on http://127.0.0.1:$Port"
}

Start-DemoJava key-service $keyJar 8081 @{
    SERVER_ADDRESS='127.0.0.1'; SERVER_PORT='8081'; KEY_PORT='8081'; KEY_BIND_ADDRESS='127.0.0.1';
    AUDIT_URL='jdbc:postgresql://127.0.0.1:55432/audit_db'; AUDIT_USER='audit_key'; AUDIT_PASSWORD=$secrets.AUDIT_KEY_PASSWORD;
    KEY_DIRECTORY=(Join-Path $script:DemoLocal 'keys'); KEY_WRITER_TOKEN=$secrets.KEY_WRITER_TOKEN;
    KEY_VERIFIER_TOKEN=$secrets.KEY_VERIFIER_TOKEN; KEY_ADMIN_TOKEN=$secrets.KEY_ADMIN_TOKEN; KEY_SIGNER_TOKEN=$secrets.KEY_SIGNER_TOKEN
}
$processorEnvironment = @{
    SERVER_ADDRESS='127.0.0.1'; SERVER_PORT='8082';
    PRIMARY_URL='jdbc:postgresql://127.0.0.1:55431/primary_db'; PRIMARY_USER='primary_processor'; PRIMARY_PASSWORD=$secrets.PRIMARY_PROCESSOR_PASSWORD;
    AUDIT_URL='jdbc:postgresql://127.0.0.1:55432/audit_db'; AUDIT_USER='audit_processor'; AUDIT_PASSWORD=$secrets.AUDIT_PROCESSOR_PASSWORD;
    SETTLEMENT_URL='jdbc:postgresql://127.0.0.1:55432/audit_db'; SETTLEMENT_USER='settlement_processor'; SETTLEMENT_PASSWORD=$secrets.SETTLEMENT_PROCESSOR_PASSWORD;
    BROKER_DIRECTORY=(Join-Path $script:DemoLocal 'activemq');
    KEY_SERVICE_URL='http://127.0.0.1:8081'; KEY_VERIFIER_TOKEN=$secrets.KEY_VERIFIER_TOKEN; KEY_SIGNER_TOKEN=$secrets.KEY_SIGNER_TOKEN;
    PROCESSOR_APP_TOKEN=$secrets.PROCESSOR_APP_TOKEN; PROCESSOR_ADMIN_TOKEN=$secrets.PROCESSOR_ADMIN_TOKEN;
    PROCESSOR_TEST_FAULTS_ENABLED=([string][bool]$EnableTestFaults).ToLowerInvariant()
}
foreach ($name in $notificationEnvironment.Keys) { $processorEnvironment[$name] = $notificationEnvironment[$name] }
Start-DemoJava processor $processorJar 8082 $processorEnvironment
Start-DemoJava application $appJar 8080 @{
    SERVER_ADDRESS='127.0.0.1'; SERVER_PORT='8080';
    PRIMARY_URL='jdbc:postgresql://127.0.0.1:55431/primary_db'; PRIMARY_USER='primary_app'; PRIMARY_PASSWORD=$secrets.PRIMARY_APP_PASSWORD;
    KEY_SERVICE_URL='http://127.0.0.1:8081'; KEY_WRITER_TOKEN=$secrets.KEY_WRITER_TOKEN;
    PROCESSOR_URL='http://127.0.0.1:8082'; PROCESSOR_APP_TOKEN=$secrets.PROCESSOR_APP_TOKEN; APP_API_TOKEN=$secrets.APP_API_TOKEN
}
Write-Host 'Local demo ready. No real payments. Credentials: .local/secrets.json (never commit/share this file).'
if ($notificationEnvironment.NOTIFICATION_MODE -eq 'mailpit') {
    Write-Host 'Incident email: http://127.0.0.1:8025 (local capture only; no external delivery).'
} elseif ($notificationEnvironment.NOTIFICATION_MODE -eq 'smtp') {
    Write-Host 'Incident email: authenticated SMTP enabled for privately configured recipients.'
} else {
    Write-Host 'Incident email delivery is disabled; new incident notifications remain queued.'
}
if ($EnableTestFaults) { Write-Warning 'Test fault injection enabled. Stop and restart without -EnableTestFaults before presentations.' }
