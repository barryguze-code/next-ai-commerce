const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync('src/main/resources/static/js/personal-collaboration.js','utf8');
const fragment=source.slice(source.indexOf('    function filteredRows(){'),source.indexOf("    root.querySelector('#personal-download').onclick"));
function filtered(account,store,scope='ASSIGNED',type=''){
 const cards=[['account-wide','a',null,'CATALOG'],['selected-store','a','s1','MARKETPLACE_SKU'],['other-store','a','s2','ORDER'],['foreign-account','b',null,'CATALOG']];
 const context=vm.createContext({account:{value:account},store,scope,type:{value:type},page:{rows:cards.map(([id,account,store,subjectType],i)=>({position:i+1,card:{key:{id,type:'REVIEW'},account,subjectType,store}})),reviews:{},dates:{}}});
 return Array.from(vm.runInContext(fragment+';filteredRows()',context),r=>r.card.key.id);
}
for(const tab of ['ASSIGNED','ACTIVE','CLOSED'])test(`${tab}: selected store retains same-account module tasks without including another store/account`,()=>{
 assert.deepEqual(filtered('a','s1',tab).sort(),['account-wide','selected-store']);
 assert.deepEqual(filtered('a','s2',tab).sort(),['account-wide','other-store']);
});
test('account and broad selections retain their scope; type filters still apply',()=>{
 assert.deepEqual(filtered('a','').sort(),['account-wide','other-store','selected-store']);
 assert.equal(filtered('','').length,4);assert.deepEqual(filtered('a','s1','ASSIGNED','CATALOG'),['account-wide']);
});
test('returning to the queue refreshes immediately, coalesces events and preserves position',async()=>{
 const listeners={},docListeners={};let reads=0,restores=0,resolve;
 const context=vm.createContext({window:{addEventListener:(n,f)=>listeners[n]=f},document:{hidden:false,querySelector:()=>null,addEventListener:(n,f)=>docListeners[n]=f},busy:false,dragged:null,queueMicrotask,viewport:{capture:()=>({y:420}),restore:a=>{assert.equal(a.y,420);restores++;}},load:()=>{reads++;return new Promise(r=>resolve=r);}});
 const start=source.indexOf('    let refreshPending=false'),end=source.indexOf('    let searchTimer;',start);
 vm.runInContext(source.slice(start,end),context);
 listeners.focus();docListeners.visibilitychange();assert.equal(reads,1);resolve();await new Promise(setImmediate);assert.equal(restores,1);
 listeners.pageshow({persisted:true});assert.equal(reads,2);resolve();await new Promise(setImmediate);
 context.document.hidden=true;listeners.focus();assert.equal(reads,2);
 context.document.hidden=false;context.busy=true;listeners.focus();assert.equal(reads,2);
 context.busy=false;context.document.querySelector=()=>({});listeners.focus();assert.equal(reads,2);
});

test('a committed update during an in-flight refresh triggers a trailing read',async()=>{
 const listeners={};let reads=0,resolve;
 const context=vm.createContext({window:{addEventListener:(n,f)=>listeners[n]=f},document:{hidden:false,querySelector:()=>null,addEventListener:()=>{}},busy:false,dragged:null,queueMicrotask,viewport:{capture:()=>({}),restore:()=>{}},load:()=>{reads++;return new Promise(r=>resolve=r);}});
 vm.runInContext(source.slice(source.indexOf('    let refreshPending=false'),source.indexOf('    let searchTimer;')),context);
 listeners.focus();listeners['personal-collaboration-updated']({type:'personal-collaboration-updated'});assert.equal(reads,1);
 resolve();await new Promise(setImmediate);assert.equal(reads,2);resolve();await new Promise(setImmediate);
});

test('a dialog opened during a background read defers rendering',async()=>{
 let resolve,rendered=0;
 const context=vm.createContext({generation:0,search:{value:''},scope:'ASSIGNED',URLSearchParams,fetch:()=>new Promise(r=>resolve=r),busy:false,dragged:null,refreshQueued:false,document:{hidden:false,querySelector:()=>({})},render:()=>rendered++});
 vm.runInContext(source.slice(source.indexOf('    async function load('),source.indexOf('    type.onchange=')),context);
 const pending=vm.runInContext('load(true)',context);resolve({ok:true,json:async()=>({rows:[]})});assert.equal(await pending,false);assert.equal(rendered,0);assert.equal(context.refreshQueued,true);
});
test('mutation completion resumes a queued refresh after busy clears',()=>{
 for(const marker of ['finally {busy=false;','finally{busy=false;']){
  const start=source.indexOf(marker),body=source.slice(start+source.slice(start).indexOf('{')+1,source.indexOf('}',start));let resumed=0;
  const context=vm.createContext({busy:true,dragged:{},refreshQueued:true,clearInsertion:()=>{},refreshVisibleQueue:()=>{assert.equal(context.busy,false);resumed++;}});
  vm.runInContext(body,context);assert.equal(resumed,1);
 }
});
