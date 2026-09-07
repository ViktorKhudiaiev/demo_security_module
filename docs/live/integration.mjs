// Explicit integration run: only the helper's dedicated persistent lab fixtures are mutated.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import {ROOT} from './bridge.mjs';
const base='http://127.0.0.1:8090',startedAt=new Date().toISOString(),checks=[];
const capability=(await (await fetch(base+'/api/session')).json()).capability;
const headers={Origin:base,'X-Demo-Session':capability,'Content-Type':'application/json'};
async function state(){const r=await fetch(base+'/api/state',{headers,signal:AbortSignal.timeout(20000)});assert.equal(r.status,200);return r.json();}
async function run(action,alias){
 const r=await fetch(base+'/api/action',{method:'POST',headers,body:JSON.stringify({action})});assert.equal(r.status,202);
 const deadline=Date.now()+65000;while(Date.now()<deadline){const s=await state();if(!s.running){assert.equal(s.error,null,s.error??'');assert.equal(s.checks?.[alias]?.passed,true,`${alias} evidence not confirmed`);return s;}await new Promise(resolve=>setTimeout(resolve,1000));}throw new Error('Lab action deadline exceeded.');
}
const record=(name,extra={})=>{checks.push({name,passed:true,...extra});console.log('PASS '+name);};
let finalState;
try{
 const ready=await state();assert.ok(ready.health.every(x=>x.ready));record('All three local Java services ready');
 const queue=ready.health.find(x=>x.port===8082).queue;assert.equal(queue.status,'UP');assert.equal(queue.transport,'embedded-vm');assert.equal(queue.persistent,true);record('Processor reports a persistent embedded VM broker');
 await run('setup','funding');record('Authenticated funding recorded: 100000 cents; current protected balances reconciled');
 const valid=await run('valid','valid'),id=valid.fixtures.operations.valid;record('Authenticated $25 transfer: exact source, receipt, result, two postings and relayed audit');
 assert.equal(valid.snapshot.primary.database,'primary_db');assert.equal(valid.snapshot.audit.database,'audit_db');assert.equal(valid.snapshot.settlement.database,'audit_db');record('Two physical databases: evidence and accounting both read audit_db');
 const repeated=await run('valid','valid');assert.equal(repeated.fixtures.operations.valid,id);assert.equal(repeated.snapshot.settlement.postings.filter(x=>x.operation_id===id).length,2);record('Repeated valid action retains UUID and exactly two postings');
 const before=repeated.snapshot.settlement.accounts.map(x=>[x.id,x.balance_cents]).sort();finalState=await run('forged','forged');assert.deepEqual(finalState.snapshot.settlement.accounts.map(x=>[x.id,x.balance_cents]).sort(),before);record('Direct-primary forgery: no receipt, quarantine, zero postings, balances unchanged');
 const forgedId=finalState.fixtures.operations.forged;const repeatedForgery=await run('forged','forged');assert.equal(repeatedForgery.fixtures.operations.forged,forgedId);record('Repeated forged action retains UUID and no financial effect');finalState=repeatedForgery;
 for(const [name,body,h,expected] of [['Foreign-origin action rejected',{action:'valid'},{...headers,Origin:'https://example.invalid'},403],['Missing session capability rejected',{action:'valid'},{Origin:base,'Content-Type':'application/json'},403],['Arbitrary SQL/action fields rejected',{action:'forged',sql:'SELECT 1'},headers,400]]){const r=await fetch(base+'/api/action',{method:'POST',headers:h,body:JSON.stringify(body)});assert.equal(r.status,expected);record(name);}
 const next=await fetch(base+'/api/action',{method:'POST',headers,body:JSON.stringify({action:'new-demo'})});assert.equal(next.status,202);let fresh;
 for(let i=0;i<60;i++){fresh=await state();if(!fresh.running)break;await new Promise(r=>setTimeout(r,1000));}assert.equal(fresh.running,null);assert.equal(fresh.error,null);assert.notEqual(fresh.runId,finalState.runId);assert.equal(fresh.prepared,false);assert.equal(fresh.snapshot.settlement.accounts.length,0);
 const archived=JSON.parse(await fs.readFile(path.join(ROOT,'.local/live-demo/history',finalState.runId+'.json'),'utf8'));assert.equal(archived.validId,id);assert.equal(archived.forgedId,forgedId);record('New demonstration retains completed ownership and creates unprepared fresh identities');
 const duplicate=await fetch(base+'/api/action',{method:'POST',headers,body:JSON.stringify({action:'new-demo'})});assert.equal(duplicate.status,202);let duplicateState;for(let i=0;i<20;i++){duplicateState=await state();assert.equal(duplicateState.runId,fresh.runId);if(!duplicateState.running)break;await new Promise(r=>setTimeout(r,500));}assert.equal(duplicateState.running,null);assert.equal(duplicateState.error,null);record('Repeated new-demo request does not replace the fresh identities');
 const report={kind:'local-live-lab-integration',startedAt,finishedAt:new Date().toISOString(),passed:true,checks,fixtures:finalState.fixtures,nextDemoRunId:fresh.runId,balances:finalState.snapshot.settlement.accounts.map(({id,balance_cents})=>({id,balanceCents:balance_cents})),operationChecks:finalState.checks,limits:['Simulated USD; no external payments.','Actions reuse identities within a demonstration. A new demonstration preserves old records.','Three logical table groups in two databases are read separately, not as a global atomic snapshot.','This is functional integration, not a new 20 TPS benchmark or browser UI test.','Local software-key stack; host and helper remain trusted.']};
 const dir=path.join(ROOT,'.local/live-demo');await fs.mkdir(dir,{recursive:true});const file=path.join(dir,'verification-'+startedAt.replaceAll(':','-')+'.json');await fs.writeFile(file,JSON.stringify(report,null,2)+'\n');console.log('Report: '+file);
}catch(error){console.error('FAIL '+error.message);process.exitCode=1;}
