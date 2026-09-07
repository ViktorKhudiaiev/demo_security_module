import fs from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
const runtimeModules = process.env.RUNTIME_NODE_MODULES ?? 'C:/Users/vikto/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules';
const artifactPath = createRequire(path.join(runtimeModules, 'artifact-loader.cjs')).resolve('@oai/artifact-tool');
const { Presentation, PresentationFile, FileBlob } = await import(pathToFileURL(artifactPath));

const root = process.cwd();
const tmp = path.join(root, '.local/artifact-qa/presentation-two-db');
const out = path.join(root, 'docs/presentation');
const skill = 'C:/Users/vikto/.codex/plugins/cache/openai-primary-runtime/presentations/26.904.11930/skills/presentations';
const python = 'C:/Users/vikto/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe';
process.env.RUNTIME_NODE_MODULES = 'C:/Users/vikto/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules';
const { resolvePresentationFont, finalizePresentation, makeNativeBulletParagraphs } = await import(pathToFileURL(path.join(skill, 'container_tools/artifact_tool_utils.mjs')));
const family = resolvePresentationFont({ fontFamily: 'Arial', availableFonts: ['Arial'] });
const fontPolicy = { basis: 'design', families: [family] };
const C = { navy: '#142D4E', blue: '#2463A6', text: '#26364B', muted: '#5E6A78', light: '#E8EEF4', rule: '#CAD5E1', pale: '#F4F7FA', white: '#FFFFFF', red: '#B43138', redPale: '#FFF3F2', amber: '#916000', amberPale: '#FFF8E6' };
let p = Presentation.create({ slideSize: { width: 1280, height: 720 } });
const metadata = [];
let counter = 0;

function text(s, value, x, y, w, h, size=27, { color=C.text, bold=false, align='left', fill='none', inset=0, name }={}) {
  const a = s.shapes.add({ geometry:'textbox', name:name ?? `text-${++counter}`, position:{left:x,top:y,width:w,height:h},fill,line:{fill:'none',width:0} });
  a.text = value;
  a.text.style = { typeface:family, fontSize:size, bold, color, alignment:align, verticalAlignment:'top', autoFit:'none', wrap:'square', insets:{left:inset,right:inset,top:inset,bottom:inset} };
  return a;
}
function line(s,x,y,w,h=0,color=C.rule,width=1){return s.shapes.add({geometry:'line',position:{left:x,top:y,width:w,height:h},fill:'none',line:{fill:color,width,style:'solid'}});}
function box(s,value,x,y,w,h,{color=C.blue,fill=C.white,size=24,bold=true,dash=false}={}){
  const a=s.shapes.add({geometry:'rect',name:`diagram-${++counter}`,position:{left:x,top:y,width:w,height:h},fill,line:{fill:color,width:2,style:dash?'dashed':'solid'}});
  if(value){a.text=value;a.text.style={typeface:family,fontSize:size,bold,color,alignment:'center',verticalAlignment:'middle',autoFit:'none',wrap:'square',insets:{left:10,right:10,top:9,bottom:9}};}
  return a;
}
function edge(s,a,b,{from='right',to='left',color=C.blue,kind='straight',dash=false}={}){return s.shapes.connect(a,b,{kind,fromSide:from,toSide:to,line:{fill:color,width:2,style:dash?'dashed':'solid'},tail:{type:'triangle',width:'sm',length:'sm'}});}
function bullets(s,items,x,y,w,h,size=28,color=C.text){
  const a=text(s,'',x,y,w,h,size,{color});
  a.text=makeNativeBulletParagraphs(items,{marginLeftPoints:17,hangingPoints:9,spaceAfterPoints:13});return a;
}
function table(s,rows,x,y,w,h,widths,{size=25,header=true,firstBold=false}={}){
  const t=s.tables.add({rows:rows.length,columns:rows[0].length,left:x,top:y,width:w,height:h,columnWidths:widths,values:rows});
  t.styleOptions={headerRow:false,bandedRows:false};
  t.borders.assign({fill:C.rule,width:0.8,style:'solid'});
  for(let r=0;r<rows.length;r++){
    t.rows[r].height=h/rows.length;
    for(let c=0;c<rows[0].length;c++){
      const z=t.getCell(r,c);z.fill=header&&r===0?C.navy:C.white;
      z.text.style={typeface:family,fontSize:size,color:header&&r===0?C.white:C.text,bold:(header&&r===0)||(firstBold&&c===0),autoFit:'none',verticalAlignment:'middle',insets:{left:14,right:12,top:10,bottom:10}};
    }
  }
  return t;
}
function slide(title,notes,{sub='',footer='Local software-key demo. One trusted host. September 2026.'}={}){
  const s=p.slides.add();s.background.fill=C.white;
  const n=metadata.length+1;
  text(s,title,64,45,1148,94,44,{color:C.navy,bold:true,name:`title-${n}`});
  if(sub)text(s,sub,66,139,1142,49,24,{color:C.muted});
  text(s,footer,66,667,1050,30,17,{color:C.muted});
  text(s,String(n).padStart(2,'0'),1157,665,55,30,19,{color:C.muted,align:'right'});
  s.speakerNotes.textFrame.setText(notes);
  metadata.push({number:n,title,notes});return s;
}

