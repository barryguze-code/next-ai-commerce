const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const source=fs.readFileSync('src/main/resources/static/js/table-data-tools.js','utf8');
const context={document:{body:{}},getComputedStyle:()=>({overflowY:'visible'}),window:{scrollBy:(x,y)=>context.delta=y}};
vm.createContext(context);
vm.runInContext(source.slice(source.indexOf('function pageSizeAnchor('),source.indexOf('// One pager presentation')),context);
const row=(top,bottom,hidden=false)=>({getClientRects:()=>[{}],classList:{contains:()=>hidden},getBoundingClientRect:()=>({top,bottom}),parentElement:context.document.body});
test('anchors the first visible row, ignoring paginated rows',()=>{
 const elements=[row(-200,-100),row(-100,40),row(40,180),row(0,100,true)];
 const anchor=context.pageSizeAnchor(elements);
 assert.equal(anchor.index,1);assert.equal(anchor.top,-100);
});
test('keeps the same record when moving from 25 to 50 and back',()=>{
 const absolute=(4-1)*25+12;
 assert.equal(Math.floor(absolute/50)+1,2);assert.equal(absolute%50,37);
 assert.equal(Math.floor(absolute/25)+1,4);assert.equal(absolute%25,12);
});
test('restores viewport offset instead of jumping to the top',()=>{
 context.restoreRowOffset(row(700,800),50);assert.equal(context.delta,650);
});
test('restores a scrolling content panel without moving the sidebar',()=>{
 const panel={scrollHeight:2000,clientHeight:600,scrollTop:100,parentElement:context.document.body};
 context.getComputedStyle=()=>({overflowY:'auto'});
 const element=row(300,400);element.parentElement=panel;
 context.restoreRowOffset(element,80);assert.equal(panel.scrollTop,320);
});
