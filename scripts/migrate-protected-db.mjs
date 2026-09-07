// Explicit migration. Never drop, truncate, overwrite existing tables, or delete a volume.
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {createHash, randomUUID} from 'node:crypto';
import {mkdir, readFile, writeFile, stat} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {normalizedMetadata} from './migration-metadata.mjs';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
assert.equal(process.argv[2],'--docker');
const docker=process.argv[3];assert(docker);
const resumePath=process.argv[4]==='--resume-report'?path.resolve(process.argv[5]):null;
if(resumePath)assert(resumePath.startsWith(path.join(root,'.local/migrations')+path.sep),'Resume report must be inside this project migration directory');
const compose=['compose','--project-name','secure-integrity-demo','--project-directory',root,
  '--env-file',path.join(root,'.local/compose.env'),'-f',path.join(root,'compose.yaml'),'--profile','legacy'];
const tables={accounts:'id',operation_jobs:'operation_id',operation_results:'operation_id',
  ledger_journal:'operation_id',ledger_postings:'operation_id,leg',settlement_outbox:'id',account_audit_outbox:'id'};
const auditTables={issuance_receipts:'sequence',audit_events:'seq',audit_head:'id',audit_checkpoints:'tree_size'};
const runId='protected-'+new Date().toISOString().replaceAll(':','-')+'-'+randomUUID().slice(0,8);
const output=path.join(root,'.local/migrations',runId);
await mkdir(output,{recursive:true});
const report={runId,startedAt:new Date().toISOString(),passed:false,backups:[],tables:{},notes:[
  'Seven accounting tables copied into audit_db; separate runtime roles retained.',
  'Old settlement container is stopped only after verification; its volume is retained.',
  'After new writes, rollback requires reconciliation of those writes, not a blind endpoint switch.'
]};
const digest=bytes=>createHash('sha256').update(bytes).digest('hex');
async function run(args,input='',timeout=120000) {
  return new Promise((resolve,reject)=>{
    const child=spawn(docker,args,{cwd:root,windowsHide:true,stdio:['pipe','pipe','pipe']});
    let stdout='',stderr='',size=0;const timer=setTimeout(()=>{child.kill();reject(new Error('Docker command timed out'));},timeout);
    child.on('error',error=>{clearTimeout(timer);reject(error);});
    child.stdout.on('data',chunk=>{size+=chunk.length;if(size>128*1024*1024){child.kill();return;}stdout+=chunk;});
    child.stderr.on('data',chunk=>{if(stderr.length<8000)stderr+=chunk;});
    child.on('close',code=>{clearTimeout(timer);code===0?resolve(stdout.trim()):reject(new Error(`Docker operation failed (${code}): ${stderr.slice(0,1500)}`));});
    child.stdin.on('error',()=>{});child.stdin.end(input);
  });
}
async function sql(database,query) {
  return run([...compose,'exec','-T',database+'-db','psql','-X','-q','-t','-A','-v','ON_ERROR_STOP=1',
    '-U',database+'_admin','-d',database+'_db'],query);
}
async function fingerprints(database,definitions) {
  const result={};
  for(const [table,order] of Object.entries(definitions)) {
    const data=await sql(database,`BEGIN READ ONLY; SELECT row_to_json(t)::text FROM (SELECT * FROM ${table} ORDER BY ${order}) t; COMMIT;`);
    result[table]={count:Number(await sql(database,`SELECT count(*) FROM ${table};`)),sha256:digest(data)};
  }
  return result;
}
async function metadata(database) {
  const names=Object.keys(tables).map(t=>`'${t}'`).join(',');
  return sql(database,`SELECT json_build_object('indexes',(SELECT json_agg(t ORDER BY tablename,indexname) FROM
    (SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname='public' AND tablename IN (${names})) t),
    'constraints',(SELECT json_agg(t ORDER BY table_name,conname) FROM (SELECT r.relname AS table_name,c.conname,pg_get_constraintdef(c.oid) AS definition
    FROM pg_constraint c JOIN pg_class r ON r.oid=c.conrelid JOIN pg_namespace n ON n.oid=r.relnamespace WHERE n.nspname='public' AND r.relname IN (${names})) t));`);
}
async function backup(database,filename,selected=[]) {
  const remote='/tmp/'+runId+'-'+filename;
  await run([...compose,'exec','-T',database+'-db','pg_dump','-U',database+'_admin','-d',database+'_db',
    '--format=custom','--no-owner','--no-privileges','--file',remote,...selected.flatMap(t=>['--table','public.'+t])]);
  await run([...compose,'exec','-T',database+'-db','pg_restore','--list',remote]);
  const local=path.join(output,filename);
  await run([...compose,'cp',database+'-db:'+remote,local]);
  const size=(await stat(local)).size;assert(size>0);
  report.backups.push({filename,bytes:size,sha256:digest(await readFile(local))});
  return local;
}
try {
  // Refuse live service or lab processes, including ones absent from the JVM ownership file.
  for(const port of [8080,8081,8082,8090]) {
    let reachable=false;
    try {await fetch(`http://127.0.0.1:${port}/health`,{signal:AbortSignal.timeout(1000)});reachable=true;} catch {}
    assert(!reachable,`Port ${port} is still serving. Stop the local lab and services before migrating.`);
  }
  await run([...compose,'up','-d','--wait','audit-db','settlement-db']);
  for(const database of ['audit','settlement']) {
    const id=await run([...compose,'ps','-q',database+'-db']);assert(/^[0-9a-f]+$/.test(id));
    const [container]=JSON.parse(await run(['inspect',id]));
    assert.equal(container.Config.Labels['com.docker.compose.project'],'secure-integrity-demo');
    assert.equal(container.Config.Labels['com.docker.compose.service'],database+'-db');
    assert.equal(path.resolve(container.Config.Labels['com.docker.compose.project.working_dir']),root);
    assert.equal(await sql(database,'SELECT current_database();'),database+'_db');
    report[database+'Identity']={container:id,volumes:container.Mounts.filter(m=>m.Type==='volume').map(m=>m.Name),serverVersion:await sql(database,'SHOW server_version;')};
    assert.equal(await sql(database,"SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid();"),'0','Other database connections still exist');
  }
  const expected=digest(await readFile(path.join(root,'transaction-security-module/src/main/resources/settlement-schema.sql')));
  assert.equal(await sql('settlement',"SELECT sha256 FROM demo_schema_versions WHERE name='settlement-schema';"),expected,'Legacy schema differs from reviewed source');
  const names=Object.keys(tables).map(t=>`'${t}'`).join(',');
  assert.equal(await sql('audit',`SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN (${names});`),resumePath?'7':'0','Unexpected target accounting tables; refusing to overwrite or re-import');
  assert.equal(await sql('audit',"SELECT count(*) FROM demo_schema_versions WHERE name='settlement-schema';"),'0');
  const beforeAudit=await fingerprints('audit',auditTables);
  const beforeVersions=await sql('audit','SELECT row_to_json(t) FROM (SELECT * FROM demo_schema_versions ORDER BY name) t;');
  const before=await fingerprints('settlement',tables),structure=await metadata('settlement');
  report.sourceBefore=before;report.auditBefore=beforeAudit;report.versionsBefore=beforeVersions;
  if(resumePath) {
    const prior=JSON.parse(await readFile(resumePath,'utf8'));
    assert.equal(prior.passed,false);assert(prior.backups.length>=3);
    assert.deepEqual(prior.settlementIdentity.volumes,report.settlementIdentity.volumes);
    assert.deepEqual(prior.auditIdentity.volumes,report.auditIdentity.volumes);
    for(const item of prior.backups) {
      assert(/^[a-z-]+\.dump$/.test(item.filename));
      const bytes=await readFile(path.join(path.dirname(resumePath),item.filename));
      assert.equal(bytes.length,item.bytes);assert.equal(digest(bytes),item.sha256,'Previous backup digest differs');
    }
    // Compare original audit COPY data with the current four audit tables using the same tool.
    const originalRemote='/tmp/'+runId+'-original-audit.dump';
    await run([...compose,'cp',path.join(path.dirname(resumePath),'audit-before.dump'),'audit-db:'+originalRemote]);
    const current=await backup('audit','audit-resume-check.dump',Object.keys(auditTables));
    const currentRemote='/tmp/'+runId+'-current-audit.dump';
    await run([...compose,'cp',current,'audit-db:'+currentRemote]);
    for(const table of Object.keys(auditTables)) {
      const extract=async archive=>{
        const data=await run([...compose,'exec','-T','audit-db','pg_restore','--data-only','--table',table,'--file','-',archive]);
        const copies=data.match(/COPY [\s\S]*?\n\\\.\r?\n/g);assert.equal(copies?.length,1,'Expected one complete COPY block');return copies[0];
      };
      assert.equal(await extract(currentRemote),await extract(originalRemote),'Original audit backup differs: '+table);
    }
    report.resumedFrom=path.basename(path.dirname(resumePath));
    report.priorReportSha256=digest(await readFile(resumePath));
  } else {
  await backup('audit','audit-before.dump');
  await backup('settlement','settlement-before.dump');
  const transfer=await backup('settlement','accounting-tables.dump',Object.keys(tables));
  await writeFile(path.join(output,'report.json'),JSON.stringify(report,null,2)+'\n');
  const remote='/tmp/'+runId+'-import.dump';
  await run([...compose,'cp',transfer,'audit-db:'+remote]);
  await run([...compose,'exec','-T','audit-db','pg_restore','-U','audit_admin','-d','audit_db',
    '--single-transaction','--exit-on-error','--no-owner','--no-privileges',remote]);
  }
  const after=await fingerprints('audit',tables);
  assert.deepEqual(after,before,'Accounting rows changed during migration');
  assert.deepEqual(normalizedMetadata(await metadata('audit')),normalizedMetadata(structure),'Constraints or indexes changed');
  assert.deepEqual(await fingerprints('audit',auditTables),beforeAudit,'Existing audit evidence changed');
  assert.equal(await sql('audit','SELECT row_to_json(t) FROM (SELECT * FROM demo_schema_versions ORDER BY name) t;'),beforeVersions);
  assert.equal(await sql('audit',`SELECT count(*) FROM ledger_journal j LEFT JOIN (SELECT operation_id,count(*) AS legs,sum(amount_cents) AS net FROM ledger_postings GROUP BY operation_id) p USING(operation_id) WHERE p.legs IS DISTINCT FROM 2::bigint OR p.net IS DISTINCT FROM 0::numeric;`),'0','Journal/posting invariant failed');
  // Marker is the cutover gate. If any earlier check failed, normal startup remains blocked.
  await sql('audit',`BEGIN; INSERT INTO demo_schema_versions(name,sha256) VALUES('settlement-schema','${expected}'); COMMIT;`);
  report.tables=after;report.auditPreserved=beforeAudit;report.schemaAndIndexesPreserved=true;
  await run([...compose,'stop','settlement-db']);
  report.passed=true;report.completedAt=new Date().toISOString();
  console.log('PASS: seven tables copied with identical rows, indexes and constraints; existing audit evidence unchanged.');
  console.log('Legacy Settlement container stopped; volume and verified backup archives retained.');
} catch(error) {report.error=error.message;console.error(error.message);process.exitCode=1;}
finally {await writeFile(path.join(output,'report.json'),JSON.stringify(report,null,2)+'\n');console.log('Migration report: '+path.join(output,'report.json'));}
