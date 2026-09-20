import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import vm from 'node:vm';
import {mediumMarkdown,disclaimer,referenceRevision,linkedinPreview} from './build-publication-copies.mjs';

const read=name=>fs.readFile(new URL('../'+name,import.meta.url),'utf8');

test('current article formats and the LinkedIn text retain the disclaimer',async()=>{
  for(const file of ['article/article.md','article/index.html','article/medium-article.md','article/medium-article.html','article/linkedin-post.txt']){
    assert.ok((await read(file)).includes(disclaimer),file);
  }
});

test('Medium copies are synchronized and all reference links are public',async()=>{
  const article=await read('article/article.md'),medium=await read('article/medium-article.md');
  assert.equal(medium,mediumMarkdown(article));
  assert.doesNotMatch(medium,/^\|/m);
  for(const match of medium.matchAll(/\]\(([^)]+)\)/g))assert.match(match[1],/^https:\/\//);
  assert.ok(medium.includes(`/blob/${referenceRevision}/docs/evidence/local-verification-2026-09-07.json`));
  for(const block of article.matchAll(/(?:^\|.+\|\r?\n)+/gm)){
    for(const row of block[0].trim().split(/\r?\n/)){
      for(const cell of row.slice(1,-1).split('|').map(value=>value.trim())){
        if(!/^:?-+:?$/.test(cell))assert.ok(medium.includes(cell),cell);
      }
    }
  }
  for(const value of ['19.963636363636365','137','September 7, 2026','not a new throughput benchmark'])assert.ok(medium.includes(value),value);
  const html=await read('article/medium-article.html');
  assert.doesNotMatch(html,/<(?:script|table|header|footer)\b|localhost|127\.0\.0\.1|file:\/\//i);
});

test('LinkedIn post is copy-ready for a PDF document post',async()=>{
  const text=await read('article/linkedin-post.txt');
  assert.match(text,/attached article/);
  assert.doesNotMatch(text,/INSERT|PLACEHOLDER|localhost|file:\/\//i);
  assert.ok(text.length<3000);
});

test('Medium generation refuses an article without its disclaimer',()=>{
  assert.throws(()=>mediumMarkdown('# Example\n'),/disclaimer/);
});

test('LinkedIn preview copies exactly the plain post including blank lines',async()=>{
  const post=await read('article/linkedin-post.txt'),html=await read('article/linkedin-post.html');
  assert.equal(html,linkedinPreview(post));
  let copied;
  const nodes={'copy-post':{},'copy-source':{value:post},'copy-status':{}};
  const document={getElementById:id=>nodes[id]};
  const script=html.match(/<script>([\s\S]*?)<\/script>/)[1];
  vm.runInNewContext(script,{document,navigator:{clipboard:{writeText:async text=>{copied=text;}}}});
  await nodes['copy-post'].onclick();
  assert.equal(copied,post);
  assert.match(copied,/WHAT THE APPROACH ADDS\n\n1\./);
  assert.match(nodes['copy-status'].textContent,/Copied/);
  assert.doesNotMatch(copied,/Download cover|publishing instructions/);
});

test('LinkedIn preview offers manual selection if clipboard access is denied',async()=>{
  const html=await read('article/linkedin-post.html');let focused=false,selected=false;
  const nodes={'copy-post':{},'copy-source':{hidden:true,focus(){focused=true;},select(){selected=true;}},'copy-status':{}};
  vm.runInNewContext(html.match(/<script>([\s\S]*?)<\/script>/)[1],{document:{getElementById:id=>nodes[id]},navigator:{}});
  await nodes['copy-post'].onclick();
  assert.equal(nodes['copy-source'].hidden,false);assert.ok(focused&&selected);
  assert.match(nodes['copy-status'].textContent,/Ctrl\+C/);
});
