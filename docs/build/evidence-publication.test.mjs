import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, mkdir, copyFile, writeFile, readFile, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import {createHash} from 'node:crypto';

const source=fileURLToPath(new URL('./publish-current-evidence.mjs',import.meta.url));
async function fixture(t,passed){
 const root=await mkdtemp(path.join(tmpdir(),'integrity-publication-'));
 t.after(()=>rm(root,{recursive:true,force:true}));
 await mkdir(path.join(root,'docs/build'),{recursive:true});
 await mkdir(path.join(root,'docs/evidence'));
 await mkdir(path.join(root,'.local/run/load'),{recursive:true});
 await mkdir(path.join(root,'.local/run/scenarios'));
 await copyFile(source,path.join(root,'docs/build/publish-current-evidence.mjs'));
 const combined=JSON.stringify({runId:'fixture',passed,unitTests:{tests:1},
   nodeRuntime:{path:'C:\\private\\node.exe'},password:'fixture-not-a-real-secret'});
 await writeFile(path.join(root,'.local/run/report.json'),combined);
 await writeFile(path.join(root,'.local/run/load/report.json'),JSON.stringify({passed,
   steadyCompletedTps:passed?20:19.963636363636365,configuration:{targetTps:20,completionTolerance:0},
   criteria:{steadyThroughput:passed},unselectedField:'private fixture detail'}));
 await writeFile(path.join(root,'.local/run/scenarios/report.json'),JSON.stringify({passed:true,
   checks:[{name:'fixture-check',passed:true,evidence:{password:'fixture-secret'}}]}));
 const run=(...args)=>spawnSync(process.execPath,['docs/build/publish-current-evidence.mjs',...args],{cwd:root,encoding:'utf8'});
 return {root,combined,run};
}

for(const passed of [true,false])test(`publication preserves ${passed?'passing':'failing'} evidence and redacts unselected fields`,async t=>{
 const {root,combined,run}=await fixture(t,passed);
 const result=run('.local/run','local-verification-2099-01-01.json');
 assert.equal(result.status,0,result.stderr);
 const text=await readFile(path.join(root,'docs/evidence/local-verification-2099-01-01.json'),'utf8');
 const report=JSON.parse(text);
 assert.equal(report.passed,passed);
 assert.equal(report.load.passed,passed);
 assert.equal(report.load.criteria.steadyThroughput,passed);
 assert.equal(report.load.configuration.completionTolerance,0);
 assert.equal(report.provenance.rawCombinedSha256,createHash('sha256').update(combined).digest('hex'));
 assert.doesNotMatch(text,/password|private|unselectedField|[A-Z]:\\/);
});

test('publication refuses overwrite, path escape and missing outcome',async t=>{
 const {root,run}=await fixture(t,false);
 assert.equal(run('.local/run','local-verification-2099-01-01.json').status,0);
 assert.notEqual(run('.local/run','local-verification-2099-01-01.json').status,0);
 assert.notEqual(run('.local/run','../escape.json').status,0);
 assert.notEqual(run('docs','local-verification-2099-01-02.json').status,0);
 await writeFile(path.join(root,'.local/run/report.json'),JSON.stringify({runId:'missing-outcome'}));
 assert.notEqual(run('.local/run','local-verification-2099-01-02.json').status,0);
});
