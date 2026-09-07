// Untrusted database values are rendered only through textContent.
(() => {
 const $=id=>document.getElementById(id);let capability=null,state=null,selected='valid',busy=false;
 const money=cents=>new Intl.NumberFormat('en-US',{style:'currency',currency:'USD'}).format(cents/100);
 const short=value=>value==null?'—':String(value).length>25?String(value).slice(0,12)+'…'+String(value).slice(-8):String(value);
 const parse=value=>{try{const op=JSON.parse(value);return op&&typeof op==='object'&&!Array.isArray(op)?op:{};}catch{return {};}};
 function table(target,title,columns,rows){
  const heading=document.createElement('h3');heading.textContent=title;target.append(heading);
  if(!rows.length){const p=document.createElement('p');p.className='empty';p.textContent='No matching rows observed.';target.append(p);return;}
  const wrap=document.createElement('div');wrap.className='table-wrap';const t=document.createElement('table'),head=document.createElement('thead'),tr=document.createElement('tr');
  for(const [label] of columns){const th=document.createElement('th');th.textContent=label;tr.append(th);}head.append(tr);t.append(head);const body=document.createElement('tbody');
  for(const row of rows){const r=document.createElement('tr');for(const [,read] of columns){const c=document.createElement('td');const value=read(row);c.textContent=value??'—';r.append(c);}body.append(r);}t.append(body);wrap.append(t);target.append(wrap);
 }
 function render(){
  if(!state)return;$('health').replaceChildren(...state.health.map(item=>{const span=document.createElement('span');span.className=item.ready?'good':'bad';span.textContent=`${item.name} :${item.port} · ${item.ready?'Ready':'Unavailable'}`;return span;}));
  const working=!!state.running||busy;for(const action of ['setup','valid','forged','new-demo'])$(action).disabled=working||!state.health.every(x=>x.ready)||(action!=='setup'&&!state.prepared)||(action==='new-demo'&&!['funding','valid','forged'].every(k=>state.checks?.[k]?.passed));
  $('setup').textContent=state.prepared?'1 · Recheck the same accounts':'1 · Prepare dedicated accounts';$('valid').textContent=state.checks?.valid.passed?'2 · Retry the same valid transfer':'2 · Send a valid $25 transfer';
  $('activity').textContent=state.running?`Running ${state.running}. Reading actual database evidence…`:'Ready. Actions use persistent fixture identities.';$('error').textContent=state.error||state.observationError||'';
  const id=state.fixtures?.operations[selected];$('operation-id').textContent=id?`${selected.toUpperCase()} · ${id}`:'This operation has not been issued yet.';
  for(const store of ['primary','audit','settlement']){$(store+'-tables').replaceChildren();$(store+'-time').textContent=state.snapshot?.[store].observedAt?'Observed '+state.snapshot[store].observedAt:'Not observed';}
  $('raw-state').textContent=JSON.stringify(state,null,2);
  const snapshot=state.snapshot;if(!snapshot){$('outcome').textContent='No current database evidence';$('outcome').className='';$('conclusion').textContent='Prepare the fixtures, or restore the unavailable dependency. No success is inferred.';$('timeline').replaceChildren();$('alice-balance').textContent='Not observed';$('bob-balance').textContent='Not observed';return;}
  for(const who of ['alice','bob']){const a=snapshot.settlement.accounts.find(x=>x.id===state.fixtures[who]);$(who+'-balance').textContent=a?money(a.balance_cents):'Not observed';}
  const source=snapshot.primary.transactions.filter(x=>x.id===id),receipts=snapshot.audit.receipts.filter(x=>x.id===id),events=snapshot.audit.events.filter(x=>x.operation_id===id),results=snapshot.settlement.results.filter(x=>x.operation_id===id),postings=snapshot.settlement.postings.filter(x=>x.operation_id===id),outbox=snapshot.settlement.outbox.filter(x=>x.operation_id===id);
  const result=results[0];$('outcome').textContent=result?.status??(source.length||receipts.length?'Pending processing':'Waiting for this action');$('outcome').className=result?.status==='QUARANTINED'?'bad':state.checks?.[selected]?.passed?'good':'';
  $('conclusion').textContent=state.checks?.[selected]?.passed?(selected==='forged'?'Confirmed: no independent receipt, durable quarantine, zero postings and unchanged expected balances.':'Confirmed: exact authenticated operation, paired postings, expected balances and delivered audit outcome.'):(result?.reason||'Completion has not yet been confirmed across the independently read stores.');
  const steps=[['Primary row',source.length>0],['Independent receipt',receipts.length>0],['VERIFIED event',events.some(x=>x.event_type==='VERIFIED')],['Protected outcome',!!result],['Financial postings',postings.length===2],['Audit outcome',events.some(x=>['COMPLETED','QUARANTINED','REJECTED'].includes(x.event_type))&&outbox.some(x=>x.relayed_at_micros!==null)]];
  $('timeline').replaceChildren(...steps.map(([label,present],i)=>{const div=document.createElement('div'),b=document.createElement('b'),span=document.createElement('span');div.className=present?'present':selected==='forged'&&result?'absent':'';b.textContent=(i+1)+'. '+label;span.textContent=present?'Observed':selected==='forged'&&result?'Absent / not performed':'Not observed yet';div.append(b,span);return div;}));
  table($('primary-tables'),'transactions',[['Field',x=>x.field],['Stored value',x=>x.value]],source.flatMap(x=>{const op=parse(x.operation_json);return [{field:'Operation',value:short(x.id)},{field:'Amount',value:Number.isSafeInteger(op.amountMinor)?money(op.amountMinor):'Invalid payload'},{field:'From / to',value:short(op.fromAccountId)+' → '+short(op.toAccountId)},{field:'key_id',value:x.key_id},{field:'content_hash',value:short(x.content_hash)},{field:'mac (tag)',value:short(x.mac)}];}));
  table($('primary-tables'),'transaction_outbox',[['Event',x=>x.event_type],['Acknowledged',x=>x.processed_at_micros?'Yes':'No']],snapshot.primary.outbox.filter(x=>x.transaction_id===id));
  table($('primary-tables'),'transaction_status_events',[['Projection',x=>x.status],['Reason',x=>x.reason]],snapshot.primary.statusEvents.filter(x=>x.transaction_id===id));
  table($('audit-tables'),'issuance_receipts',[['Operation',x=>short(x.id)],['Original amount',x=>money(x.operation.amountMinor)],['Key ID',x=>x.key_id]],receipts);
  table($('audit-tables'),'audit_events',[['Seq',x=>x.seq],['Event',x=>x.event_type],['Detail',x=>short(x.detail)]],events);
  table($('settlement-tables'),'operation_jobs',[['Status',x=>x.status],['Attempts',x=>x.attempts]],snapshot.settlement.jobs.filter(x=>x.operation_id===id));
  table($('settlement-tables'),'operation_results',[['Outcome',x=>x.status],['Reason',x=>x.reason]],results);
  table($('settlement-tables'),'ledger_postings',[['Leg',x=>x.leg],['Account',x=>x.account_id===state.fixtures.alice?'Alice':x.account_id===state.fixtures.bob?'Bob':'Treasury'],['Amount',x=>money(x.amount_cents)]],postings);
  table($('settlement-tables'),'settlement_outbox',[['Outcome',x=>x.status],['Relayed',x=>x.relayed_at_micros?'Yes':'No']],outbox);
  $('raw-state').textContent=JSON.stringify(state,null,2);
 }
 async function api(route,body){const response=await fetch(route,{method:body?'POST':'GET',headers:{'X-Demo-Session':capability,...(body?{'Content-Type':'application/json'}:{})},body:body?JSON.stringify(body):undefined,cache:'no-store'});const data=await response.json();if(!response.ok)throw new Error(data.error||'The local lab is unavailable.');return data;}
 async function refresh(){try{state=await api('/api/state');render();}catch(error){$('error').textContent=error.message;$('activity').textContent='Disconnected. Displayed observations are stale.';$('outcome').textContent='Stale observation — not current evidence';$('outcome').className='bad';$('raw-state').textContent=JSON.stringify({stale:true,lastObservedState:state},null,2);for(const action of ['setup','valid','forged','new-demo'])$(action).disabled=true;}}
 async function action(name){if(busy)return;busy=true;selected=['setup','new-demo'].includes(name)?'funding':name;$('operation').value=selected;render();let actionError=null;try{await api('/api/action',{action:name});}catch(error){actionError=error.message;}finally{busy=false;await refresh();if(actionError)$('error').textContent=actionError;}}
 $('operation').onchange=e=>{selected=e.target.value;render();};for(const name of ['setup','valid','forged','new-demo'])$(name).onclick=()=>action(name);
 async function poll(){await refresh();setTimeout(poll,1800);}
 if(location.origin!=='http://127.0.0.1:8090')return;
 fetch('/api/session',{cache:'no-store'}).then(r=>{if(!r.ok)throw new Error();return r.json();}).then(data=>{capability=data.capability;$('offline').hidden=true;$('connected').hidden=false;poll();}).catch(()=>{$('offline').hidden=false;});
})();
