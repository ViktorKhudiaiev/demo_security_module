// Trusted local teaching harness. Only server-owned fixtures may be changed or observed.
import fs from 'node:fs/promises';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';

export const ROOT=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const LEDGER='00000000-0000-0000-0000-000000000010';
const TREASURY='00000000-0000-0000-0000-000000000001';
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const lit=value=>"'"+String(value).replaceAll("'","''")+"'";
const bounded=value=>String(value??'').slice(0,1000);
export function validateAction(value){
 if(!value||Array.isArray(value)||Object.keys(value).length!==1||!['setup','valid','forged','new-demo'].includes(value.action))throw new Error('Choose one supported fixed action.');
 return value.action;
}
export function validateManifest(m){
 if(m?.version!==1||![m.runId,m.alice,m.bob,m.forgedId,m.forgedEvent].every(x=>typeof x==='string'&&UUID.test(x)))throw new Error('Invalid local fixture manifest.');
 if(new Set([m.alice,m.bob,m.forgedId,m.forgedEvent]).size!==4||[m.alice,m.bob].includes(TREASURY))throw new Error('Invalid fixture identity.');
 for(const k of ['fundingId','validId'])if(m[k]!==null&&!UUID.test(m[k]))throw new Error('Invalid operation identity.');
 if(!Number.isSafeInteger(m.createdAtMicros)||m.createdAtMicros<=0||typeof m.initialized!=='boolean')throw new Error('Invalid fixture state.');
 return m;
}
function sameFields(op,m,type,amount,from,to,key){return op?.ledgerId===LEDGER&&op.type===type&&op.amountMinor===amount&&op.fromAccountId===from&&op.toAccountId===to&&op.currency==='USD'&&op.idempotencyKey===key;}
const sameOperation=(a,b)=>!!a&&!!b&&JSON.stringify(Object.entries(a).sort())===JSON.stringify(Object.entries(b).sort());
const parseOperation=value=>{try{return JSON.parse(value);}catch{return null;}};
const newManifest=()=>({version:1,runId:randomUUID(),alice:randomUUID(),bob:randomUUID(),forgedId:randomUUID(),forgedEvent:randomUUID(),createdAtMicros:Date.now()*1000,fundingId:null,validId:null,initialized:false});
export function assess(snapshot,m){
 const s=snapshot.settlement,a=snapshot.audit,p=snapshot.primary;
 const result=id=>s.results.find(x=>x.operation_id===id),receipt=id=>a.receipts.find(x=>x.id===id);
 const postings=id=>s.postings.filter(x=>x.operation_id===id);
 const event=(id,type)=>a.events.some(x=>x.operation_id===id&&x.event_type===type);
 const relayed=id=>s.outbox.some(x=>x.operation_id===id&&x.relayed_at_micros!==null);
 const pair=(id,from,to,amount)=>{const rows=postings(id);return rows.length===2&&rows.some(x=>x.leg===0&&x.account_id===from&&x.amount_cents===-amount)&&rows.some(x=>x.leg===1&&x.account_id===to&&x.amount_cents===amount);};
 const key=kind=>`live-demo:${m.runId}:${kind}`;
 const completion=(id,type,amount,from,to,kind)=>{
  const r=result(id),i=receipt(id),source=p.transactions.find(x=>x.id===id);
  return !!(id&&r?.status==='COMPLETED'&&r.operation?.id===id&&i?.operation?.id===id&&
   sameFields(r.operation,m,type,amount,from,to,key(kind))&&sameFields(i.operation,m,type,amount,from,to,key(kind))&&
   sameOperation(r.operation,i.operation)&&sameOperation(parseOperation(source?.operation_json),i.operation)&&r.content_hash===i.content_hash&&
   source.content_hash===i.content_hash&&source.mac===i.mac&&source.key_id===i.key_id&&source.created_at_micros===i.operation.createdAtMicros&&
   pair(id,from,to,amount)&&event(id,'VERIFIED')&&event(id,'COMPLETED')&&relayed(id));
 };
 const funded=completion(m.fundingId,'FUNDING',100000,TREASURY,m.alice,'funding');
 const valid=completion(m.validId,'TRANSFER',2500,m.alice,m.bob,'valid');
 const expected=[valid?97500:100000,valid?2500:0];
 const balancesMatch=s.accounts.find(x=>x.id===m.alice)?.balance_cents===expected[0]&&s.accounts.find(x=>x.id===m.bob)?.balance_cents===expected[1];
 const r=result(m.forgedId);
 const forged=!!(funded&&r?.status==='QUARANTINED'&&r.reason==='No independent issuance receipt'&&!receipt(m.forgedId)&&
  p.transactions.some(x=>x.id===m.forgedId)&&postings(m.forgedId).length===0&&!s.journal.some(x=>x.operation_id===m.forgedId)&&
  event(m.forgedId,'INTEGRITY_INCIDENT')&&event(m.forgedId,'QUARANTINED')&&relayed(m.forgedId)&&balancesMatch);
 return {funding:{passed:funded&&balancesMatch},valid:{passed:valid&&balancesMatch},forged:{passed:forged},balancesMatch};
}

