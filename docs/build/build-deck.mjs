// Targeted edits retain the editable source deck's masters, tables and geometry.
import fs from 'node:fs/promises';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
import {createRequire} from 'node:module';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const runtimeModules = process.env.RUNTIME_NODE_MODULES ?? 'C:/Users/vikto/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules';
const artifactPath = createRequire(path.join(runtimeModules, 'artifact-loader.cjs')).resolve('@oai/artifact-tool');
const {PresentationFile, FileBlob} = await import(pathToFileURL(artifactPath));
const root = process.cwd();
const tmp = path.resolve(process.env.DECK_QA_DIRECTORY ?? path.join(root, '.local/artifact-qa/presentation-notifications-2026-09-08/build-1'));
const out = path.resolve(process.env.DECK_OUTPUT_DIRECTORY ?? path.join(root, 'docs/presentation'));
const skill = process.env.PRESENTATIONS_SKILL_DIR ?? 'C:/Users/vikto/.codex/plugins/cache/openai-primary-runtime/presentations/26.905.11957/skills/presentations';
const python = process.env.RUNTIME_PYTHON ?? 'C:/Users/vikto/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe';
process.env.RUNTIME_NODE_MODULES = runtimeModules;
const {finalizePresentation, makeNativeBulletParagraphs} = await import(pathToFileURL(path.join(skill, 'container_tools/artifact_tool_utils.mjs')));
const source = path.resolve(process.env.DECK_EDIT_SOURCE ?? path.join(root, 'docs/presentation/demo.pptx'));
const outputName = process.env.DECK_OUTPUT_NAME ?? 'demo-notifications-2026-09-08.pptx';
assert.equal(path.basename(outputName), outputName, 'Output name must be a filename');
await fs.mkdir(tmp, {recursive:true});
await fs.mkdir(out, {recursive:true});
const reference = path.join(tmp, 'reference.pptx');
await fs.copyFile(source, reference);
const referenceSha256 = createHash('sha256').update(await fs.readFile(reference)).digest('hex');
const p = await PresentationFile.importPptx(await FileBlob.load(reference));
assert.equal(p.slides.items.length, 14, 'Expected the existing 14-slide template');
const inspected = await p.inspect({kind:'slide,textbox,shape,table,layout', maxChars:150000});
await fs.writeFile(path.join(tmp, 'before-inspect.ndjson'), inspected.ndjson);
const records = inspected.ndjson.split('\n').filter(Boolean).map(line => JSON.parse(line));
const family = 'Arial';
const fontPolicy = {basis:'reference', families:[family], referencePath:reference, referenceSha256};
const color = {blue:'#2463A6', red:'#B43138'};
function shape(slide, name) {
    const record = records.find(item => ['textbox','shape'].includes(item.kind) && item.slide === slide && item.name === name);
    assert(record, `Missing template shape ${slide}/${name}`);
    return p.resolve(record.id);
}
function replace(slide, name, value) {
    const target = shape(slide, name);
    // Full imported paragraphs require assignment. text.replace does not cross paragraph boundaries.
    const styles = {'text-2':[24,false,'#5E6A78'],'text-25':[24,false,color.red],
        'text-88':[24,false,'#5E6A78'],'text-91':[29,true,color.blue],'text-92':[22,false,'#5E6A78'],
        'text-93':[24,false,'#5E6A78'],'text-96':[23,false,'#5E6A78'],
        'text-111':[31,true,'#142D4E'],'text-112':[27,false,'#26364B'],'text-113':[25,true,color.blue]};
    const style = name.startsWith('title-') ? [44,true,'#142D4E'] : styles[name];
    assert(style, `No reference typography for ${name}`);
    target.text = value;
    target.text.style = {typeface:family,fontSize:style[0],bold:style[1],color:style[2],
        alignment:'left',verticalAlignment:'top',autoFit:'none',wrap:'square',
        insets:{left:0,right:0,top:0,bottom:0}};
    return target;
}
function updateTable(slide, values) {
    const record = records.find(item => item.kind === 'table' && item.slide === slide);
    assert(record && record.rows === values.length && record.cols === values[0].length, `Unexpected native table structure on slide ${slide}`);
    const table = p.resolve(record.id);
    values.forEach((row, r) => row.forEach((value, c) => {
        table.cells.set(r, c, value);
        table.getCell(r,c).text.style = {typeface:family,fontSize:slide===11?27:26,
            color:r===0?'#FFFFFF':'#26364B',bold:r===0,autoFit:'none',verticalAlignment:'middle',
            insets:{left:14,right:12,top:10,bottom:10}};
    }));
    return table;
}
function diagramNode(slide, name, value, x, width) {
    const existing = records.find(item => item.kind === 'textbox' && item.slide === 3 && item.name === name);
    if (existing) return p.resolve(existing.id);
    const node = slide.shapes.add({name, geometry:'rect', position:{left:x,top:590,width,height:59},
        fill:'#FFFFFF',line:{fill:color.blue,width:2}});
    node.text = value;
    node.text.style = {typeface:family,fontSize:22,bold:true,color:color.blue,
        alignment:'center',verticalAlignment:'middle',autoFit:'none',insets:{left:8,right:8,top:4,bottom:4}};
    return node;
}
replace(1, 'text-2', 'Viktor Khudiaiev\nTechnical demonstration, September 8, 2026');
// Add a native asynchronous reporting path below the existing architecture.
replace(3, 'text-25', '');
const architecture = p.slides.items[2];
const hadNotificationFlow = records.some(item => item.name === 'notification-capture');
const capture = diagramNode(architecture,'notification-capture','Audit DB: incident +\nnotification_outbox',65,340);
const dispatcher = diagramNode(architecture,'notification-dispatcher','Separate email dispatcher\nSMTP retries and rate cap',483,340);
const inbox = diagramNode(architecture,'notification-inbox','Mailpit local inbox\nSMTP by configuration',901,312);
if (!hadNotificationFlow) for (const [from,to] of [[capture,dispatcher],[dispatcher,inbox]]) {
    architecture.shapes.connect(from,to,{kind:'straight',fromSide:'right',toSide:'left',
        line:{fill:color.blue,width:2},tail:{type:'triangle',width:'sm',length:'sm'}});
}
// Current notification observations. The notes retain historical outage details.
replace(11,'title-11','Incident email notifications');
replace(11,'text-88','Audit/Protected commits incident evidence and notification_outbox together');
updateTable(11,[
    ['Observed scenario','Protected result','Local email observation'],
    ['Fabricated\nPrimary record','Quarantined\nNo financial effect','One captured alert\nMinimal incident metadata'],
    ['Tampering after\nsettlement','Original completed result\nremains unchanged','One captured alert\nSeparate human review']
]);
replace(11,'text-91','A separate dispatcher retries SMTP with a rate cap.\nAt-least-once delivery. Email never gates settlement.');
replace(11,'text-92','Mailpit captures locally. Gmail delivery requires configured authenticated SMTP.');
// Preserve the failed acceptance result without rounding it into a pass.
replace(12,'title-12','Measured workload and current checks');
replace(12,'text-93','Latest load: September 7. Offered 20 TPS for 120 seconds, before notifications.');
const measurements = updateTable(12,[
    ['Run or measure','Observed result'],
    ['September 7 steady completed rate','19.963636 TPS'],
    ['Strict 20 TPS gate, zero tolerance','FAILED'],
    ['September 7 completed / failed operations','2,400 / 0'],
    ['September 7 completion latency, p95 / p99','668 ms / 791 ms'],
    ['September 6 initial run: 83 Java tests','20.3091 TPS, passed'],
    ['September 6 final run: 86 Java tests','20.0000 TPS, passed']
]);
measurements.getCell(2,1).text.style = {typeface:family,fontSize:26,color:color.red,bold:true,autoFit:'none',verticalAlignment:'middle',insets:{left:14,right:12,top:10,bottom:10}};
replace(12,'text-96','September 8: 137 Java tests, 44 Node checks, 18 role checks and 4 live cases passed.\nNo new load run. These checks establish neither email capacity nor a production SLA.');
replace(14,'text-111','Local demonstration cases');
const demoCases = replace(14,'text-112','');
demoCases.text = makeNativeBulletParagraphs(['Normal transfer and identical retry',
    'Fabricated row and captured alert','Tampering after settlement and review'],
    {marginLeftPoints:17,hangingPoints:9,spaceAfterPoints:13});
