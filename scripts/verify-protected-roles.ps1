# Read-only checks against the actual merged PostgreSQL role configuration.
. (Join-Path $PSScriptRoot 'common.ps1')
$docker = Get-DemoDocker
$query = @'
BEGIN READ ONLY;
SELECT json_build_object(
 'database',current_database(),
 'roles',json_build_object(
  'key_can_issue',has_table_privilege('audit_key','issuance_receipts','INSERT'),
  'key_cannot_account',NOT has_table_privilege('audit_key','accounts','UPDATE'),
  'key_cannot_rewrite_receipts',NOT has_table_privilege('audit_key','issuance_receipts','UPDATE'),
  'audit_can_append',has_table_privilege('audit_processor','audit_events','INSERT'),
  'audit_cannot_rewrite_history',NOT has_table_privilege('audit_processor','audit_events','UPDATE'),
  'audit_cannot_account',NOT has_table_privilege('audit_processor','accounts','UPDATE'),
  'accounting_can_post',has_table_privilege('settlement_processor','ledger_postings','INSERT'),
  'accounting_cannot_rewrite_postings',NOT has_table_privilege('settlement_processor','ledger_postings','UPDATE'),
  'accounting_cannot_issue',NOT has_table_privilege('settlement_processor','issuance_receipts','INSERT'),
  'accounting_cannot_rewrite_audit',NOT has_table_privilege('settlement_processor','audit_events','UPDATE'),
  'runtime_roles_not_admin',(SELECT bool_and(NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole) FROM pg_roles WHERE rolname IN ('audit_key','audit_processor','settlement_processor')),
  'primary_admin_absent',NOT EXISTS(SELECT FROM pg_roles WHERE rolname='primary_admin')
 ));
COMMIT;
'@
$result = @($query | & $docker compose --project-name secure-integrity-demo --project-directory $script:DemoRoot --env-file (Join-Path $script:DemoLocal 'compose.env') -f (Join-Path $script:DemoRoot 'compose.yaml') exec -T audit-db psql -X -q -t -A -v ON_ERROR_STOP=1 -U audit_admin -d audit_db)
if ($LASTEXITCODE -ne 0) { throw 'Protected role inspection failed.' }
$report = ($result -join "`n") | ConvertFrom-Json
if ($report.database -ne 'audit_db') { throw 'Unexpected protected database identity.' }
foreach ($check in $report.roles.PSObject.Properties) {
    if ($check.Value -ne $true) { throw "Protected role isolation failed: $($check.Name)" }
}
$report | ConvertTo-Json -Depth 4
