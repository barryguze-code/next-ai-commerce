const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync('src/main/resources/static/js/shipping-label-print.js','utf8');
function setup(pdf=true){
 class Node{constructor(tag){this.tag=tag;this.children=[];this.events={};this.attrs={};}append(...n){this.children.push(...n);}replaceChildren(...n){this.children=n;}setAttribute(k,v){this.attrs[k]=v;}addEventListener(k,v){this.events[k]=v;}}
 let prints=0,requests=0,revoked=0;const timers=new Map();let timer=0;const doc={head:new Node('head'),body:new Node('body'),createElement(tag){const n=new Node(tag);if(tag==='iframe')n.contentWindow={focus(){},print(){prints++;}};return n;}};
 const preview={document:doc,focus(){},events:{},addEventListener(k,v){this.events[k]=v;}};
 const url='/app/orders/order/buy-shipping/fedex/labels/00000000-0000-0000-0000-000000000001';
 const URLClass=class extends URL{static createObjectURL(){return 'blob:private-label';}static revokeObjectURL(){revoked++;}};
 const window={location:{origin:'http://localhost:8080'}};
 vm.runInNewContext(source,{window,URL:URLClass,setTimeout:fn=>{timers.set(++timer,fn);return timer;},clearTimeout:id=>timers.delete(id),fetch:async()=>{requests++;return {ok:pdf,headers:{get:()=>pdf?'application/pdf':'text/html'},blob:async()=>({})};}});
 return {window,preview,doc,url,prints:()=>prints,requests:()=>requests,revoked:()=>revoked,ready:()=>{for(const fn of [...timers.values()])fn();}};
}
test('only archived PDF is fetched; ready event opens native print once and manual reprint never purchases',async()=>{
 const x=setup();await x.window.prepareShippingLabelPrint(x.preview,x.url);assert.equal(x.requests(),1);assert.equal(x.prints(),0);const [header,frame]=x.doc.body.children;assert.equal(frame.src,'blob:private-label');assert.equal(header.children[0].textContent,'Print label');frame.events.load();frame.events.load();assert.equal(x.prints(),0);x.ready();assert.equal(x.prints(),1);header.children[0].onclick();assert.equal(x.prints(),2);assert.equal(x.requests(),1);x.preview.events.pagehide();assert.equal(x.revoked(),1);
});
test('failed or sign-in PDF response never auto-prints or retries a purchase',async()=>{
 const x=setup(false);await assert.rejects(x.window.prepareShippingLabelPrint(x.preview,x.url),/could not be loaded/);assert.equal(x.prints(),0);assert.equal(x.requests(),1);assert.match(x.doc.body.children[0].textContent,/saved label/);
});
test('cross-origin and unrelated URLs cannot supply a print document',async()=>{
 const x=setup();await assert.rejects(x.window.prepareShippingLabelPrint(x.preview,'https://example.org'+x.url),/Invalid archived label/);assert.equal(x.requests(),0);assert.equal(x.prints(),0);
});

test('manual Print during PDF initialization cancels deferred auto-print',async()=>{const x=setup();await x.window.prepareShippingLabelPrint(x.preview,x.url);const [header,frame]=x.doc.body.children;frame.events.load();header.children[0].onclick();x.ready();assert.equal(x.prints(),1);});
