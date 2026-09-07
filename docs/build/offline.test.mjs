import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import vm from 'node:vm';

const entry=new URL('../index.html',import.meta.url);
const html=await fs.readFile(entry,'utf8');

function documentStub(source){
 const nodes=new Map();
 const make=()=>({textContent:'',children:[],attributes:{},hidden:false,disabled:false,
  append(...items){this.children.push(...items);},replaceChildren(...items){this.children=items;},
  setAttribute(name,value){this.attributes[name]=value;},classList:{toggle(){}}});
 for(const tag of source.matchAll(/<[^>]+\bid="([^"]+)"[^>]*>/g)){
  const element=make();element.id=tag[1];element.hidden=/\bhidden\b/.test(tag[0]);element.disabled=/\bdisabled\b/.test(tag[0]);nodes.set(element.id,element);
 }
 return {nodes,getElementById(id){assert.ok(nodes.has(id),`Missing HTML element: ${id}`);return nodes.get(id);},createElement:make,querySelectorAll(){return [];}};
}

test('walkthrough is self-contained and every companion file exists',async()=>{
 assert.match(html,/<html lang="en">/);
 assert.doesNotMatch(html,/@@[A-Z_]+@@|\bfetch\s*\(|XMLHttpRequest|WebSocket|<base\b/);
 assert.doesNotMatch(html,/<script[^>]+\bsrc=|<link[^>]+\brel="stylesheet"|@import|\btype="module"/);
 for(const relative of ['../index.html','../article/index.html','../presentation/guide.html','../live/index.html']){
  const source=new URL(relative,import.meta.url),text=await fs.readFile(source,'utf8');
  for(const match of text.matchAll(/\b(?:href|src)="([^"]+)"/g)){
   const target=new URL(match[1].replaceAll('&amp;','&'),source);
   if(target.protocol!=='file:')continue;
   target.hash='';target.search='';assert.ok((await fs.stat(target)).isFile(),`${relative}: ${match[1]}`);
  }
 }
});

test('offline tabs, all twelve flow steps, glossary and evidence work without network',()=>{
 const document=documentStub(html);
 const noNetwork=()=>assert.fail('An offline page attempted a network request');
 const context=vm.createContext({document,fetch:noNetwork,XMLHttpRequest:noNetwork,WebSocket:noNetwork});
 for(const script of html.matchAll(/<script>([\s\S]*?)<\/script>/g))vm.runInContext(script[1],context);
 const get=id=>document.getElementById(id);
 for(const id of ['overview','architecture','sequence','evidence','terms','materials']){
  get('tab-'+id).onclick();assert.equal(get(id).hidden,false);assert.equal(get('tab-'+id).attributes['aria-selected'],'true');
 }
 assert.equal(get('flow-step').children.length,12);
 assert.equal(get('flow-back').disabled,true);
 for(let i=0;i<11;i++)get('flow-next').onclick();
 assert.equal(get('flow-step').value,'11');assert.equal(get('flow-next').disabled,true);
 get('flow-step').onchange({target:{value:'0'}});assert.equal(get('flow-back').disabled,true);
 assert.ok(get('glossary').children.length>10);assert.equal(get('scenario-evidence').children.length,15);
 assert.equal(JSON.parse(get('raw-evidence').textContent).verification.passed,true);
});

test('live lab opened from disk stays offline and cannot silently execute experiments',async()=>{
 const page=await fs.readFile(new URL('../live/index.html',import.meta.url),'utf8');
 const code=await fs.readFile(new URL('../live/client.js',import.meta.url),'utf8');
 const document=documentStub(page),noNetwork=()=>assert.fail('File-mode lab attempted network access');
 vm.runInNewContext(code,{document,location:{origin:'null'},Intl,fetch:noNetwork,setTimeout:noNetwork});
 assert.equal(document.getElementById('offline').hidden,false);
 assert.equal(document.getElementById('connected').hidden,true);
 for(const id of ['setup','valid','forged','new-demo'])assert.equal(document.getElementById(id).disabled,true);
});
