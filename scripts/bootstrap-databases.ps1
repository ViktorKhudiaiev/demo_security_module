. (Join-Path $PSScriptRoot 'common.ps1')
$secrets = Get-DemoSecrets
& (Join-Path $PSScriptRoot 'assert-protected-migration.ps1')
if (-not $?) { throw 'Protected database migration check failed.' }

function Invoke-DemoSql {
    param([string]$Service, [string]$User, [string]$Database, [string]$Sql)
    $docker = Get-DemoDocker
    $oldEncoding = $OutputEncoding
    try {
        $OutputEncoding = New-Object Text.UTF8Encoding($false)
        $Sql | & $docker compose --project-name secure-integrity-demo --project-directory $script:DemoRoot --env-file (Join-Path $script:DemoLocal 'compose.env') -f (Join-Path $script:DemoRoot 'compose.yaml') exec -T $Service psql -X -q -v ON_ERROR_STOP=1 -U $User -d $Database
        if ($LASTEXITCODE -ne 0) { throw "Database initialization failed for $Service (exit $LASTEXITCODE)." }
    } finally { $OutputEncoding = $oldEncoding }
}

function Get-RoleSql {
    param([string]$Name, [string]$Password)
    if ($Name -notmatch '^[a-z_]+$' -or $Password -notmatch '^[0-9a-f]{64}$') { throw 'Unexpected generated database credentials.' }
    $template = @'
DO $role$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '__NAME__') THEN
    CREATE ROLE __NAME__ LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
  END IF;
END
$role$;
ALTER ROLE __NAME__ PASSWORD '__PASSWORD__';
'@
    return $template.Replace('__NAME__', $Name).Replace('__PASSWORD__', $Password)
}

function Read-Schema {
    param([string]$RelativePath)
    $path = Join-Path $script:DemoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path)) { throw "Schema is missing: $RelativePath" }
    $schema = Get-Content -Raw -LiteralPath $path
    $digest = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    $name = [IO.Path]::GetFileNameWithoutExtension($path)
    if ($name -notmatch '^[a-z-]+$') { throw 'Unexpected schema name.' }
    # Never rebuild a persisted database automatically. Repeated starts are
    # no-ops; a changed schema requires an explicit, reviewed migration.
    $wrapper = @'
CREATE TABLE IF NOT EXISTS demo_schema_versions(name VARCHAR(64) PRIMARY KEY, sha256 CHAR(64) NOT NULL, applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP);
DO $schema_guard$
BEGIN
  IF EXISTS (SELECT FROM demo_schema_versions WHERE name='__NAME__' AND sha256 <> '__DIGEST__') THEN
    RAISE EXCEPTION 'Schema __NAME__ changed: apply an explicit migration; automatic recreation is prohibited';
  END IF;
END
$schema_guard$;
SELECT NOT EXISTS (SELECT FROM demo_schema_versions WHERE name='__NAME__') AS apply_schema \gset
\if :apply_schema
__SCHEMA__
INSERT INTO demo_schema_versions(name,sha256) VALUES('__NAME__','__DIGEST__');
\endif
'@
    return $wrapper.Replace('__NAME__', $name).Replace('__DIGEST__', $digest).Replace('__SCHEMA__', $schema)
}

$primarySql = @(
    'BEGIN;',
    (Get-RoleSql primary_app $secrets.PRIMARY_APP_PASSWORD),
    (Get-RoleSql primary_processor $secrets.PRIMARY_PROCESSOR_PASSWORD),
    'REVOKE ALL ON DATABASE primary_db FROM PUBLIC;',
    'REVOKE ALL ON SCHEMA public FROM PUBLIC;',
    'GRANT CONNECT ON DATABASE primary_db TO primary_app, primary_processor;',
    'GRANT USAGE ON SCHEMA public TO primary_app, primary_processor;',
    (Read-Schema 'account-transfer-app/src/main/resources/primary-schema.sql'),
    'GRANT SELECT, INSERT ON accounts, transactions, transaction_outbox TO primary_app;',
    'GRANT SELECT ON transactions, transaction_outbox, transaction_status_events TO primary_processor;',
    'GRANT UPDATE (processed_at_micros) ON transaction_outbox TO primary_processor;',
    'GRANT INSERT ON transaction_status_events TO primary_processor;',
    'COMMIT;'
) -join "`n"
Invoke-DemoSql primary-db primary_admin primary_db $primarySql

$auditSql = @(
    'BEGIN;',
    (Get-RoleSql audit_key $secrets.AUDIT_KEY_PASSWORD),
    (Get-RoleSql audit_processor $secrets.AUDIT_PROCESSOR_PASSWORD),
    (Get-RoleSql settlement_processor $secrets.SETTLEMENT_PROCESSOR_PASSWORD),
    'REVOKE ALL ON DATABASE audit_db FROM PUBLIC;',
    'REVOKE ALL ON SCHEMA public FROM PUBLIC;',
    'GRANT CONNECT ON DATABASE audit_db TO audit_key, audit_processor, settlement_processor;',
    'GRANT USAGE ON SCHEMA public TO audit_key, audit_processor, settlement_processor;',
    (Read-Schema 'tokenization-module/src/main/resources/key-schema.sql'),
    (Read-Schema 'transaction-security-module/src/main/resources/audit-schema.sql'),
    (Read-Schema 'transaction-security-module/src/main/resources/notification-schema.sql'),
    (Read-Schema 'transaction-security-module/src/main/resources/settlement-schema.sql'),
    'GRANT SELECT, INSERT ON issuance_receipts TO audit_key;',
    'GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO audit_key;',
    'GRANT SELECT, INSERT ON audit_events, audit_checkpoints TO audit_processor;',
    'GRANT SELECT, UPDATE ON audit_head TO audit_processor;',
    'GRANT SELECT, INSERT ON notification_outbox TO audit_processor;',
    'GRANT UPDATE (attempts,next_attempt_micros,lease_until_micros,claim_token,delivered_at_micros,last_error) ON notification_outbox TO audit_processor;',
    'GRANT SELECT, INSERT, UPDATE ON accounts, operation_jobs, settlement_outbox, account_audit_outbox TO settlement_processor;',
    'GRANT SELECT, INSERT ON operation_results, ledger_journal, ledger_postings TO settlement_processor;',
    'COMMIT;'
) -join "`n"
Invoke-DemoSql audit-db audit_admin audit_db $auditSql

Write-Host 'Two databases initialized with separate runtime roles for issuance, audit and accounting.'