// 1. Minimal editable cover.
{
const notes=`Timing: about 1 minute.
Main point: the project examines a specific situation. An attacker obtains administrative access to the primary transaction database but does not control the trusted services, independent evidence or settlement domain. A primary database row alone must never authorize a debit.
Plain-language explanation: we attach an authenticated seal to an operation, retain an independent receipt of its issuance, and execute the financial effect in a protected domain. The seal alone is insufficient, so the discussion also covers corrections, missing records and recovery.
The phrase "without a blockchain" describes our trust model. We do not run distributed consensus among mutually distrustful participants. This does not establish that our approach costs less than every ledger product or surpasses it in every property.
This is a local engineering prototype on PostgreSQL, not a universal adapter for any database. The demo moves simulated dollars rather than real payments.
Likely question: what is the contribution? Answer: a coherent integration of established mechanisms around an existing relational database, with checked execution admission and reproducible scenarios. We claim no new cryptographic primitive.
Sources: docs/reference/architecture.md; docs/history/article-review-2026-09-05.md, R11–R14.`;
const s=p.slides.add();s.background.fill=C.white;
text(s,'Verifiable Record Integrity\nWithout a Blockchain',64,164,1125,170,66,{color:C.navy,bold:true,name:'title-1'});
text(s,'A PostgreSQL implementation with authenticated operations\nand protected settlement',68,371,1090,95,31,{color:C.text});
text(s,'Viktor Khudiaiev\nTechnical demonstration, September 2026',68,539,1040,62,24,{color:C.muted});
text(s,'Local prototype. Simulated funds. Production requirements remain explicit.',68,669,1100,30,17,{color:C.muted});
s.speakerNotes.textFrame.setText(notes);metadata.push({number:1,title:'Verifiable Record Integrity Without a Blockchain',notes});
}
// 2. Explicit threat and guarantee.
{
const notes=`Timing: 1–1.5 minutes.
DBA means database administrator. Our attacker may modify, insert, delete and restore historical primary contents, and disable triggers. Therefore, a SQL rule that prohibits row changes is not our final line of defense.
Primary stores untrusted operation intents. Audit/Protected stores independent receipts, history and authoritative accounting under separate runtime roles. The processor JVM contains the persistent ActiveMQ broker and the Java verification/accounting workers. The processor is the trusted executor. The primary DBA must have no authority to change these protected components or obtain MAC-issuance permission.
Bounded guarantee: primary administrative access alone is insufficient to execute a forged, modified or already executed operation. The assumptions on this slide are mandatory. Compromise of the common host, an application with issuance authority, or settlement changes the threat model.
HMAC does not conceal data. It does not prevent DROP DATABASE or keep the system continuously available. Recovery requires backups and complementary infrastructure.
The integrating application checks an individual user's right to transfer funds. We deliberately do not build another Identity Provider or require actorId inside this module. However, the application must protect its API and issuance credential. If the trusted application itself requests an improper transfer, a MAC does not understand that business meaning.
Why two databases? Main is attacker-controlled. Audit/Protected stores both evidence and financial authority outside Main administration. Java calculates; SQL commits atomically. A passive audit log could not protect balances owned by a compromised Main DBA.
Sources: docs/reference/architecture.md; docs/history/article-review-2026-09-05.md, R02, R08.`;
const s=slide('Threat model and bounded guarantee',notes,{sub:'Primary database access alone does not authorize financial execution'});
table(s,[['Attacker controls','Trusted assumptions'],['Primary rows, outbox hints and status projections','Application and service credentials'],['Primary insertion, modification, deletion and replay','Key service, processor and embedded broker'],['Primary triggers and historical restores','Audit/Protected DB and the common host']],65,218,1148,292,[520,628],{size:26});
text(s,'Excluded: confidentiality, whole-host compromise, guaranteed availability\nand automatic regulatory compliance.',67,554,1125,82,26,{color:C.muted});
}
// 3. Current two-database topology, with an explicitly embedded broker.
{
const notes=`Three Java processes run on ports 8080, 8081 and 8082. Main PostgreSQL runs on 55431. Audit/Protected PostgreSQL runs on 55432 and holds evidence plus authoritative accounting under separate runtime roles. No third database is required.
The key service commits issuance_receipts before returning the MAC. The application commits the operation and Primary outbox together. The processor dispatcher polls that outbox and sends a persistent UUID hint to ActiveMQ Classic 6.3.2 in the same JVM, using VM-only transport and a KahaDB journal. Primary acknowledgment follows broker persistence. The consumer commits a protected operation_jobs insert before acknowledging JMS.
The processor resolves the independent receipt first, compares the source and verifies the HMAC through the key API, then re-reads and settles the exact checked snapshot. Java calculates; a protected SQL transaction commits balances, paired postings, result and durable outcome. The outcome relay records audit history and the Main projection. Reconciliation and Merkle checkpoints run independently.
The broker buffers identifiers. It is not payment authorization, an independent failover node, or evidence of improved maximum capacity. Duplicate delivery is expected. No global transaction spans both databases and JMS. A Kafka adapter would need distinct offset/partition semantics.
Sources: docs/reference/architecture.md; scripts/start.ps1; transaction-security-module/src/main/java/com/demo/securityapp/delivery/EmbeddedActiveMqDelivery.java. Apache VM transport: https://activemq.apache.org/components/classic/documentation/vm-transport-reference`;
const s=slide('Current local architecture',notes,{sub:'Two PostgreSQL databases. ActiveMQ runs inside the processor JVM.'});
const app=box(s,'Application\n:8080',64,235,220,85);
const key=box(s,'Key service\n:8081',64,452,220,85);
const primary=box(s,'Main DB\nIntents and outbox',485,235,275,85,{color:C.red,fill:C.redPale});
const audit=box(s,'Audit / Protected DB\nEvidence and accounting',440,452,320,104,{size:23});
box(s,'',917,196,296,380,{fill:C.pale,dash:true});
text(s,'PROCESSOR JVM :8082',934,210,263,30,20,{bold:true,color:C.navy});
const queue=box(s,'Embedded ActiveMQ\nPersistent UUID hints',937,260,256,86,{size:23});
const worker=box(s,'Verification worker\nJava accounting',937,449,256,86,{size:23});
edge(s,app,key,{from:'bottom',to:'top'});text(s,'issue API',201,376,152,35,21,{color:C.blue});
edge(s,app,primary);text(s,'publish',329,239,134,32,21,{color:C.blue});
edge(s,key,audit);text(s,'receipt first',293,463,146,35,20,{color:C.blue});
edge(s,primary,queue,{color:C.red});text(s,'poll + send',775,243,138,33,20,{color:C.red});
edge(s,queue,worker,{from:'bottom',to:'top'});text(s,'SQL job\nbefore ACK',949,365,228,60,21,{color:C.blue,align:'center'});
edge(s,worker,audit,{from:'left',to:'right'});text(s,'checked\nsnapshot',775,444,138,64,20,{color:C.blue});
text(s,'Red identifies attacker-controlled Main. The broker never grants payment approval.',65,598,1145,45,24,{color:C.red});
}
// 4. Exact pipeline.
{
const notes=`Timing: 1–1.5 minutes.
Read from top to bottom. The integrating application has already checked business permission. Its service credential allows a MAC request. The key service validates the schema and commits an independent receipt before returning the response. This ensures that an authentically issued operation has an independent record even before primary publication.
The application commits the operation and source outbox in one transaction. The dispatcher persists a UUID in embedded ActiveMQ before acknowledging the Primary hint. The consumer commits a protected SQL job before committing JMS receipt acknowledgment. It obtains the receipt and checks the exact SignedOperation, HMAC and ledger domain. After rereading primary, it uses precisely the matching snapshot.
Settlement executes a local atomic transaction containing postings, balances, the unique execution journal, result and outcome outbox. Atomic means that the database commits this entire set or none of it. It does not imply one transaction across both PostgreSQL databases and JMS.
Outcome delivery to audit and projection updates in primary may lag. A durable outbox stores the delivery task together with the financial effect. Repeated delivery handles duplicates. The processor does not debit again merely because an HTTP response disappeared or the primary status has not caught up.
In load acceptance, completion means more than HTTP 200. The harness checks protected COMPLETED status, the exact pair of postings, operation content and a relayed durable outcome.
Likely question: why durable? Answer: a process loses memory when it stops, but a database-recorded job remains available after restart.
Sources: tokenization-module/src/main/java/com/demo/keyservice/IssuanceService.java, issue; transaction-security-module/src/main/java/com/demo/securityapp/Processor.java, discover, process, settle, relay; scripts/load-evidence.mjs.`;
const s=slide('Transfer execution path',notes);
const labels=[['1','Authorize and issue','The host application owns user permission checks.'],['2','Commit the receipt','Key service records evidence before returning the MAC.'],['3','Publish intent and outbox','Both source records commit in one Main SQL transaction.'],['4','Queue the operation ID','Persistent send, then Main ACK. SQL job, then JMS ACK.'],['5','Verify and recheck','Receipt first. Check MAC, then require the same snapshot.'],['6','Commit accounting and relay','Atomic protected postings. Retry audit and Main delivery.']];
for(let i=0;i<labels.length;i++){
const y=174+i*74;
text(s,labels[i][0],66,y,60,48,33,{bold:true,color:C.blue});
text(s,labels[i][1],146,y,390,52,25,{bold:true,color:C.navy});
text(s,labels[i][2],564,y,636,58,23);
if(i<labels.length-1)line(s,146,y+65,1055,0,C.rule,1);
}
}
// 5. Canonical structure.
{
const notes=`Timing: 1.5 minutes.
Canonicalization is an unambiguous conversion of operation content into bytes. Simple concatenation of AB and C produces the same result as A and BC. Our format therefore defines each field's boundary and type.
TLV means Tag, Length, Value, with an additional explicit type byte here. One byte contains the numeric field identifier, one contains the type, four contain the value length in bytes, and the value follows. Big-endian places the most significant bytes first. This is one exact specification, not a choice among serializers.
The fixed order of all 13 fields is domain, schemaVersion, keyId, id, ledgerId, type, fromAccountId, toAccountId, amountMinor, currency, createdAtMicros, relatedOperationId and idempotencyKey. The domain separates this MAC from other purposes. LedgerId binds the operation to a particular ledger. KeyId belongs to the authenticated bytes and identifies the exact secret version.
A UUID occupies 16 bytes. SchemaVersion is int32. Amount and timestamp are int64. Amount is positive, integral and denominated in minor currency units, without floating point. Time is exactly UTC Unix epoch microseconds. NULL has its own type tag 0 and length 0. The required relatedOperationId field must appear even when explicitly null. The implementation rejects an omitted field.
UTF-8 specifies string encoding. Version 1 restricts identifiers to the permitted ASCII alphabet. It has no free-form Unicode text and no silent NFC normalization. Unsupported schemas fail validation.
A golden test vector specifies concrete bytes, hash and MAC. The test key made of repeated 0b bytes must never serve as a runtime key.
Sources: docs/reference/protocol.md, Operation commitment; tokenization-module/src/main/java/com/demo/integrity/CanonicalEncoder.java; tokenization-module/src/test/java/com/demo/integrity/CanonicalEncoderTest.java.`;
const s=slide('Canonical operation bytes',notes,{sub:'Version 1 has one exact encoding and golden test vectors'});
table(s,[['Field ID','Type','Byte length','Value'],['uint8','uint8','uint32, big-endian','Exact bytes']],65,199,1148,131,[210,210,332,396],{size:26});
table(s,[['Bound content','Representation'],['Domain, schema, key, ledger and operation identity','Fixed field order and validated values'],['Type, accounts, amount and currency','UUID16, int64 minor units, UTF-8 identifiers'],['Timestamp, correction link and idempotency key','UTC microseconds; explicit null or UUID']],65,366,1148,235,[656,492],{size:22});
text(s,'ASCII identifiers only. Missing fields fail. The MAC binds all 13 fields.',67,624,1115,37,23,{color:C.muted});
}
// 6. Crypto layers table.
{
const notes=`Timing: 1.5 minutes.
HMAC means Hash-based Message Authentication Code, a message authentication code using a secret key. SHA-256 is the underlying hash function. The short authentication tag changes when the protected bytes change. HMAC does not encrypt or conceal the amount and accounts.
A holder of the symmetric HMAC key can generate and verify MACs. Our processor does not possess the key. It uses a verification-only API that denies issuance. The application has an issuance credential. Separate roles also cover key rotation administration and checkpoint signing. Verification permission does not automatically confer issuance permission.
Ed25519 is a separate asymmetric signature scheme. A private key signs a Merkle-log checkpoint, and a verifier uses the public key. The signed object has its own domain-separated TLV structure. Individual operation status events do not each receive this signature. The operation continues to use HMAC.
A verifier must obtain the expected public key through a trusted channel. Accepting any new key merely because it accompanies a signature lets an attacker bring a new key pair. The signature by itself does not prove that the log is fresh, that the clock is correct, or that a human actually performed the business event.
Locally, software files with OS access controls hold the keys. HMAC rotation already works: new operations use the active keyId, and retained older keys verify history. A real KMS or HSM is not integrated. KMS is a managed service for key operations. HSM is a hardware security module. Both require actual integration and independent governance.
Sources: docs/reference/protocol.md; tokenization-module/src/main/java/com/demo/keyservice/LocalKeyVault.java; tokenization-module/src/main/java/com/demo/keyservice/ServiceAuthenticationFilter.java; docs/history/article-review-2026-09-05.md, R04–R08.`;
const s=slide('HMAC and Ed25519 protect different objects',notes);
table(s,[['','HMAC-SHA256','Ed25519'],['Object','Immutable operation','Merkle checkpoint'],['Purpose','Internal content authentication','Public-key signature verification'],['Verifier','Authorized verification API','Expected public key'],['Required trust','Key service and receipt store','Trusted key distribution and retained head']],65,201,1148,335,[223,452,473],{size:26});
text(s,'Implemented: separate API roles and HMAC key rotation.',67,566,1119,40,27,{color:C.blue,bold:true});
text(s,'Current custody uses software files on the same trusted host.',67,613,1119,39,25,{color:C.muted});
}
// 7. TOCTOU exact snapshot.
{
const notes=`Timing: 1.5 minutes.
TOCTOU means time-of-check to time-of-use. Imagine that the verified transfer sends 10 dollars to A. After verification, an attacker changes primary to send 1,000 dollars to B. If the processor checks only an old VERIFIED flag but uses the new financial fields, the protection fails.
Our code first matches SignedOperation to the independent receipt and verifies the HMAC. Immediately before settlement, it rereads primary. The new read must match the previously checked snapshot exactly. A mismatch or deletion quarantines the operation without postings.
If the comparison succeeds, the processor passes that exact finalSnapshot into settle. Settlement does not reread the amount or recipient from primary. Consequently, a primary modification after the last comparison cannot change the selected financial effect. Later reconciliation records the discrepancy.
We do not claim to lock a primary row throughout a transaction in another database. We do not claim distributed ACID. The safeguard concerns exactly which data the executor consumes.
Likely question: can we compare the hash and then query the amount again? Answer: no. That would recreate the race. Execution must consume the same checked snapshot.
Evidence: the PostgreSQL scenario tamper-after-verification-before-use-is-quarantined paused processing after verification, modified the row, and obtained quarantine. A separate scenario tested deletion after verification. The normal configuration disables these test pauses.
Sources: transaction-security-module/src/main/java/com/demo/securityapp/Processor.java, process and settle; docs/evidence/local-verification-2026-09-06.json, tamper-after-verification-before-use-is-quarantined and delete-after-verification-is-quarantined; docs/history/article-review-2026-09-05.md, R01.`;
const s=slide('TOCTOU protection at execution',notes,{sub:'A previous VERIFIED event never serves as a blanket payment approval'});
const a=box(s,'Authenticated\nsnapshot',65,250,285,101);
const b=box(s,'Final source read\nExact comparison',483,250,301,101);
const c=box(s,'Settlement uses\nthat same snapshot',923,250,288,101);
edge(s,a,b);edge(s,b,c);
text(s,'Attacker may change primary between checks',384,197,611,39,24,{color:C.red});
text(s,'Mismatch or deletion',475,403,329,40,25,{color:C.red,bold:true});
text(s,'Operation quarantine\nNo money postings',474,450,357,82,27,{color:C.red});
text(s,'Later primary edits cannot change the amount or recipient\nalready selected for execution.',67,573,1141,76,29,{color:C.navy});
}
// 8. Deletion and history.
{
const notes=`Timing: 1.5–2 minutes.
A standalone row HMAC detects modification but cannot detect the complete absence of the row. Checking only surviving records is insufficient. An auto-increment gap also fails to prove an attack because legitimate sequence allocation can create gaps.
The first path concerns the primary database. The key service saves an issuance receipt before returning the MAC. The processor scans independent issuance inventory and compares it with primary. This detects a missing issued operation even before anyone observed its source outbox. The inventory sequence is a cursor, not a cryptographic completeness proof.
The check is periodic. The code allows a five-second publication grace window, and further delay depends on inventory scanning. Issued-but-missing means a discrepancy. Either malicious deletion or an application crash before primary publication can cause it. Intent cannot be inferred automatically.
The second path concerns audit history. A Merkle tree links event hashes into a root. A signed checkpoint binds logId, treeSize, root, keyId, version and time. An inclusion proof establishes membership relative to that root. Signature verification requires a trusted public key.
The demo retains checkpoint files outside audit DB. This database-external anchor allows comparison with a previously retained state. However, a self-consistent old log does not prove freshness without a trusted recent expectation. Files on the same host cannot resist the host administrator.
The demo does not implement operation MAC chaining, a compact consistency-proof API, a scalable incremental tree, a timestamp authority or a public witness. Verification currently reconstructs the whole tree or prefix. A root signature does not replace content verification or trust in the path that supplied the root.
Sources: docs/reference/protocol.md, Issuance and retries, Signed checkpoints; transaction-security-module/src/main/java/com/demo/securityapp/Processor.java, reconcile; transaction-security-module/src/main/java/com/demo/securityapp/AuditLog.java; docs/history/article-review-2026-09-05.md, R03–R04.`;
const s=slide('Missing operations and audit history',notes);
text(s,'Primary completeness',67,186,1084,39,29,{color:C.navy,bold:true});
const a=box(s,'Independent\nissuance inventory',66,257,314,92);
const b=box(s,'Periodic comparison\nwith primary records',480,257,318,92);
const c=box(s,'Missing or changed\noperation incident',902,257,310,92);
edge(s,a,b);edge(s,b,c);
text(s,'Audit history',67,393,1084,40,29,{color:C.navy,bold:true});
const d=box(s,'Ordered audit\nevents',66,462,314,92);
const e=box(s,'Merkle root and\nEd25519 checkpoint',480,462,318,92);
const f=box(s,'Retained local\ncheckpoint files',902,462,310,92);
edge(s,d,e);edge(s,e,f);
text(s,'A missing issuance is a discrepancy, not proof of intent.\nLocal checkpoint files do not provide an independent freshness witness.',67,592,1130,65,24,{color:C.muted});
}
// 9. Financial correctness.
{
const notes=`Timing: 1–1.5 minutes.
An authenticated operation must still pass ordinary settlement checks: the expected ledger, supported USD currency, existing distinct accounts, no protected hold and sufficient available funds. A MAC does not replace financial invariants.
One transfer creates paired postings: a negative amount for the sender and a positive amount for the recipient. Their sum is zero. Both postings, balance changes, a unique execution journal, operation result and durable outcome outbox commit in one settlement transaction.
The processor locks accounts in stable UUID order. This protects concurrent balance changes and reduces avoidable deadlocks. Protected job/result state and unique operation identity prevent repeated execution.
Idempotency means an identical business request with the same key returns the original operation without another financial effect. At issuance, the unique ledgerId and idempotencyKey pair binds the request. A changed amount under the same key returns HTTP 409. Replaying an outbox hint also causes no second debit.
The injected failure after the debit posting rolls back the entire SQL transaction. A subsequent retry leaves exactly two final postings rather than three.
Primary balances and holds are not authoritative. A fabricated COMPLETED status cannot authorize or establish settlement. Authoritative financial state remains in the protected accounting tables in Audit DB.
Likely question: does "exactly once" apply to the whole network? Answer: the design ensures one financial effect under retries inside this protected settlement model. Network messages and delivery may repeat. External payment systems require their own idempotency and reconciliation.
Sources: transaction-security-module/src/main/java/com/demo/securityapp/Processor.java, settle, businessRejection, outcome, relay; docs/reference/protocol.md, Issuance and retries; docs/evidence/local-verification-2026-09-06.json.`;
const s=slide('Protected settlement invariants',notes);
table(s,[['Invariant','Implementation'],['One financial effect per operation','Protected execution identity and idempotent retries'],['No partial debit or credit','Paired postings and balances in one SQL transaction'],['Trusted funds and account controls','Protected balances and account holds'],['Concurrent balance safety','Account locks in stable order'],['Durable outcome delivery','Outcome outbox commits with settlement']],65,184,1148,376,[470,678],{size:24});
text(s,'Atomic accounting in Audit DB. Eventual Main status delivery.',67,595,1118,49,28,{color:C.blue,bold:true});
}
// 10. Corrections and funding.
{
const notes=`Timing: 1.5 minutes.
A new account starts with zero balance. To fund it, the application creates an authenticated FUNDING operation from the system Treasury account. It follows the same issuance, verification and settlement path. This matters because arbitrary initial-balance writes in primary would bypass protection of ordinary transfers.
Treasury is the balancing system account in this simulation. It may have a negative balance to represent the source of simulated funds. The integrating product must establish actual bank funding, limits and approvals. Cryptography alone does not confirm an external deposit.
Correcting a completed transfer preserves the original operation. A new REVERSAL first swaps sender and recipient exactly, retains the original amount and currency, and references the original operation. Once reversal completes, a CORRECTION can carry the corrected business data and reference that same original.
Both references form part of authenticated canonical bytes. The implementation rejects duplicate reversals or corrections of the original and rejects reversal of a reversal. The two correction steps do not form one atomic transaction. Compensation may fail when the refunding account lacks funds or has a hold.
Do not describe a pending operation as atomically cancelled: cancellation or supersession before settlement is not implemented in this version. The slide states that limitation.
Sources: docs/reference/protocol.md, Operation commitment; transaction-security-module/src/main/java/com/demo/securityapp/Processor.java, businessRejection, register; docs/history/article-review-2026-09-05.md, R09–R10.`;
const s=slide('Funding and corrections preserve evidence',notes);
text(s,'Funding',67,181,1020,40,30,{color:C.navy,bold:true});
text(s,'Zero-balance account. Authenticated Treasury operation. Same settlement checks.',67,236,1126,85,29);
text(s,'Correction of a completed operation',67,345,1124,40,30,{color:C.navy,bold:true});
const a=box(s,'Completed\noriginal',66,418,313,93);
const b=box(s,'New exact\nREVERSAL',481,418,313,93);
const c=box(s,'New corrected\nCORRECTION',899,418,313,93);
edge(s,a,b);edge(s,b,c);
text(s,'Each new operation binds the original ID in its MAC.',67,551,1126,42,27,{color:C.blue,bold:true});
text(s,'Compensation can fail. The workflow is not atomic.\nPre-settlement cancellation and external funding evidence remain outside this demo.',67,601,1134,62,24,{color:C.muted});
}
// 11. Recovery evidence native table.
{
const notes=`Timing: 1.5 minutes.
The availability policy fails closed: an unavailable required check does not authorize an unverified payment. Confirmed invalid content enters quarantine. A temporary dependency failure leaves durable retry state where retry is appropriate. Quarantine applies to the operation. Automatically holding the named recipient requires a separate policy because an attacker could name an innocent account to cause denial of service. Protected account holds exist as a separate administrative action.
The first real local test stopped the processor JVM. The initial HTTP request returned 503 even though the independent receipt and primary publication already existed. During the outage there were zero postings. After restart, the processor picked up the operation without client republication and completed it. A retry with the same idempotency key caused no second effect. In this case, 503 conveys uncertainty to the client rather than a proven abort.
The second test stopped the key service before NEW issuance. The request returned 503 with no receipt, no primary row and no balance change. After recovery, an identical business request completed once.
Each successful operation has two postings. The report checks exact payloads, accounts, amounts, balances and audit outcomes. The harness restored normal configuration with test fault hooks disabled.
These September 6 tests use the embedded broker path. They do not isolate a pending broker-message crash. They are Windows process-stop tests. They do not represent host power loss, PostgreSQL failure, a network partition, PITR or production failover. Key-service loss during an already verified in-flight settlement was not tested and does not imply immediate cancellation of that execution.
Sources: docs/evidence/local-recovery-2026-09-06.json; scripts/recovery.mjs; scripts/recovery-stop-service.ps1; docs/reference/architecture.md.`;
const s=slide('Recovery after real local process outages',notes,{sub:'September 6 run: two databases and embedded ActiveMQ'});
table(s,[['Stopped service','During outage','After restart'],['Processor','HTTP 503 after publication\nZero postings','Protected job recovery\nSame-key retry adds no effect'],['Key service\nbefore new issue','HTTP 503\nNo receipt or primary row','Same request succeeds\nOne financial effect']],65,212,1148,293,[275,425,448],{size:27});
text(s,'Invalid content enters quarantine. Dependency failures remain retryable.',67,548,1115,74,29,{color:C.blue,bold:true});
text(s,'Not covered here: power loss, database outages or in-flight key-service failure.',67,625,1140,37,22,{color:C.muted});
}
// 12. Exact measured metrics.
{
const notes=`Timing: 1.5 minutes.
These results are from September 6, 2026, with two PostgreSQL databases and embedded ActiveMQ. A later worker-admission failure-path review added two regression tests; the recorded load run contained 83 Java tests. The test used one Windows machine with an Intel Core i7-13620H, 16 logical CPUs, about 16 GB of memory, Docker PostgreSQL and software keys. No cloud KMS or real payment network was in the critical path.
The generator offered exactly 20 transfers per second for 120 seconds across eight test accounts. All 2,400 unique operations completed with zero failures. Completion includes the protected result, exact paired postings, matching operation content and a relayed durable audit outcome. Balance checks passed.
The 20.3091 TPS figure counts completions in the window after the first ten seconds of warmup, from second 10 through second 120. A finite completion window can differ slightly from the constant arrival rate because of its boundaries. Acceptance used zero configured throughput tolerance.
The 19.9371 TPS figure includes the entire run with warmup and final drain. Drain is the time required to finish remaining work after arrivals stop. It was approximately 378 ms. Reporting both prevents a steady-window result from being mistaken for whole-run wall-clock throughput.
A p95 of 3989.839 ms means that approximately 95% of measured end-to-end completion latencies were at or below that value. It is not single-HMAC latency or peak database capacity.
The standard Maven verify run passed 83 Java tests with no failures, errors or skips. Separately, 15 functional/adversarial PostgreSQL scenarios and two real JVM outage cases passed. These categories are reported separately.
The experiment did not measure maximum capacity, comparative cost, a ten-minute soak or a million records. It supports the stated local workload rather than a production SLA.
Sources: docs/evidence/local-verification-2026-09-06.json, javaTests, scenarios, load; docs/evidence/local-recovery-2026-09-06.json; docs/reference/implementation-status.md.`;
const s=slide('Measured workload: 2,400 unique transfers',notes,{sub:'September 6: two databases + embedded ActiveMQ. 20 offered TPS for 120 seconds.'});
table(s,[['Measure','Observed result'],['Steady completed rate, seconds 10–120','20.3091 TPS'],['Whole run, including warmup and drain','19.9371 TPS'],['End-to-end completion latency, p95','3,990 ms'],['Automated Java tests','83 passed in recorded run'],['PostgreSQL functional and attack scenarios','15 passed'],['Real local JVM outage cases','2 passed']],65,200,1148,381,[768,380],{size:26});
text(s,'One Windows host, local software keys and simulated USD.\nThese results do not establish maximum capacity, comparative cost or a production SLA.',67,604,1140,64,23,{color:C.muted});
}
// 13. Production topology.
{
const notes=`Timing: 1.5–2 minutes.
This is a production requirements diagram, not another view of infrastructure already deployed locally. Amber denotes remaining work. The primary database retains the same red attacker-controlled boundary.
The first difference is independent governance. If one platform team or compromised root account can change primary, the application, key service and settlement, genuine control separation does not exist. Independent IAM and owners, restricted deployment rights, change review and key-use logs are required.
KMS or HSM means a real provider integration, including non-exportable keys where supported and separated authority. A local software vault must not be described as hardware non-exportability. Service transport must provide authentication and encryption, such as mTLS under the selected deployment design.
Audit and settlement must stay beyond the primary DBA's reach. Real financial execution needs an equivalent protected gateway to external rails. If a client continues to execute payments directly from primary through a bypass path, the guarantee does not transfer automatically.
Independently controlled storage or a witness must retain signed checkpoints, and public keys need trusted distribution. A sufficiently recent expected head prevents silent replacement with a valid historical prefix. Backups and PITR address recovery. WORM, when required, addresses retention policy. A TSA supplies independent timestamp evidence if the use case requires it. These services are absent from the local demo.
Further gates include dependency maintenance, security review, protocol interoperability, scalable Merkle maintenance, longer load runs, database-outage tests, recovery objectives and restore exercises. Security claims always depend on explicit assumptions and tested scope.
Sources: docs/reference/architecture.md; docs/reference/implementation-status.md, remaining production gates; docs/reference/protocol.md, Signed checkpoints.`;
const s=slide('Production target: independent control boundaries',notes,{sub:'Amber elements are requirements, not deployed demo infrastructure'});
const app=box(s,'Trusted application\nIdentity and business policy',65,226,300,90);
const primary=box(s,'Primary DB domain\nAssume attacker control',65,461,300,90,{color:C.red,fill:C.redPale});
const integ=box(s,'Integrity domain\nIndependent IAM and KMS/HSM',479,226,348,90,{color:C.amber,fill:C.amberPale});
const settle=box(s,'Verification and settlement\nProtected execution authority',479,461,348,90);
const audit=box(s,'Audit / Protected DB\nEvidence and accounting',940,226,274,90);
const retain=box(s,'Independent retention\nFresh heads and recovery',940,461,274,90,{color:C.amber,fill:C.amberPale});
edge(s,app,integ);edge(s,app,primary,{from:'bottom',to:'top'});edge(s,integ,audit);
edge(s,primary,settle,{color:C.red});edge(s,integ,settle,{from:'bottom',to:'top'});edge(s,audit,retain,{from:'bottom',to:'top',color:C.amber});edge(s,settle,audit,{kind:'elbow'});
text(s,'Allowed flows do not grant unrestricted database access.\nEncrypted service transport, deployment ownership and recovery drills remain required.',67,601,1140,62,23,{color:C.muted});
}
// 14. Customer integration and presentation close.
{
const notes=`Timing: 1–1.5 minutes, followed by questions.
For a potential customer, begin with the execution boundary rather than promising to plug in one library. Locate the business authorization decision and the point where money or another protected resource actually changes. Then define immutable fields and versioning, the application's identity and authorization responsibilities, and authoritative settlement beyond the primary DBA.
The module provides content authentication, an independent receipt, reconciliation, protected execution in the local prototype and audit checkpoints. Customer integration supplies user permissions, evidence of external funding, real payment APIs, operations and independent domain ownership. No universal adapter for MySQL or arbitrary database schemas is implemented.
A short demonstration can show a normal transfer and identical-key retry, a fabricated primary row, and a change after verification. Show the protected result and postings, not just an HTTP response. For a reliable presentation, retained reports can serve as recorded evidence without presenting them as a newly executed live run.
Local commands are documented in docs/reference/running.md. verify-local.ps1 runs tests, scenarios and load. recovery.ps1 really stops services and must run separately, without a concurrent demonstration. It should not start unexpectedly during a customer meeting. Never share .local: it contains secrets and key material. Share the sanitized JSON under docs/evidence and the prepared documents.
If asked about the guarantee, repeat the exact threat model from slide 2. If asked about novelty, explain the engineering integration and reproducible tests without claiming new cryptography. If asked for the next step, propose a bounded proof of concept around one customer business operation with explicit permissions and acceptance criteria.
Sources: docs/reference/architecture.md; docs/reference/running.md; docs/reference/implementation-status.md; scripts/scenarios.mjs.`;
const s=slide('Customer integration and demo walkthrough',notes);
text(s,'Integration boundary',67,184,518,44,31,{color:C.navy,bold:true});
bullets(s,['Application owns user authorization','Protected execution owns balances and effects','Operations bind exact business fields','Independent custody becomes a deployment gate'],67,249,550,328,27);
text(s,'Three scenarios to explain',693,184,518,44,31,{color:C.navy,bold:true});
bullets(s,['Normal transfer plus an identical retry','Fabricated primary row with a fake MAC','Post-verification tampering (test harness)'],693,249,512,270,27);
text(s,'The next integration starts with one operation, one threat model\nand explicit acceptance criteria.',67,595,1120,65,29,{color:C.blue,bold:true});
}

