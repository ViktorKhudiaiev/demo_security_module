import fs from 'node:fs/promises';
import path from 'node:path';

// This revision supplies public supporting references, not the current draft.
export const referenceRevision='9afdac2fc062241ee646742fa3179a83cc4b0b99';
export const disclaimer='The views expressed in this article are my own and do not reflect those of my employer.';
export const mediumTitle='If Someone Changes Your Database, Should Your System Still Execute the Payment?';
const escapeHtml=text=>text.replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;');

export function linkedinPreview(post){
  const paragraphs=post.trim().split(/\r?\n\r?\n/).map((paragraph,index)=>{
    const className=index<3?'hook':/^[A-Z ]+$/.test(paragraph)?'section-label':/^\d\./.test(paragraph)?'point':'body-copy';
    return `<p class="${className}">${escapeHtml(paragraph).replaceAll('\n','<br>')}</p>`;
  }).join('\n');
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Record Integrity | LinkedIn publishing kit</title><style>
*{box-sizing:border-box}body{margin:0;background:#eef2f7;color:#172a42;font:17px/1.65 'Segoe UI',Arial,sans-serif}main{max-width:1120px;margin:48px auto;padding:0 24px}.intro{max-width:730px;margin-bottom:28px}h1{font-size:34px;line-height:1.2;margin:8px 0 16px}h2{font-size:19px;margin:0 0 14px}.eyebrow,.section-label{font-weight:700;font-size:12px;letter-spacing:.1em;color:#215acc}.grid{display:grid;grid-template-columns:minmax(0,680px) 290px;gap:28px;align-items:start}.card{background:white;border:1px solid #dbe2ec;border-radius:14px;padding:32px;box-shadow:0 6px 24px #172a4209}.post p{margin:0 0 23px}.post .hook{font-size:21px;font-weight:600;line-height:1.5}.post .section-label{margin-top:32px;margin-bottom:14px}.post .point{padding-left:16px;border-left:3px solid #dbe7fa}.post p:last-child{margin-bottom:0}button,.download{display:block;width:100%;border-radius:8px;padding:12px 16px;font:600 15px 'Segoe UI',Arial,sans-serif;text-align:center;text-decoration:none;cursor:pointer}button{background:#215acc;border:0;color:white}.download{border:1px solid #cdd8e6;color:#215acc;margin-top:12px;background:white}aside{position:sticky;top:24px}aside p,.note{font-size:14px;color:#52647b}.cover{width:100%;height:auto;display:block;border-radius:8px;margin-bottom:16px}.status{min-height:48px}textarea{width:100%;min-height:340px;font:15px/1.6 monospace;margin-top:20px}a{color:#215acc}@media(max-width:850px){.grid{grid-template-columns:1fr}aside{position:static;grid-row:1}.card{padding:24px}main{margin:24px auto;padding:0 16px}h1{font-size:28px}}
</style></head><body><main><div class="intro"><div class="eyebrow">RECORD INTEGRITY / PUBLISHING KIT</div><h1>Your post, ready to copy.</h1><p>Short paragraphs. Four clear points. One useful question.</p><p class="note">Copy the post text below. Blank lines and line breaks are included. LinkedIn controls its own font and line spacing; the preview's colors, borders and typography are not pasted.</p></div><div class="grid"><article id="post-preview" class="card post" aria-label="LinkedIn post preview">${paragraphs}</article><aside class="card"><h2>1. Copy the text</h2><button id="copy-post" type="button">Copy post with line breaks</button><p id="copy-status" class="status" role="status" aria-live="polite">Only the post text is copied, not these instructions.</p><textarea id="copy-source" aria-label="Post text for manual copying" readonly hidden>${escapeHtml(post)}</textarea><h2>2. Add the cover</h2><img class="cover" src="assets/record-integrity-cover.png" alt="Conceptual cover: an altered database instruction stops at a verification boundary before the protected payment path. Headline: A database edit should not approve a payment."><a class="download" href="assets/record-integrity-cover.png" download>Download cover image</a><h2 style="margin-top:28px">3. Share the article</h2><a class="download" href="article.pdf" download>Download article PDF</a><a class="download" href="linkedin-post.txt" download>Download plain text</a><p>Attach the PDF to a document post, or use the image with a public article link. Pick the mode your composer offers; do not assume it will accept both attachments together.</p><p><a href="publishing-guide.md">Full publishing instructions</a></p></aside></div></main><script>
document.getElementById('copy-post').onclick=async()=>{
 const source=document.getElementById('copy-source'),status=document.getElementById('copy-status');
 try{await navigator.clipboard.writeText(source.value);status.textContent='Copied with blank lines. Paste into your LinkedIn post.';}
 catch{source.hidden=false;source.focus();source.select();status.textContent='Text selected below. Press Ctrl+C (or Cmd+C), then paste into LinkedIn.';}
};
</script></body></html>`;
}

export function mediumMarkdown(article){
  if(!article.includes(disclaimer))throw new Error('The canonical article must contain the employer disclaimer');
  let result=article.replace(/^# .+$/m,'# '+mediumTitle);
  result=result.replace(/\]\(([^)]+)\)/g,(match,href)=>{
    if(/^(?:[a-z][a-z\d+.-]*:|#)/i.test(href))return match;
    const target=path.posix.normalize(path.posix.join('docs/article',href));
    if(target.startsWith('../')||href.startsWith('/'))throw new Error('Reference escapes the repository');
    return `](https://github.com/ViktorKhudiaiev/demo_security_module/blob/${referenceRevision}/${target})`;
  });
  // Medium has no native Markdown table workflow; preserve every cell as text.
  result=result.replace(/(?:^\|.+\|\r?\n)+/gm,block=>{
    const rows=block.trim().split(/\r?\n/).map(row=>row.slice(1,-1).split('|').map(cell=>cell.trim()));
    if(rows.length<3||!rows[1].every(cell=>/^:?-+:?$/.test(cell)))throw new Error('Unsupported article table');
    const [headers,,...values]=rows;
    return values.map(row=>{
      if(row.length!==headers.length)throw new Error('Article table column mismatch');
      return `**${headers[0]}: ${row[0]}**\n\n`+row.slice(1).map((cell,index)=>`- **${headers[index+1]}:** ${cell}`).join('\n');
    }).join('\n\n')+'\n';
  });
  return result;
}

export async function buildPublicationCopies(root,marked,style){
  const article=await fs.readFile(path.join(root,'docs/article/article.md'),'utf8');
  const markdown=mediumMarkdown(article);
  await fs.writeFile(path.join(root,'docs/article/medium-article.md'),markdown);
  const html=`<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${mediumTitle}</title><style>${style}</style></head><body><main>${marked.parse(markdown,{gfm:true})}</main></body></html>`;
  await fs.writeFile(path.join(root,'docs/article/medium-article.html'),html);
  const post=await fs.readFile(path.join(root,'docs/article/linkedin-post.txt'),'utf8');
  await fs.writeFile(path.join(root,'docs/article/linkedin-post.html'),linkedinPreview(post));
  console.log('Built Medium article copies from the canonical article');
}
