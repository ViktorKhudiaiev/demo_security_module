// Explicit, selected-field publication. Never copy a runtime directory into a release.
import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const args=process.argv.slice(2);
if(args.length!==4&&args.length!==5)throw new Error('Usage: publish-current-evidence.mjs <verification-dir> <recovery-dir> <live-report> <migration-report> [new-verification-filename]');
if(args[4]&&!/^local-verification-[0-9-]+-final\.json$/.test(args[4]))throw new Error('Invalid verification output filename');
const pick=(value,keys)=>Object.fromEntries(keys.filter(key=>value[key]!==undefined).map(key=>[key,value[key]]));
async function read(relative){
 const file=path.resolve(root,relative),base=path.join(root,'.local')+path.sep;
 if(!file.startsWith(base))throw new Error('Evidence input must be an explicit local report');
 const bytes=await fs.readFile(file),data=JSON.parse(bytes);
 if(data.passed!==true)throw new Error('Only a passing measured report can be published here');
 return {data,sha256:createHash('sha256').update(bytes).digest('hex')};
}
const [combined,load,scenarios,recovery,live,migration]=await Promise.all([
 read(path.join(args[0],'report.json')),read(path.join(args[0],'load/report.json')),
 read(path.join(args[0],'scenarios/report.json')),read(path.join(args[1],'report.json')),read(args[2]),read(args[3])]);
const provenance=raw=>({rawSha256:raw.sha256,note:'Selected fields from retained local evidence. No credentials, keys or absolute workstation paths.'});
const verification={scope:'Two PostgreSQL databases; embedded persistent ActiveMQ; software keys; one trusted Windows host; simulated funds.',
 provenance:{runId:combined.data.runId,rawCombinedSha256:combined.sha256,rawLoadSha256:load.sha256,rawScenariosSha256:scenarios.sha256},
 ...pick(combined.data,['startedAt','endedAt','passed','build','harnessTests','normalRestart']),javaTests:combined.data.unitTests,
 scenarios:scenarios.data.checks.map(x=>pick(x,['name','passed','durationMs'])),
 load:pick(load.data,['runId','startedAt','endedAt','nodeVersion','platform','host','configuration','expectedCount','submitted','completed','uniqueOperationCount','failures','offeredTps','steadyCompletedTps','totalCompletedTpsIncludingDrain','drainTimeMs','backlogGrowth','latencyMs','criteria','passed','balances','limitations'])};
const outputs={
 'local-verification-2026-09-06.json':verification,
 'local-recovery-2026-09-06.json':{...pick(recovery.data,['runId','startedAt','endedAt','passed','checks','normalRestart','limitations']),provenance:provenance(recovery)},
 'live-lab-verification-2026-09-06.json':{...pick(live.data,['kind','startedAt','finishedAt','passed','checks','limits']),provenance:provenance(live)},
 'protected-migration-2026-09-06.json':{...pick(migration.data,['runId','startedAt','endedAt','passed','tables','notes']),provenance:provenance(migration)}
};
const selected=args[4]?{[args[4]]:verification}:outputs;
for(const [name,report] of Object.entries(selected)){
 const text=JSON.stringify(report,null,2)+'\n';
 if(/[A-Z]:[\\/]|password|clientSecret|privateKey|Bearer\s/i.test(text))throw new Error('Sensitive publication content rejected');
 await fs.writeFile(path.join(root,'docs/evidence',name),text,{flag:'wx'});
 console.log('Published '+name);
}
