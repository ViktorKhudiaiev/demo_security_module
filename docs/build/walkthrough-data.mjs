// Educational ordering, not captured network timing. Responses are summarized in step descriptions.
export const flowSteps = [
 {title:'Request an authorized transfer',from:'user',to:'app',label:'1 · Transfer request',path:'M125 145 H190',text:'The integrating application owns business-user authorization. Module 1 uses a demo API credential, not a full identity provider. The security module protects against a Primary DB attacker; it does not establish the caller\'s account entitlements.'},
 {title:'Authenticate the exact operation',from:'app',to:'keys',label:'2 · Issue HMAC',path:'M420 145 H505',text:'Module 1 asks Module 2 to authenticate the canonical operation. UUID, accounts, amount, currency, microsecond timestamp, schema version, key ID and correction reference are bound into its HMAC.'},
 {title:'Commit the independent original',from:'keys',to:'audit',label:'3 · Commit issuance receipt',path:'M735 145 H780 V430 H845',text:'The key service commits issuance_receipts in Audit/Protected DB before returning the authenticated operation and tag. Only the key service holds secret key bytes; verification does not issue a replacement tag.'},
 {title:'Publish the instruction and its outbox together',from:'app',to:'primary',label:'4 · Primary SQL commit',path:'M305 205 V250 H965 V205',text:'After the issuance response, one Primary SQL transaction inserts transactions and transaction_outbox. There is no database trigger, NOTIFY channel or direct broker write in this application transaction.'},
 {title:'Poll the outbox for identifiers',from:'processor',to:'primary',label:'5 · Poll Primary outbox',path:'M420 405 H465 V275 H1115 V160 H1085',text:'The processor dispatcher polls pending outbox IDs. Independent receipt-inventory and source scans also discover deleted, missing-hint or fabricated records. IDs are hints, never permission to debit.'},
 {title:'Persist the hint in embedded ActiveMQ',from:'processor',to:'queue',label:'6 · Persistent UUID message',path:'M420 405 H505',text:'A synchronous persistent send writes the UUID to ActiveMQ Classic 6.3.2 and its local KahaDB journal. Only after durable acceptance does the dispatcher acknowledge the Primary outbox. An uncertain response may produce a duplicate.'},
 {title:'Deliver into the protected SQL inbox',from:'queue',to:'processor',label:'7 · Deliver UUID; commit job; ACK',path:'M505 430 H420',text:'The consumer in the same processor JVM inserts operation_jobs in Audit/Protected DB. It commits the JMS receive only after that SQL insert commits. On failure it rolls back the receive. Duplicate UUIDs produce one job, not another payment.'},
 {title:'Check the independent receipt before the source',from:'processor',to:'keys',label:'8 · Receipt first, then VerifyMac',path:'M305 345 V295 H620 V205',text:'A claimed job must have an independently issued receipt. The processor then reads Primary, compares canonical content and hash, and calls the key service to verify the HMAC. A missing receipt or altered record cannot authorize postings.'},
 {title:'Record verification evidence',from:'processor',to:'audit',label:'9 · VERIFIED audit event',path:'M420 370 H805 V405 H845',text:'A VERIFIED event records what was checked; its presence alone is not execution permission. An integrity discrepancy follows a separate path: the incident and first notification commit together in Audit/Protected. A dedicated dispatcher retries SMTP without gating settlement. Local Mailpit captures email, not external Gmail delivery.'},
 {title:'Re-read and execute only the checked snapshot',from:'processor',to:'primary',label:'10 · Final source comparison',path:'M305 345 V270 H965 V205',text:'Immediately before settlement, Primary must still match the checked snapshot. Java calculates the debit and credit from those exact fields, never from a later untrusted amount or recipient read.'},
 {title:'Commit one protected financial effect',from:'processor',to:'audit',label:'11 · Atomic accounting SQL commit',path:'M420 450 H800 V470 H845',text:'One SQL transaction in Audit/Protected DB locks the operation and accounts, commits both postings, balances, journal, result, durable outcome and job completion. The unique operation ID prevents a second financial effect on retries.'},
 {title:'Relay the durable outcome and status',from:'processor',to:'primary',label:'12 · Audit outcome + Primary projection',path:'M305 465 V535 H1125 V185 H1085',text:'The processor relays settlement_outbox into the protected audit history and transaction_status_events in Primary. This retryable projection is not another payment. The application reads the authoritative result from the processor. Merkle checkpoints run independently.'}
];
export const participants = [
 ['User','Authorized caller'],['Module 1','Transfer app'],['Module 2','Key service'],
 ['Main DB','Primary PostgreSQL'],['ActiveMQ','Inside processor'],['Module 3','Processor'],['Protected DB','Audit PostgreSQL']
];
export const sequenceMessages = [
 [0,1,'1','Request transfer'],
 [1,2,'2','Issue canonical operation + HMAC'],
 [2,6,'3','Commit independent receipt; return tag to application'],
 [1,3,'4','Commit transactions + outbox'],
 [5,3,'5','Poll pending operation IDs'],
 [5,4,'6','Persist UUID; then ACK Primary outbox'],
 [4,5,'7','Deliver UUID (transacted JMS receive)'],
 [5,6,'7','Commit unique job; then ACK JMS receive'],
 [5,2,'8','Read independent issuance receipt FIRST'],
 [5,3,'8','Read source; compare canonical content + hash'],
 [5,2,'8','Verify HMAC; no new issued token'],
 [5,6,'9','Append VERIFIED event'],
 [5,3,'10','Re-read source; require exact checked snapshot'],
 [5,6,'11','Atomic postings + balances + result + outcome outbox'],
 [5,6,'12','Relay outcome into audit history'],
 [5,3,'12','Project status; app reads authoritative processor result']
];
