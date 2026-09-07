$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$script:DemoRoot = Split-Path -Parent $PSScriptRoot
$script:DemoLocal = Join-Path $script:DemoRoot '.local'

function Get-DemoNodeVersion {
    param([Parameter(Mandatory)][string]$Path)
    $output = @(& $Path --version 2>$null)
    if ($LASTEXITCODE -ne 0) { throw "Node version check failed (exit $LASTEXITCODE): $Path" }
    $version = ($output -join "`n").Trim()
    if ($version -notmatch '^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$') {
        throw "Unrecognized Node version from $Path"
    }
    if ([int]$Matches[1] -lt 20) { throw "Node.js 20+ required; found $version at $Path" }
    return $version
}

function Get-DemoNode {
    # Find an existing runtime without installing software or changing PATH.
    # An explicit override must not silently fall back to another runtime.
    $explicit = -not [string]::IsNullOrWhiteSpace($env:DEMO_NODE_PATH)
    $candidates = @()
    if ($explicit) {
        $candidates = @($env:DEMO_NODE_PATH)
    } else {
        $commands = @(Get-Command -Name node.exe, node -CommandType Application -All -ErrorAction SilentlyContinue)
        foreach ($command in $commands) { $candidates += $command.Source }
        $candidates += Join-Path $script:DemoRoot '.local\tools\node\node.exe'
        $candidates += Join-Path $script:DemoRoot '.local\tools\node\bin\node.exe'
        if ($env:ProgramFiles) { $candidates += Join-Path $env:ProgramFiles 'nodejs\node.exe' }
        if ($env:LOCALAPPDATA) { $candidates += Join-Path $env:LOCALAPPDATA 'Programs\nodejs\node.exe' }
        if ($env:USERPROFILE) {
            $candidates += Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe'
        }
    }
    $rejected = @()
    foreach ($candidate in @($candidates | Select-Object -Unique)) {
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            if ($explicit) { throw "DEMO_NODE_PATH does not point to an existing runtime: $candidate" }
            continue
        }
        try {
            $resolved = (Resolve-Path -LiteralPath $candidate).ProviderPath
            $version = Get-DemoNodeVersion -Path $resolved
            Write-Verbose "Selected Node.js $version at $resolved"
            return $resolved
        } catch {
            if ($explicit) { throw "DEMO_NODE_PATH is unusable: $($_.Exception.Message)" }
            $rejected += $_.Exception.Message
        }
    }
    $detail = if ($rejected.Count -gt 0) { ' Rejected candidates: ' + ($rejected -join '; ') } else { '' }
    throw ('No usable Node.js 20+ runtime found. Install Node.js 20+ or set DEMO_NODE_PATH to an existing node.exe. Checked PATH, .local/tools/node, standard Windows install locations, and the current user bundled runtime.' + $detail)
}

