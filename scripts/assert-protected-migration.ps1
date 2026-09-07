. (Join-Path $PSScriptRoot 'common.ps1')
$docker = Get-DemoDocker
$legacy = @(& $docker volume ls --filter 'label=com.docker.compose.project=secure-integrity-demo' --filter 'label=com.docker.compose.volume=settlement-data' --format '{{.Name}}')
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect legacy database volumes.' }
if ($legacy.Count -gt 0) {
    $query = "SELECT count(*) FROM demo_schema_versions WHERE name='settlement-schema';"
    $result = @($query | & $docker compose --project-name secure-integrity-demo --project-directory $script:DemoRoot --env-file (Join-Path $script:DemoLocal 'compose.env') -f (Join-Path $script:DemoRoot 'compose.yaml') exec -T audit-db psql -X -q -t -A -v ON_ERROR_STOP=1 -U audit_admin -d audit_db 2>$null)
    if ($LASTEXITCODE -ne 0 -or ($result -join '').Trim() -ne '1') {
        throw 'Legacy accounting data exists. Run scripts/migrate-protected-db.ps1 before starting; empty accounting initialization is prohibited.'
    }
}
