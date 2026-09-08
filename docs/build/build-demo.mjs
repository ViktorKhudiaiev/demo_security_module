import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import vm from 'node:vm';
import {architectureSvg,sequenceSvg,productionSvg} from './build-walkthrough-diagrams.mjs';
import {flowSteps} from './walkthrough-data.mjs';
import {glossary} from './glossary.mjs';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const read=p=>fs.readFile(path.join(root,p),'utf8');
export function validateEvidenceReport(report,name){
 if(!report||typeof report.passed!=='boolean')throw new Error(`Evidence ${name} must declare a boolean passed result`);
 // Failed measurements are evidence too; publication must not select passing runs only.
 return report;
}
export async function buildDemo(){
let html=await read('docs/build/customer-demo.template.html');
const model='const glossary='+JSON.stringify(glossary).replaceAll('<','\\u003c')+';';
const evidence={verification:JSON.parse(await read('docs/evidence/local-verification-2026-09-07.json')),historicalVerification:JSON.parse(await read('docs/evidence/local-verification-2026-09-06-final.json')),notifications:JSON.parse(await read('docs/evidence/notification-verification-2026-09-08.json')),recovery:JSON.parse(await read('docs/evidence/local-recovery-2026-09-06.json')),liveLab:JSON.parse(await read('docs/evidence/live-lab-verification-2026-09-06.json'))};
for(const [name,report] of Object.entries(evidence))validateEvidenceReport(report,name);
html=html.replace('@@MODEL@@',()=>model).replace('@@EVIDENCE@@',()=>JSON.stringify(evidence).replaceAll('<','\\u003c')).replace('@@FLOW_STEPS@@',()=>JSON.stringify(flowSteps)).replace('@@ARCHITECTURE_SVG@@',()=>architectureSvg()).replace('@@SEQUENCE_SVG@@',()=>sequenceSvg()).replace('@@TARGET_SVG@@',()=>productionSvg());
if(/@@[A-Z_]+@@/.test(html))throw new Error('Unresolved template');
if(/\p{Script=Cyrillic}/u.test(html))throw new Error('Document content violates project text conventions');
for(const script of html.matchAll(/<script>([\s\S]*?)<\/script>/g))new vm.Script(script[1]);
if(/\bfetch\s*\(|XMLHttpRequest|https?:\/\/[^"'<>\s]+\.(js|css)/.test(html))throw new Error('Unexpected network dependency');
await fs.writeFile(path.join(root,'docs/index.html'),html);
console.log('Built docs/index.html: overview, numbered architecture, sequence diagram and retained evidence.');
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))await buildDemo();