// A notes-only import is not valid for an architecture revision.
if (process.env.DECK_EDIT_SOURCE) throw new Error('Architecture revision requires the updated visible slide generator');

await fs.mkdir(tmp,{recursive:true});await fs.mkdir(out,{recursive:true});
await fs.writeFile(path.join(tmp,'slide-plan.json'),JSON.stringify(metadata.map(({number,title})=>({number,title})),null,2));
await fs.writeFile(path.join(out,'notes.md'),'# Presenter notes\n\nPresentation: Verifiable Record Integrity Without a Blockchain. 14 slides, about 15–20 minutes plus questions. Slide notes provide detailed explanations.\n\n'+metadata.map(x=>`## ${x.number}. ${x.title}\n\n${x.notes}`).join('\n\n'));
const candidate=path.join(tmp,'candidate.pptx');
await(await PresentationFile.exportPptx(p)).save(candidate);
console.log('Candidate exported');
for(let i=0;i<p.slides.items.length;i++){
const s=p.slides.items[i];
const preview=await p.export({slide:s,format:'png',scale:1});
await fs.writeFile(path.join(tmp,`draft-${String(i+1).padStart(2,'0')}.png`),new Uint8Array(await preview.arrayBuffer()));
const layout=await s.export({format:'layout'});await fs.writeFile(path.join(tmp,`draft-${String(i+1).padStart(2,'0')}.layout.json`),await layout.text());
console.log(`Rendered draft ${i+1}`);
}
const finalPath=path.join(out,process.env.DECK_OUTPUT_NAME??'demo.pptx');
const result=await finalizePresentation({workspaceDir:root,candidatePath:candidate,finalPath,pythonExecutable:python,
integrityValidatorPath:path.join(skill,'container_tools/inspect_presentation_package_integrity.py'),layoutValidatorPath:path.join(skill,'container_tools/inspect_presentation_layout_geometry.py'),
layoutArgs:['--expected-slide-size-emu','12192000,6858000','--validate-bullet-geometry','--validate-heading-fit',...[2,5,6,9,11,12].flatMap(n=>['--require-native-table-slide',String(n)])],
explicitTotalSlideCount:14,requiredNativeTableOwnerSlides:[2,5,6,9,11,12],requiredNativeChartOwnerSlides:[],fontPolicy,verifyArtifactToolImport:true,
receiptPath:path.join(tmp,process.env.DECK_RECEIPT_NAME??'final.validation.json')});
console.log(JSON.stringify(result));
const finalDeck=await PresentationFile.importPptx(await FileBlob.load(finalPath));
for(let i=0;i<finalDeck.slides.items.length;i++){
const preview=await finalDeck.export({slide:finalDeck.slides.items[i],format:'png',scale:1});
await fs.writeFile(path.join(tmp,`final-${String(i+1).padStart(2,'0')}.png`),new Uint8Array(await preview.arrayBuffer()));
console.log(`Rendered final ${i+1}`);
}
