// Foreground, loopback-only live lab. No arbitrary SQL, proxy routes or process controls.
import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import {randomBytes,timingSafeEqual,createHash} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {LiveBridge,ROOT,validateAction} from './bridge.mjs';

const PORT=8090;
const routes=new Map([
 ['/docs/live/index.html','docs/live/index.html'],
 ['/docs/live/client.js','docs/live/client.js'],
 ['/docs/index.html','docs/index.html'],
 ['/docs/article/index.html','docs/article/index.html'],
 ['/docs/presentation/guide.html','docs/presentation/guide.html'],
 ['/docs/article/article.pdf','docs/article/article.pdf'],
 ['/docs/presentation/demo.pptx','docs/presentation/demo.pptx'],
 ['/docs/downloads/demo-materials.zip','docs/downloads/demo-materials.zip'],
 ['/docs/article/article.md','docs/article/article.md'],
 ['/docs/presentation/notes.md','docs/presentation/notes.md'],
]);
const redirects=new Map([
 ['/','/docs/index.html'],
 ['/docs/customer-demo.html','/docs/index.html'],
 ['/docs/live-demo.html','/docs/live/index.html'],
]);
// Explicit public references from the presenter handbook; never serve a directory.
for(const file of [
 'docs/evidence/local-verification-2026-09-06-final.json',
 'docs/evidence/notification-verification-2026-09-08.json',
 'docs/evidence/local-verification-2026-09-07.json',
 'docs/evidence/local-verification-2026-09-06.json','docs/evidence/local-recovery-2026-09-06.json','docs/evidence/live-lab-verification-2026-09-06.json','docs/evidence/protected-migration-2026-09-06.json',
 'docs/reference/architecture.md','docs/reference/architecture-decisions.md','docs/reference/implementation-status.md','docs/reference/glossary.md',
 'docs/evidence/local-verification-2026-09-05.json','docs/evidence/local-recovery-2026-09-05.json','docs/evidence/live-lab-verification-2026-09-05.json',
 'docs/reference/protocol.md','docs/reference/running.md','scripts/scenarios.mjs','scripts/load.mjs','scripts/recovery.mjs',
 'docs/reference/code-structure.md','docs/reference/contributing.md','docs/reference/notifications.md',
 'account-transfer-app/src/main/java/com/demo/transferapp/service/TransferService.java',
 'tokenization-module/src/main/java/com/demo/integrity/crypto/CanonicalEncoder.java',
 'tokenization-module/src/main/java/com/demo/keyservice/service/IssuanceService.java','tokenization-module/src/main/java/com/demo/keyservice/vault/LocalKeyVault.java',
 'transaction-security-module/src/main/java/com/demo/securityapp/notification/NotificationDispatcher.java',
 'transaction-security-module/src/main/java/com/demo/securityapp/notification/NotificationOutbox.java',
 'transaction-security-module/src/main/java/com/demo/securityapp/notification/SmtpNotificationSender.java',
 'transaction-security-module/src/main/java/com/demo/securityapp/service/Processor.java','transaction-security-module/src/main/java/com/demo/securityapp/audit/AuditLog.java','transaction-security-module/src/main/java/com/demo/securityapp/crypto/MerkleTree.java'
])routes.set('/'+file,file);
export function authorize(req,{port=PORT,capability},requireCapability=false){
 const expectedHost=`127.0.0.1:${port}`,origin=`http://${expectedHost}`;
 const raw=req.rawHeaders??[];
 if(raw.filter((v,i)=>i%2===0&&v.toLowerCase()==='host').length!==1)return false;
 if(req.headers.host!==expectedHost||!req.url.startsWith('/')||req.url.startsWith('//')||req.url.length>2048)return false;
 if(req.headers['x-forwarded-host']||req.headers.forwarded)return false;
 if(raw.filter((v,i)=>i%2===0&&v.toLowerCase()==='origin').length>1)return false;
 if(req.headers.origin!==undefined&&req.headers.origin!==origin)return false;
 if(req.headers['sec-fetch-site']&&!['same-origin','none'].includes(req.headers['sec-fetch-site']))return false;
 if(req.method==='POST'&&req.headers.origin!==origin)return false;
 if(requireCapability){
  const token=req.headers['x-demo-session'];if(typeof token!=='string'||!/^[0-9a-f]{64}$/.test(token)||token.length!==capability.length)return false;
  if(!timingSafeEqual(Buffer.from(token),Buffer.from(capability)))return false;
 }
 return true;
}
async function readBody(req){
 if(req.headers['content-type']!=='application/json')throw new Error('JSON required');
 let size=0,body='';for await(const chunk of req){size+=chunk.length;if(size>1024)throw new Error('Request exceeds lab limit');body+=chunk;}
 return JSON.parse(body);
}
export function createLabServer(bridge,{port=PORT,capability=randomBytes(32).toString('hex')}={}){
 const common={'Cache-Control':'no-store','X-Content-Type-Options':'nosniff','X-Frame-Options':'DENY','Referrer-Policy':'no-referrer','Cross-Origin-Resource-Policy':'same-origin'};
 const send=(res,status,body)=>{res.writeHead(status,{...common,'Content-Type':'application/json; charset=utf-8'});res.end(JSON.stringify(body));};
 const server=http.createServer(async(req,res)=>{
  try{
   const activePort=port===0?server.address().port:port;
   if(!authorize(req,{port:activePort,capability})){send(res,403,{error:'This lab accepts only its exact loopback origin.'});return;}
   if(req.url==='/api/session'&&req.method==='GET'){send(res,200,{capability});return;}
   if(req.url.startsWith('/api/')){
    if(!authorize(req,{port:activePort,capability},true)){send(res,403,{error:'Lab session authorization required.'});return;}
    if(req.url==='/api/state'&&req.method==='GET'){send(res,200,await bridge.state());return;}
    if(req.url==='/api/action'&&req.method==='POST'){
     let action;try{action=validateAction(await readBody(req));}catch{send(res,400,{error:'Use one fixed lab action with a small JSON request.'});return;}
     try{bridge.start(action);}catch(error){send(res,409,{error:error.message});return;}
     send(res,202,{accepted:true,action});return;
    }
    send(res,404,{error:'Unknown lab endpoint.'});return;
   }
   if(!['GET','HEAD'].includes(req.method)){send(res,405,{error:'Method not allowed.'});return;}
   const redirect=redirects.get(req.url);
   if(redirect){res.writeHead(302,{...common,Location:redirect});res.end();return;}
   const relative=routes.get(req.url);if(!relative){send(res,404,{error:'Not found.'});return;}
   const file=await fs.readFile(path.join(ROOT,relative));const extension=path.extname(relative);
   const mime={'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.json':'application/json; charset=utf-8','.md':'text/plain; charset=utf-8','.java':'text/plain; charset=utf-8','.mjs':'text/plain; charset=utf-8','.pdf':'application/pdf','.zip':'application/zip','.pptx':'application/vnd.openxmlformats-officedocument.presentationml.presentation'}[extension];
   const scriptHashes=extension==='.html'?[...file.toString('utf8').matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m=>`'sha256-${createHash('sha256').update(m[1]).digest('base64')}'`):[];
   res.writeHead(200,{...common,'Content-Type':mime,'Content-Security-Policy':`default-src 'none'; script-src 'self' ${scriptHashes.join(' ')}; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; object-src 'none'`});res.end(req.method==='HEAD'?undefined:file);
  }catch{send(res,503,{error:'The local lab could not obtain evidence. No success is inferred.'});}
 });
 server.requestTimeout=15000;server.headersTimeout=10000;server.maxHeadersCount=30;
 return server;
}
export async function startLab(){
 const bridge=new LiveBridge();await bridge.load();const server=createLabServer(bridge);
 server.on('error',()=>{console.error('Live lab could not bind its dedicated port. No existing process was stopped.');process.exitCode=1;});
 server.listen(PORT,'127.0.0.1',()=>console.log(`Live transaction lab: http://127.0.0.1:${PORT}/docs/live/index.html\nUses dedicated local fixtures and simulated USD. Press Ctrl+C to stop the lab; database evidence is retained.`));
 return server;
}
if(process.argv[1]&&import.meta.url===pathToFileURL(path.resolve(process.argv[1])).href){startLab().catch(()=>{console.error('Live lab startup failed. Start the local project services and check its local configuration.');process.exitCode=1;});}