demoCases.text.style = {typeface:family,fontSize:27,color:'#26364B',autoFit:'none',verticalAlignment:'top',insets:{left:0,right:0,top:0,bottom:0}};
replace(14,'text-113','Evidence and code: github.com/ViktorKhudiaiev/demo_security_module\nCode snapshot e2e35de. Full immutable links accompany the speaker notes.').text.style = {typeface:family,fontSize:25,bold:true,color:color.blue,autoFit:'none',verticalAlignment:'top',insets:{left:0,right:0,top:0,bottom:0}};

const notes = await fs.readFile(path.join(root,'docs/presentation/notes.md'),'utf8');
const sections = [...notes.matchAll(/^## (\d+)\. ([^\n]+)\r?\n([\s\S]*?)(?=^## \d+\. |$(?![\s\S]))/gm)];
assert.equal(sections.length,14,'Presenter notes must cover all 14 slides');
sections.forEach((section,index)=>{
    assert.equal(Number(section[1]),index+1);
    p.slides.items[index].speakerNotes.textFrame.setText(section[3].trim());
});
await fs.writeFile(path.join(tmp,'slide-plan.json'),JSON.stringify(sections.map(section=>({number:Number(section[1]),title:section[2]})),null,2));
const candidate = path.join(tmp,'candidate.pptx');
await (await PresentationFile.exportPptx(p)).save(candidate);
for (let index=0;index<p.slides.items.length;index++) {
    const slide = p.slides.items[index];
    const preview = await p.export({slide,format:'png',scale:1});
    await fs.writeFile(path.join(tmp,`draft-${String(index+1).padStart(2,'0')}.png`),new Uint8Array(await preview.arrayBuffer()));
    const layout = await slide.export({format:'layout'});
    await fs.writeFile(path.join(tmp,`draft-${String(index+1).padStart(2,'0')}.layout.json`),await layout.text());
}
const finalPath = path.join(out,outputName);
const requiredTables = [2,5,6,9,11,12];
const result = await finalizePresentation({workspaceDir:root,candidatePath:candidate,finalPath,pythonExecutable:python,
    integrityValidatorPath:path.join(skill,'container_tools/inspect_presentation_package_integrity.py'),
    layoutValidatorPath:path.join(skill,'container_tools/inspect_presentation_layout_geometry.py'),
    layoutArgs:['--expected-slide-size-emu','12192000,6858000','--validate-bullet-geometry','--validate-heading-fit',
        ...requiredTables.flatMap(number=>['--require-native-table-slide',String(number)])],
    explicitTotalSlideCount:14,requiredNativeTableOwnerSlides:requiredTables,requiredNativeChartOwnerSlides:[],
    fontPolicy,verifyArtifactToolImport:true,receiptPath:path.join(tmp,process.env.DECK_RECEIPT_NAME??'final.validation.json')});
console.log(JSON.stringify(result));
const finalDeck = await PresentationFile.importPptx(await FileBlob.load(finalPath));
const finalSnapshot = (await finalDeck.inspect({kind:'slide,textbox,shape,table,chart,notes,layout',maxChars:250000})).ndjson;
await fs.writeFile(path.join(tmp,'final-inspect.ndjson'),finalSnapshot);
const finalRecords = finalSnapshot.split('\n').filter(Boolean).map(line=>JSON.parse(line));
const visible = finalRecords.filter(item=>item.kind==='textbox').map(item=>item.text??'').join('\n');
for (const expected of ['September 8, 2026','notification_outbox','Incident email notifications',
    'At-least-once delivery. Email never gates settlement.','137 Java tests, 44 Node checks, 18 role checks',
    'No new load run.','Code snapshot e2e35de.']) assert(visible.includes(expected),`Missing visible text: ${expected}`);
assert(!visible.includes('Red identifies attacker-controlled Main.'),'Superseded architecture caption remains');
for (let index=0;index<finalDeck.slides.items.length;index++) {
    const preview = await finalDeck.export({slide:finalDeck.slides.items[index],format:'png',scale:1});
    await fs.writeFile(path.join(tmp,`final-${String(index+1).padStart(2,'0')}.png`),new Uint8Array(await preview.arrayBuffer()));
    console.log(`Rendered final ${index+1}`);
}