export class LiveBridge {
 constructor(){this.manifest=null;this.secrets=null;this.running=null;this.lastError=null;this.lastAction=null;this.cache=null;this.readPromise=null;}
 async load(){
  this.secrets=JSON.parse(await fs.readFile(path.join(ROOT,'.local/secrets.json'),'utf8'));
  for(const name of ['APP_API_TOKEN','KEY_VERIFIER_TOKEN'])if(typeof this.secrets[name]!=='string'||this.secrets[name].length<20)throw new Error('Local service configuration is incomplete.');
  const candidates=[process.env.DEMO_DOCKER_PATH,process.env.LOCALAPPDATA&&path.join(process.env.LOCALAPPDATA,'Programs/DockerDesktop/resources/bin/docker.exe'),'C:/Program Files/Docker/Docker/resources/bin/docker.exe'].filter(Boolean);
  for(const candidate of candidates){try{await fs.access(candidate);this.docker=candidate;break;}catch{if(process.env.DEMO_DOCKER_PATH===candidate)throw new Error('Configured Docker executable is unavailable.');}}
  if(!this.docker)throw new Error('Docker executable is unavailable.');
  this.statePath=path.join(ROOT,'.local/live-demo/state.json');
  try{this.manifest=validateManifest(JSON.parse(await fs.readFile(this.statePath,'utf8')));}catch(error){if(error.code!=='ENOENT')throw error;}
 }
 async persist(){await fs.mkdir(path.dirname(this.statePath),{recursive:true});const temporary=this.statePath+'.tmp';await fs.writeFile(temporary,JSON.stringify(this.manifest,null,2)+'\n');await fs.rename(temporary,this.statePath);}
 key(kind){return `live-demo:${this.manifest.runId}:${kind}`;}
 async health(){return Promise.all([['Application',8080],['Key service',8081],['Processor',8082]].map(async([name,port])=>{try{const r=await fetch(`http://127.0.0.1:${port}/health`,{signal:AbortSignal.timeout(2000)});const body=await r.json();return {name,port,ready:r.ok&&body.status==='UP'&&body.auditHealthy!==false,...(port===8082?{queue:body.queue}:{})};}catch{return {name,port,ready:false};}}));}
 async api(port,route,method='GET',body,key){
  const token=port===8080?this.secrets.APP_API_TOKEN:this.secrets.KEY_VERIFIER_TOKEN;
  const r=await fetch(`http://127.0.0.1:${port}${route}`,{method,headers:{authorization:`Bearer ${token}`,...(body?{'content-type':'application/json'}:{}),...(key?{'idempotency-key':key}:{})},body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(10000)});
  if(r.status===404)return null;
  if(!r.ok)throw new Error('Dependency request is unavailable; retry the same action.');
  return r.json();
 }
 dockerSql(store,sql,readOnly=true){
  const configuration={primary:['primary-db','primary_admin','primary_db'],audit:['audit-db','audit_admin','audit_db'],settlement:['audit-db','audit_admin','audit_db']}[store];
  if(!configuration||(!readOnly&&store!=='primary'))throw new Error('Unsupported database action.');
  const [service,user,database]=configuration;
  const args=['compose','--project-name','secure-integrity-demo','--project-directory',ROOT,'--env-file',path.join(ROOT,'.local/compose.env'),'-f',path.join(ROOT,'compose.yaml'),'exec','-T',service,'psql','-X','-q','-t','-A','-1','-v','ON_ERROR_STOP=1','-U',user,'-d',database];
  return new Promise((resolve,reject)=>{
   const child=spawn(this.docker,args,{cwd:ROOT,shell:false,windowsHide:true,stdio:['pipe','pipe','pipe']});let output='',bytes=0,ended=false;
   const finish=(error,value)=>{if(ended)return;ended=true;clearTimeout(timeout);error?reject(error):resolve(value);};
   const timeout=setTimeout(()=>{child.kill();finish(new Error('Database observation timed out.'));},12000);
   child.on('error',()=>finish(new Error('Docker could not start.')));
   child.stdout.on('data',chunk=>{bytes+=chunk.length;if(bytes>256000){child.kill();finish(new Error('Database response exceeded the lab limit.'));}else output+=chunk;});
   child.stderr.on('data',chunk=>{bytes+=chunk.length;if(bytes>256000){child.kill();finish(new Error('Database response exceeded the lab limit.'));}});
   child.on('close',code=>finish(code===0?null:new Error('Database observation or fixed fixture write failed.'),output.trim()));
   child.stdin.on('error',()=>{});child.stdin.end(`${readOnly?'SET TRANSACTION READ ONLY;':''} SET LOCAL statement_timeout='5000ms';\n${sql}`);
  });
 }
 async snapshot(){
  if(this.readPromise)return this.readPromise;
  if(this.cache&&Date.now()-this.cache.time<750)return this.cache.value;
  this.readPromise=this.readSnapshot().then(value=>{this.cache={time:Date.now(),value};return value;}).finally(()=>{this.readPromise=null;});return this.readPromise;
 }
 async readSnapshot(){
  const m=this.manifest;if(!m)return null;
  const ids=[m.fundingId,m.validId,m.forgedId].filter(Boolean).map(lit).join(',');const accounts=[m.alice,m.bob].map(lit).join(',');
  const array=query=>`COALESCE((SELECT json_agg(t) FROM (${query}) t),'[]'::json)`;
  const primary=`SELECT json_build_object('database',current_database(),'observedAt',clock_timestamp(),'transactions',${array(`SELECT id,substring(operation_json,1,16384) AS operation_json,key_id,content_hash,mac,created_at_micros FROM transactions WHERE id IN (${ids})`)},'outbox',${array(`SELECT id,transaction_id,event_type,processed_at_micros FROM transaction_outbox WHERE transaction_id IN (${ids})`)},'statusEvents',${array(`SELECT transaction_id,status,substring(reason,1,512) AS reason,created_at_micros FROM transaction_status_events WHERE transaction_id IN (${ids}) ORDER BY created_at_micros`) });`;
  const audit=`SELECT json_build_object('database',current_database(),'observedAt',clock_timestamp(),'receipts',${array(`SELECT id,sequence,signed_json::jsonb->'operation' AS operation,signed_json::jsonb->>'keyId' AS key_id,signed_json::jsonb->>'contentHash' AS content_hash,signed_json::jsonb->>'mac' AS mac,issued_at_micros FROM issuance_receipts WHERE id IN (${ids})`)},'events',${array(`SELECT seq,operation_id,event_type,substring(detail,1,1000) AS detail,created_at_micros FROM audit_events WHERE operation_id IN (${ids}) ORDER BY seq`)});`;
  const settlement=`SELECT json_build_object('database',current_database(),'observedAt',clock_timestamp(),'accounts',${array(`SELECT id,name,status,balance_cents FROM accounts WHERE id IN (${accounts}) ORDER BY id`)},'jobs',${array(`SELECT operation_id,status,attempts FROM operation_jobs WHERE operation_id IN (${ids})`)},'results',${array(`SELECT operation_id,status,substring(reason,1,512) AS reason,content_hash,signed_json::jsonb->'operation' AS operation,completed_at_micros FROM operation_results WHERE operation_id IN (${ids})`)},'journal',${array(`SELECT operation_id,operation_type,content_hash FROM ledger_journal WHERE operation_id IN (${ids})`)},'postings',${array(`SELECT operation_id,leg,account_id,amount_cents FROM ledger_postings WHERE operation_id IN (${ids}) ORDER BY operation_id,leg`)},'outbox',${array(`SELECT operation_id,status,relayed_at_micros FROM settlement_outbox WHERE operation_id IN (${ids})`)});`;
  const queries={primary,audit,settlement};const entries=await Promise.all(Object.entries(queries).map(async([name,sql])=>{const data=JSON.parse(await this.dockerSql(name,sql));if(data.database!==(name==='primary'?'primary_db':'audit_db'))throw new Error('Unexpected database identity.');return [name,data];}));return Object.fromEntries(entries);
 }
 async waitFor(alias){
  const deadline=Date.now()+45000;
  while(Date.now()<deadline){this.cache=null;const snapshot=await this.snapshot();if(assess(snapshot,this.manifest)[alias].passed)return;await sleep(800);}
  throw new Error('Still pending or evidence differs from expectations. Retry the same action; no new operation identity will be created.');
 }
 async issue(alias,body){
  const key=this.key(alias);let response;
  try{response=await this.api(8080,alias==='funding'?'/api/fundings':'/api/transfers','POST',body,key);}
  catch{const receipt=await this.api(8081,`/v1/issuances?ledgerId=${LEDGER}&idempotencyKey=${encodeURIComponent(key)}`);if(receipt){this.manifest[alias+'Id']=receipt.operation.id;await this.persist();}throw new Error('The response was uncertain. Retry this same action to reconcile its original identity.');}
  if(!response||!UUID.test(response.id))throw new Error('Unexpected operation response.');this.manifest[alias+'Id']=response.id;await this.persist();
 }
 async setup(){
  if(!this.manifest)this.manifest=newManifest();
  // Always persist before mutations, including a retry after an initial write failure.
  await this.persist();
  const m=this.manifest;
  for(const [id,label] of [[m.alice,'Alice'],[m.bob,'Bob']])await this.api(8080,'/api/accounts','POST',{id,name:`live-demo-${m.runId.slice(0,8)}-${label}`,initialBalanceCents:0},this.key('account-'+label));
  await this.issue('funding',{toAccountId:m.alice,amountCents:100000,currency:'USD'});await this.waitFor('funding');m.initialized=true;await this.persist();
 }
 async valid(){const m=this.manifest;await this.issue('valid',{fromAccountId:m.alice,toAccountId:m.bob,amountCents:2500,currency:'USD'});await this.waitFor('valid');}
 async archive(){
  const dir=path.join(path.dirname(this.statePath),'history');await fs.mkdir(dir,{recursive:true});const file=path.join(dir,this.manifest.runId+'.json'),data=JSON.stringify(this.manifest,null,2)+'\n';
  try{await fs.writeFile(file,data,{flag:'wx'});}catch(error){if(error.code!=='EEXIST'||await fs.readFile(file,'utf8')!==data)throw new Error('Could not retain the previous fixture manifest.');}
 }
 async newDemo(){
  // A repeated request before preparation is a no-op, not another fixture generation.
  if(!this.manifest?.initialized)return;
  const checks=assess(await this.snapshot(),this.manifest);
  if(!['funding','valid','forged'].every(k=>checks[k].passed))throw new Error('Complete and confirm both experiments before creating the next demo.');
  await this.archive();const previous=this.manifest;this.manifest=newManifest();
  try{await this.persist();}catch(error){this.manifest=previous;throw error;}
 }
 async forged(){
  const m=this.manifest;const operation={id:m.forgedId,schemaVersion:1,ledgerId:LEDGER,type:'TRANSFER',fromAccountId:m.alice,toAccountId:m.bob,amountMinor:2500,currency:'USD',createdAtMicros:m.createdAtMicros,relatedOperationId:null,idempotencyKey:this.key('forged')};
  // Every value originates from the validated private fixture manifest or a fixed constant.
  await this.dockerSql('primary',`INSERT INTO transactions(id,operation_json,key_id,content_hash,mac,created_at_micros) VALUES(${lit(m.forgedId)},${lit(JSON.stringify(operation))},'live-demo-forged-key',repeat('0',64),repeat('0',64),${m.createdAtMicros}) ON CONFLICT DO NOTHING;\nINSERT INTO transaction_outbox(id,transaction_id,event_type,created_at_micros) VALUES(${lit(m.forgedEvent)},${lit(m.forgedId)},'LIVE_DEMO_FORGED',${m.createdAtMicros}) ON CONFLICT DO NOTHING;`,false);
  await this.waitFor('forged');
 }
 start(action){
  validateAction({action});
  if(this.running){if(this.running===action)return;throw new Error('Another lab action is in progress.');}
  if(!['setup','new-demo'].includes(action)&&!this.manifest?.initialized)throw new Error('Prepare the dedicated accounts first.');
  this.running=action;this.lastAction=action;this.lastError=null;
  this.task=(async()=>{const health=await this.health();if(!health.every(x=>x.ready))throw new Error('Start the healthy local Java/PostgreSQL stack before continuing.');await this[action==='new-demo'?'newDemo':action]();})().catch(error=>{this.lastError=bounded(error.message);}).finally(()=>{this.running=null;this.cache=null;});
 }
 async state(){
  const health=await this.health();let snapshot=null,observationError=null;
  if(this.manifest){try{snapshot=await this.snapshot();}catch{observationError='Database evidence is unavailable. No successful result is inferred.';}}
  return {health,runId:this.manifest?.runId??null,running:this.running,lastAction:this.lastAction,error:this.lastError,observationError,prepared:!!this.manifest?.initialized,
   fixtures:this.manifest?{alice:this.manifest.alice,bob:this.manifest.bob,operations:{funding:this.manifest.fundingId,valid:this.manifest.validId,forged:this.manifest.forgedId}}:null,
   snapshot,checks:snapshot?assess(snapshot,this.manifest):null,observedAt:new Date().toISOString()};
 }
}
