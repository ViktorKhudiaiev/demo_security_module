import {flowSteps, sequenceMessages, participants} from './walkthrough-data.mjs';
const esc=s=>String(s).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('"','&quot;');
const text=(x,y,value,cls='label')=>`<text x="${x}" y="${y}" class="${cls}">${esc(value)}</text>`;
const box=(id,x,y,w,title,subtitle,detail)=>`<g id="node-${id}" class="component"><rect x="${x}" y="${y}" width="${w}" height="120" rx="8"/>${text(x+16,y+30,title)}${text(x+16,y+60,subtitle,'sub')}${text(x+16,y+88,detail,'sub')}</g>`;
const database=(id,x,y,w,title,lines)=>`<g id="node-${id}" class="component database"><path d="M${x} ${y+20} V${y+100} A${w/2} 20 0 0 0 ${x+w} ${y+100} V${y+20}"/><ellipse cx="${x+w/2}" cy="${y+20}" rx="${w/2}" ry="20"/>${text(x+16,y+57,title)}${lines.map((line,i)=>text(x+16,y+80+i*20,line,'sub')).join('')}</g>`;
export function architectureSvg(){return `<svg viewBox="0 0 1180 660" role="img" aria-labelledby="architecture-title"><title id="architecture-title">Three modules, two PostgreSQL databases and embedded ActiveMQ, with an optional incident notification side channel</title><defs><marker id="flow-arrow" markerWidth="10" markerHeight="10" refX="8" refY="5" orient="auto"><path d="M0 0 L10 5 L0 10 Z" fill="#215acc"/></marker></defs>
<rect x="170" y="305" width="585" height="190" rx="12" fill="#edf3ff" stroke="#7f96b5" stroke-dasharray="6 5"/>
${text(185,330,'ONE PROCESSOR JVM · internal VM transport · no broker network port','sub')}
<g id="node-user" class="component"><circle cx="78" cy="115" r="17"/><path d="M48 160 Q48 140 78 140 Q108 140 108 160"/>${text(48,190,'User')}</g>
${box('app',190,85,230,'MODULE 1','Transfer application',':8080 · business API')}
${box('keys',505,85,230,'MODULE 2','Key service',':8081 · key stays here')}
${database('primary',845,85,240,'MAIN / PRIMARY DB',['transactions + outbox','Untrusted projections'])}
${box('processor',190,345,230,'MODULE 3','Integrity processor',':8082 · Java calculations')}
${box('queue',505,345,230,'EMBEDDED ACTIVEMQ','Persistent UUID hints','KahaDB journal on disk')}
${database('audit',845,365,240,'AUDIT / PROTECTED DB',['Receipts + audit history','Jobs + balances + postings'])}
<path id="active-arrow" d="${flowSteps[0].path}" fill="none" stroke="#215acc" stroke-width="4" marker-end="url(#flow-arrow)"/>
${text(25,25,'RECTANGLE = MODULE / BROKER    CYLINDER = POSTGRESQL DATABASE','sub')}
<text id="arrow-caption" x="25" y="60" class="label">${esc(flowSteps[0].label)}</text>
${text(25,575,'Only Primary DB is in attacker scope. The processor host, key service and protected database remain trusted.','sub')}
<g id="notification-sideflow"><rect x="25" y="597" width="1110" height="47" rx="6" fill="#fff5de" stroke="#c6a451"/>
${text(40,626,'INCIDENT ONLY: notification_outbox → email dispatcher → Mailpit SMTP :1025 (inbox :8025). Not a settlement gate.','sub')}</g></svg>`;}
export function sequenceSvg(){
 const width=1200,height=445,left=80,gap=173,top=86,row=22;
 let svg=`<svg viewBox="0 0 ${width} ${height}" role="img" aria-labelledby="sequence-title"><title id="sequence-title">Compact normal transfer sequence with every module, both databases and the embedded broker</title><defs><marker id="sequence-arrow" markerWidth="7" markerHeight="7" refX="6" refY="3.5" orient="auto"><path d="M0 0 L7 3.5 L0 7 Z" fill="#215acc"/></marker></defs>`;
 participants.forEach(([title,detail],i)=>{const x=left+i*gap;svg+=`<rect x="${x-75}" y="5" width="150" height="55" rx="6" fill="${i===3?'#fff1f2':i===6?'#edf7f1':'#eaf1ff'}" stroke="#9fb1c8"/><text x="${x}" y="27" text-anchor="middle" class="sequence-title">${esc(title)}</text><text x="${x}" y="48" text-anchor="middle" class="sub">${esc(detail)}</text><line x1="${x}" x2="${x}" y1="60" y2="433" stroke="#cbd6e5" stroke-dasharray="5 4"/>`;});
 sequenceMessages.forEach(([from,to,step,label],i)=>{const x1=left+from*gap,x2=left+to*gap,y=top+i*row,caption=step+' · '+label,labelWidth=caption.length*8.1,labelX=Math.max(12,Math.min(Math.min(x1,x2)+8,width-labelWidth-16));svg+=`<g class="sequence-message" data-step="${step}"><path d="M${x1} ${y} H${x2}" stroke="#215acc" stroke-width="1.7" fill="none" marker-end="url(#sequence-arrow)"/><rect x="${labelX-3}" y="${y-19}" width="${labelWidth+6}" height="17" fill="white"/>${text(labelX,y-5,caption,'sequence-label')}</g>`;});
 return svg+'</svg>';
}
export function productionSvg(){return `<svg viewBox="0 0 1180 500" role="img" aria-labelledby="target-title"><title id="target-title">Production target: two database domains, independently governed key custody and external evidence retention</title>
<defs><marker id="target-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0 0 L8 4 L0 8 Z" fill="#215acc"/></marker></defs>
<rect class="boundary" x="15" y="35" width="330" height="220" rx="10"/>
${text(32,63,'APPLICATION / PRIMARY DOMAIN','label')}
${text(32,103,'Application: business authorization','sub')}
${text(32,140,'Primary PostgreSQL: candidate + outbox','sub')}
${text(32,185,'Primary DBA cannot reach protected state','sub')}
<rect class="boundary" x="420" y="35" width="340" height="220" rx="10"/>
${text(437,63,'PROTECTED PROCESSING DOMAIN','label')}
${text(437,103,'Durable delivery adapter + processor','sub')}
${text(437,140,'Audit/Protected PostgreSQL','sub')}
${text(437,173,'Separate issuance, audit, accounting roles','sub')}
${text(437,211,'Java calculations; atomic SQL execution','sub')}
<rect class="boundary" x="825" y="35" width="340" height="220" rx="10"/>
${text(842,63,'INDEPENDENT KEY GOVERNANCE','label')}
${text(842,103,'Key API + non-exportable KMS/HSM keys','sub')}
${text(842,140,'Independent IAM and deployment approval','sub')}
${text(842,173,'GenerateMac / VerifyMac separation','sub')}
${text(842,211,'Signed checkpoints; pinned public keys','sub')}
<rect class="boundary" x="420" y="330" width="745" height="125" rx="10"/>
${text(437,360,'INDEPENDENT EVIDENCE / RECOVERY','label')}
${text(437,395,'Retained recent checkpoints, immutable storage policy, backups and tested PITR','sub')}
${text(437,429,'External timestamp authority only where required; no compliance claim from HMAC','sub')}
<rect class="boundary" x="15" y="330" width="330" height="125" rx="10"/>
${text(32,360,'OPTIONAL INCIDENT ALERTS','label')}
${text(32,395,'Protected outbox → approved SMTP','sub')}
${text(32,429,'Monitor backlog; not execution authority','sub')}
<path class="flow" d="M345 140 H420"/><path class="flow" d="M760 110 H825"/><path class="flow" d="M995 255 V330"/><path class="flow" d="M590 255 V330"/>
${text(20,487,'TARGET ONLY: mTLS, independent operators, remote broker HA if required, monitoring, recovery drills and security review.','sub')}</svg>`;}
