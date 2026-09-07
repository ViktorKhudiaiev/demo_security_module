import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import vm from 'node:vm';
import {randomUUID,createHash} from 'node:crypto';
import {once} from 'node:events';
import {LiveBridge,assess,validateAction,validateManifest} from './bridge.mjs';
import {authorize,createLabServer} from './server.mjs';
import {flowSteps,sequenceMessages,participants} from '../build/walkthrough-data.mjs';
import {architectureSvg,sequenceSvg} from '../build/build-walkthrough-diagrams.mjs';

const cap='a'.repeat(64),origin='http://127.0.0.1:8090';
const manifest=()=>({version:1,runId:randomUUID(),alice:randomUUID(),bob:randomUUID(),forgedId:randomUUID(),forgedEvent:randomUUID(),createdAtMicros:1770000000000000,fundingId:randomUUID(),validId:randomUUID(),initialized:true});
function evidence(m){
 const p={transactions:[],outbox:[],statusEvents:[]},a={receipts:[],events:[]},s={accounts:[{id:m.alice,balance_cents:97500},{id:m.bob,balance_cents:2500}],jobs:[],results:[],journal:[],postings:[],outbox:[]};
 for(const [kind,type,amount,from,to] of [['funding','FUNDING',100000,'00000000-0000-0000-0000-000000000001',m.alice],['valid','TRANSFER',2500,m.alice,m.bob]]){
  const id=m[kind+'Id'],op={id,ledgerId:'00000000-0000-0000-0000-000000000010',type,amountMinor:amount,fromAccountId:from,toAccountId:to,currency:'USD',idempotencyKey:`live-demo:${m.runId}:${kind}`,schemaVersion:1,createdAtMicros:m.createdAtMicros,relatedOperationId:null};
  a.receipts.push({id,operation:op,content_hash:'hash',mac:'tag',key_id:'key'});p.transactions.push({id,operation_json:JSON.stringify(op),content_hash:'hash',mac:'tag',key_id:'key',created_at_micros:op.createdAtMicros});s.results.push({operation_id:id,status:'COMPLETED',operation:op,content_hash:'hash'});
  s.postings.push({operation_id:id,leg:0,account_id:from,amount_cents:-amount},{operation_id:id,leg:1,account_id:to,amount_cents:amount});s.journal.push({operation_id:id});s.outbox.push({operation_id:id,relayed_at_micros:1});for(const t of ['VERIFIED','COMPLETED'])a.events.push({operation_id:id,event_type:t});
 }
 p.transactions.push({id:m.forgedId,operation_json:'null'});s.results.push({operation_id:m.forgedId,status:'QUARANTINED',reason:'No independent issuance receipt'});s.outbox.push({operation_id:m.forgedId,relayed_at_micros:1});for(const t of ['INTEGRITY_INCIDENT','QUARANTINED'])a.events.push({operation_id:m.forgedId,event_type:t});
 return {primary:p,audit:a,settlement:s};
}
function request(overrides={}){return {method:'POST',url:'/api/action',rawHeaders:['Host','127.0.0.1:8090','Origin',origin],headers:{host:'127.0.0.1:8090',origin,'x-demo-session':cap,'sec-fetch-site':'same-origin'},...overrides};}
test('exact loopback origin and capability reject browser cross-origin and malformed authority',()=>{
 assert.equal(authorize(request(),{capability:cap},true),true);
 for(const change of [{origin:'https://evil.example'},{origin:'null'},{origin:undefined},{host:'localhost:8090'},{host:'evil.example'},{'x-forwarded-host':'127.0.0.1:8090'},{'sec-fetch-site':'cross-site'},{'x-demo-session':'x'},{'x-demo-session':'é'.repeat(64)}])assert.equal(authorize(request({headers:{...request().headers,...change}}),{capability:cap},true),false);
 for(const url of ['//evil.example/api/action','http://127.0.0.1:8090/api/action'])assert.equal(authorize(request({url}),{capability:cap},true),false);
 assert.equal(authorize(request({rawHeaders:[...request().rawHeaders,'Host','127.0.0.1:8090']}),{capability:cap}),false);
 assert.equal(authorize(request({rawHeaders:[...request().rawHeaders,'Origin',origin]}),{capability:cap}),false);
});
test('only fixed actions and validated server-owned manifests are accepted',()=>{
 for(const action of ['setup','valid','forged','new-demo'])assert.equal(validateAction({action}),action);
 for(const v of [null,[],{},'valid',{action:'sql'},{action:'valid',amount:1},{action:'forged',sql:'DROP DATABASE primary_db'}])assert.throws(()=>validateAction(v));
 assert.equal(validateManifest(manifest()).version,1);
 for(const field of ['runId','alice','bob','forgedId','forgedEvent','validId'])assert.throws(()=>validateManifest({...manifest(),[field]:"x' OR true--"}));
 assert.throws(()=>validateManifest({...manifest(),alice:'00000000-0000-0000-0000-000000000001'}));
});
test('evidence requires exact content, paired postings, independent audit and expected balances',()=>{
 const m=manifest();assert.equal(assess(evidence(m),m).valid.passed,true);assert.equal(assess(evidence(m),m).forged.passed,true);
 const corruptions=[s=>s.primary.transactions.find(x=>x.id===m.validId).operation_json='null',s=>s.primary.transactions.find(x=>x.id===m.validId).mac='changed',s=>s.settlement.results.find(x=>x.operation_id===m.validId).content_hash='changed',s=>s.settlement.postings.pop(),s=>s.settlement.accounts[0].balance_cents++,s=>s.audit.events=s.audit.events.filter(x=>x.event_type!=='VERIFIED'),s=>s.settlement.outbox.find(x=>x.operation_id===m.validId).relayed_at_micros=null];
 for(const corrupt of corruptions){const s=evidence(m);corrupt(s);assert.equal(assess(s,m).valid.passed,false);}
 const differentTime=evidence(m);differentTime.primary.transactions.find(x=>x.id===m.validId).created_at_micros++;assert.equal(assess(differentTime,m).valid.passed,false);
 for(const corrupt of [s=>s.settlement.postings.push({operation_id:m.forgedId}),s=>s.audit.receipts.push({id:m.forgedId}),s=>s.settlement.results.find(x=>x.operation_id===m.forgedId).status='COMPLETED',s=>s.settlement.journal.push({operation_id:m.forgedId})]){const s=evidence(m);corrupt(s);assert.equal(assess(s,m).forged.passed,false);}
});
test('initial persistence failure cannot allow mutations before a successful retry save',async()=>{
 const b=new LiveBridge(),calls=[];let fail=true;b.persist=async()=>{calls.push('persist');if(fail){fail=false;throw new Error('disk failure');}};b.api=async()=>{calls.push('mutation');};b.issue=async()=>{calls.push('issue');};b.waitFor=async()=>{};
 await assert.rejects(b.setup());assert.deepEqual(calls,['persist']);const id=b.manifest.runId;calls.length=0;await b.setup();assert.equal(calls[0],'persist');assert.equal(b.manifest.runId,id);assert.ok(calls.includes('mutation'));
});
test('ambiguous issuance preserves original idempotency key and receipt identity',async()=>{
 const b=new LiveBridge();b.manifest=manifest();b.manifest.validId=null;const keys=[],id=randomUUID();let lost=true;b.persist=async()=>{};
 b.api=async(port,route,method,body,key)=>{if(port===8081)return {operation:{id}};keys.push(key);if(lost){lost=false;throw new Error('lost response');}return {id};};
 await assert.rejects(b.issue('valid',{}),/uncertain/);assert.equal(b.manifest.validId,id);await b.issue('valid',{});assert.equal(keys[0],keys[1]);assert.equal(b.manifest.validId,id);
});
test('action lock is acquired synchronously and repeated clicks do not enqueue duplicates',async()=>{
 const b=new LiveBridge();b.manifest=manifest();let release,calls=0;b.health=()=>new Promise(r=>{release=r;});b.valid=async()=>{calls++;};b.start('valid');b.start('valid');assert.throws(()=>b.start('forged'),/in progress/);release([{ready:true}]);await b.task;assert.equal(calls,1);assert.equal(b.running,null);
});
test('a fresh demo archives completed ownership before publishing new identities; retries are no-ops',async()=>{
 const b=new LiveBridge(),m=manifest(),calls=[];b.manifest=m;b.snapshot=async()=>evidence(m);b.archive=async()=>calls.push('archive');b.persist=async()=>calls.push('persist');await b.newDemo();assert.deepEqual(calls,['archive','persist']);assert.notEqual(b.manifest.runId,m.runId);assert.equal(b.manifest.initialized,false);const next=b.manifest.runId;await b.newDemo();assert.equal(b.manifest.runId,next);assert.equal(calls.length,2);
});
test('a fresh demo cannot discard pending work and a save failure restores prior ownership',async()=>{
 const b=new LiveBridge(),m=manifest();b.manifest=m;b.snapshot=async()=>{const s=evidence(m);s.settlement.results=[];return s;};await assert.rejects(b.newDemo(),/Complete/);assert.equal(b.manifest,m);b.snapshot=async()=>evidence(m);b.archive=async()=>{};b.persist=async()=>{throw new Error('disk');};await assert.rejects(b.newDemo(),/disk/);assert.equal(b.manifest,m);
});
test('actual HTTP server is read-only on GET and exposes only authenticated fixed actions',async t=>{
 const actions=[],server=createLabServer({state:async()=>({observed:true}),start:a=>actions.push(a)},{port:0,capability:cap});server.listen(0,'127.0.0.1');await once(server,'listening');t.after(()=>new Promise(r=>server.close(r)));const base=`http://127.0.0.1:${server.address().port}`;
 assert.equal((await fetch(base+'/api/session')).status,200);assert.equal((await fetch(base+'/api/state')).status,403);
 const headers={'X-Demo-Session':cap,Origin:base,'Content-Type':'application/json'};
 assert.equal((await fetch(base+'/api/state',{headers})).status,200);assert.deepEqual(actions,[]);
 assert.equal((await fetch(base+'/api/action',{method:'POST',headers,body:JSON.stringify({action:'valid'})})).status,202);assert.deepEqual(actions,['valid']);
 for(const [body,h,status] of [[{action:'valid',sql:'SELECT 1'},headers,400],[{action:'valid'},{...headers,Origin:'https://evil.example'},403],[{action:'valid'},{Origin:base,'Content-Type':'application/json'},403]])assert.equal((await fetch(base+'/api/action',{method:'POST',headers:h,body:JSON.stringify(body)})).status,status);
 assert.equal((await fetch(base+'/.local/secrets.json')).status,404);assert.equal((await fetch(base+'/docs/live/bridge.mjs')).status,404);assert.deepEqual(actions,['valid']);
 for(const [route,target] of [['/','/docs/index.html'],['/docs/customer-demo.html','/docs/index.html'],['/docs/live-demo.html','/docs/live/index.html']]){
  const redirect=await fetch(base+route,{redirect:'manual'});assert.equal(redirect.status,302);assert.equal(redirect.headers.get('location'),target);
 }
 const page=await fetch(base+'/docs/live/index.html');assert.equal(page.status,200);assert.match(page.headers.get('content-security-policy'),/frame-ancestors 'none'/);assert.equal(page.headers.get('access-control-allow-origin'),null);
 for(const route of ['/docs/index.html','/docs/presentation/guide.html','/docs/article/index.html']){const r=await fetch(base+route);assert.equal(r.status,200);const html=await r.text();for(const script of html.matchAll(/<script>([\s\S]*?)<\/script>/g))assert.ok(r.headers.get('content-security-policy').includes('sha256-'+createHash('sha256').update(script[1]).digest('base64')));assert.ok(!html.includes('onclick="'));}
 for(const route of ['/docs/evidence/live-lab-verification-2026-09-05.json','/docs/reference/protocol.md'])assert.equal((await fetch(base+route)).status,200);
 const guide=await (await fetch(base+'/docs/presentation/guide.html')).text();
 for(const match of guide.matchAll(/href="([^"]+\.java)"/g)){
  const source=new URL(match[1],base+'/docs/presentation/guide.html');
  const response=await fetch(source);assert.equal(response.status,200,source.pathname);
  assert.match(await response.text(),/^package com\.demo\./);
 }
 assert.equal((await fetch(base+'/docs/reference/code-structure.md')).status,200);
});
test('diagrams name all participants and preserve receipt-before-source-before-MAC order',()=>{
 assert.equal(flowSteps.length,12);assert.equal(participants.length,7);const svg=architectureSvg();for(const step of flowSteps)for(const node of [step.from,step.to])assert.ok(svg.includes(`id="node-${node}"`));
 const labels=sequenceMessages.map(x=>x[3]);const receipt=labels.findIndex(x=>x.startsWith('Read independent issuance receipt')),source=labels.findIndex(x=>x.startsWith('Read source; compare canonical')),mac=labels.findIndex(x=>x.startsWith('Verify HMAC'));assert.ok(receipt>=0&&receipt<source&&source<mac);assert.ok(sequenceSvg().includes('normal transfer sequence'));
 assert.equal((svg.match(/class="component database"/g)||[]).length,2);assert.ok(svg.includes('EMBEDDED ACTIVEMQ'));
 assert.ok(sequenceSvg().includes('viewBox="0 0 1200 445"'));assert.ok(sequenceMessages.length<=16);
});
test('client tolerates invalid primary JSON and clears unavailable/stale evidence',async()=>{
 const code=await fs.readFile(new URL('./client.js',import.meta.url),'utf8');assert.ok(!code.includes('innerHTML'));
 const nodes=new Map();const make=()=>({textContent:'',className:'',children:[],append(...x){this.children.push(...x);},replaceChildren(...x){this.children=x;}});const get=id=>{if(!nodes.has(id))nodes.set(id,make());return nodes.get(id);};let nextTimer,current;
 const m=manifest();const state=()=>({health:[{name:'Application',port:8080,ready:true}],prepared:true,fixtures:{alice:m.alice,bob:m.bob,operations:{valid:m.validId}},snapshot:evidence(m),checks:{valid:{passed:true}},running:null});current=state();
 const context=vm.createContext({document:{getElementById:get,createElement:make},location:{origin},Intl,fetch:async route=>{if(route==='/api/session')return {ok:true,json:async()=>({capability:cap})};if(current instanceof Error)throw current;return {ok:true,json:async()=>current};},setTimeout:fn=>{nextTimer=fn;}});vm.runInContext(code,context);const flush=async()=>{for(let i=0;i<3;i++)await new Promise(setImmediate);};await flush();
 for(const value of ['null','[]','7','true','"text"','{broken']){current=state();current.snapshot.primary.transactions.find(x=>x.id===m.validId).operation_json=value;await nextTimer();assert.equal(get('outcome').textContent,'COMPLETED');}
 current={...state(),snapshot:null,checks:null};await nextTimer();assert.equal(get('outcome').className,'');assert.match(get('raw-state').textContent,/"snapshot": null/);
 current=state();await nextTimer();current=new Error('offline');await nextTimer();assert.match(get('outcome').textContent,/Stale/);assert.match(get('raw-state').textContent,/"stale": true/);assert.equal(get('valid').disabled,true);
});
