import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {buildPublicationCopies} from './build-publication-copies.mjs';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const runtimeModules='C:/Users/vikto/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules';
const {marked}=await import(pathToFileURL(path.join(runtimeModules,'marked/lib/marked.esm.js')).href);
const style=`:root{--ink:#172a42;--muted:#53657d;--blue:#215acc}*{box-sizing:border-box}body{margin:0;background:#f3f6fa;color:var(--ink);font:18px/1.75 Georgia,'Times New Roman',serif}header{max-width:1000px;margin:25px auto;font:16px/1.4 'Segoe UI',Arial,sans-serif;padding:0 30px;display:flex;justify-content:space-between;gap:20px}main{max-width:1000px;margin:auto;background:white;padding:45px 65px 65px;border-top:5px solid var(--blue)}h1{font-size:42px;line-height:1.13;letter-spacing:-.03em}h2{font:700 26px/1.25 'Segoe UI',Arial,sans-serif;letter-spacing:-.02em;margin:38px 0 15px}h3{font:700 21px/1.3 'Segoe UI',Arial,sans-serif}p{margin:0 0 18px}a{color:var(--blue);text-underline-offset:3px}table{width:100%;border-collapse:collapse;font:16px/1.6 'Segoe UI',Arial,sans-serif;margin:22px 0}td,th{padding:13px;border-bottom:1px solid #cad5e4;vertical-align:top;text-align:left}th{background:#eaf1fb}code{font:14px/1.6 Consolas,monospace;overflow-wrap:anywhere;background:#eef3fa;padding:2px 4px}pre{padding:18px;overflow:auto;background:#eef3fa;white-space:pre-wrap}blockquote{margin:20px 0;padding-left:24px;border-left:3px solid var(--blue)}li{margin:10px 0}footer{max-width:1000px;margin:24px auto;padding:0 30px;color:var(--muted);font:14px/1.6 'Segoe UI',Arial,sans-serif}button{font:16px 'Segoe UI',Arial,sans-serif;background:white;color:var(--blue);border:1px solid #cad5e4;border-radius:5px;padding:8px 14px;cursor:pointer}@media(max-width:700px){main{padding:25px 20px}h1{font-size:32px}table{font-size:14px}td,th{padding:8px}}@media print{header,footer{display:none}main{border:0;padding:0;max-width:none}body{background:white;font-size:11pt}h1{font-size:26pt}h2{font-size:17pt;break-after:avoid}table{font-size:10pt}tr{break-inside:avoid}a{color:inherit}}`;
for(const [source,output,title] of [['docs/article/article.md','docs/article/index.html','Verifiable Record Integrity Without a Blockchain'],['docs/presentation/guide.md','docs/presentation/guide.html','Presenter handbook']]){
 const markdown=await fs.readFile(path.join(root,source),'utf8');
 const content=marked.parse(markdown,{gfm:true});
 if(/\p{Script=Cyrillic}/u.test(markdown))throw new Error('Document content violates project text conventions: '+source);
 const html=`<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${title}</title><style>${style}</style></head><body><header><a href="../index.html">Interactive walkthrough</a><button id="print-document" type="button">Print</button></header><main>${content}</main><footer>Viktor Khudiaiev · September 2026 · Local reference implementation. Software keys, simulated transfers, explicit trust boundaries.</footer><script>document.getElementById('print-document').onclick=()=>window.print();</script></body></html>`;
 await fs.writeFile(path.join(root,output),html);
 if(source==='docs/article/article.md'){
  const build=path.join(root,'.local/artifact-qa/pdfs/publication');
  await fs.mkdir(build,{recursive:true});
  await fs.writeFile(path.join(build,'article-body.html'),content);
 }
 console.log('Built '+output);
}
await buildPublicationCopies(root,marked,style);