function Get-DemoDocker {
    if ($env:DEMO_DOCKER_PATH) {
        if (-not (Test-Path -LiteralPath $env:DEMO_DOCKER_PATH)) { throw 'DEMO_DOCKER_PATH does not exist.' }
        return $env:DEMO_DOCKER_PATH
    }
    $installed = Get-Command docker -ErrorAction SilentlyContinue
    if ($installed) { return $installed.Source }
    foreach ($candidate in @(
        (Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin\docker.exe'),
        'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
    )) { if (Test-Path -LiteralPath $candidate) { return $candidate } }
    throw 'Docker CLI not found. Install/start Docker Desktop or set DEMO_DOCKER_PATH.'
}

function Invoke-DemoCompose {
    param([Parameter(Mandatory)][string[]]$Arguments)
    $docker = Get-DemoDocker
    & $docker compose --project-name secure-integrity-demo --project-directory $script:DemoRoot --env-file (Join-Path $script:DemoLocal 'compose.env') -f (Join-Path $script:DemoRoot 'compose.yaml') @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed (exit $LASTEXITCODE)." }
}

function Get-DemoSecrets {
    $path = Join-Path $script:DemoLocal 'secrets.json'
    if (-not (Test-Path -LiteralPath $path)) { throw 'Local credentials are absent. Run scripts/start.ps1 first.' }
    return Get-Content -Raw -LiteralPath $path | ConvertFrom-Json
}

function New-DemoRandomSecret {
    $bytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
}

function Initialize-DemoSecrets {
    New-Item -ItemType Directory -Force -Path $script:DemoLocal | Out-Null
    $path = Join-Path $script:DemoLocal 'secrets.json'
    if (-not (Test-Path -LiteralPath $path)) {
        $values = [ordered]@{}
        foreach ($key in @('PRIMARY_ADMIN_PASSWORD', 'AUDIT_ADMIN_PASSWORD', 'SETTLEMENT_ADMIN_PASSWORD',
            'PRIMARY_APP_PASSWORD', 'PRIMARY_PROCESSOR_PASSWORD', 'AUDIT_KEY_PASSWORD', 'AUDIT_PROCESSOR_PASSWORD',
            'SETTLEMENT_PROCESSOR_PASSWORD', 'KEY_WRITER_TOKEN', 'KEY_VERIFIER_TOKEN', 'KEY_ADMIN_TOKEN',
            'KEY_SIGNER_TOKEN', 'PROCESSOR_APP_TOKEN', 'PROCESSOR_ADMIN_TOKEN', 'APP_API_TOKEN')) {
            $values[$key] = New-DemoRandomSecret
        }
        [IO.File]::WriteAllText($path, ($values | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
    }
    $secrets = Get-DemoSecrets
    $composeText = @('PRIMARY_ADMIN_PASSWORD', 'AUDIT_ADMIN_PASSWORD', 'SETTLEMENT_ADMIN_PASSWORD') | ForEach-Object { "$_=$($secrets.$_)" }
    [IO.File]::WriteAllLines((Join-Path $script:DemoLocal 'compose.env'), [string[]]$composeText, (New-Object Text.UTF8Encoding($false)))
    # Local host administrators are trusted. Ordinary other Windows identities
    # must not inherit access to this directory of demo secrets.
    if ($env:OS -eq 'Windows_NT') {
        $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        $workspaceOwner = (Get-Acl -LiteralPath $script:DemoRoot).Owner
        & icacls $script:DemoLocal '/inheritance:r' '/grant:r' "${identity}:(OI)(CI)F" "${workspaceOwner}:(OI)(CI)F" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not restrict permissions on .local.' }
    }
    return $secrets
}

function Invoke-DemoMaven {
    param([string[]]$Arguments)
    $bundledJava = Join-Path $script:DemoRoot '.local\tools\jdk-21.0.11+10'
    if (Test-Path -LiteralPath $bundledJava) { $env:JAVA_HOME = $bundledJava }
    $maven = Join-Path $script:DemoRoot '.local\tools\apache-maven-3.9.9\bin\mvn.cmd'
    if (-not (Test-Path -LiteralPath $maven)) {
        $installed = Get-Command mvn -ErrorAction SilentlyContinue
        if (-not $installed) { throw 'Maven not found. Install Maven and Java 21 or provide bundled tools.' }
        $maven = $installed.Source
    }
    $cache = if ($env:MAVEN_REPO_LOCAL) { $env:MAVEN_REPO_LOCAL } else { Join-Path $script:DemoRoot '.m2-cache' }
    Push-Location $script:DemoRoot
    try {
        & $maven "-Dmaven.repo.local=$cache" @Arguments
        if ($LASTEXITCODE -ne 0) { throw "Maven failed (exit $LASTEXITCODE)." }
    } finally { Pop-Location }
}

function Wait-DemoHttp {
    param([string]$Url, [int]$TimeoutSeconds = 60)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 2
            if ($response.StatusCode -eq 200) { return }
        } catch { }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Service health timeout: $Url. See .local/logs; no data was deleted."
}

function Get-DemoOwnedProcess {
    param($Entry)
    $process = Get-Process -Id $Entry.pid -ErrorAction SilentlyContinue
    if (-not $process) { return $null }
    if ($process.StartTime.ToUniversalTime().Ticks.ToString() -ne [string]$Entry.startedAtTicks) { throw "PID $($Entry.pid) was reused; refusing to touch it." }
    $command = (Get-CimInstance Win32_Process -Filter "ProcessId=$($Entry.pid)").CommandLine
    if (-not $command -or -not $command.Contains([string]$Entry.jar)) { throw "PID $($Entry.pid) command does not match the recorded demo JAR." }
    return $process
}
