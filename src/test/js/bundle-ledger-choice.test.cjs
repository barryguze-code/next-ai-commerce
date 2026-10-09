const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm'),fs=require('node:fs');
function setup(items){
 const dialogs=[],requests=[],opened=[];
 class Element {
  constructor(){this.dataset={};this.children=[];this.events={};this.nodes={};this.isConnected=true;}
  setAttribute(){} removeAttribute(){} before(){} after(){} remove(){} focus(){} dispatchEvent(){}
  append(...children){this.children.push(...children);} add(child){this.append(child);} replaceChildren(){this.children=[];}
  addEventListener(name,handler){this.events[name]=handler;}
  querySelector(selector){return this.nodes[selector]??=new Element();}
  querySelectorAll(){return [];}
  closest(){return null;}
  showModal(){dialogs.push(this);} close(){this.events.close?.();}
 }
 const nodes={};const document={body:new Element(),getElementById:id=>nodes[id]??=new Element(),createElement:()=>new Element(),addEventListener(){},querySelector(){return null;},querySelectorAll(){return [];}};
 const window={openInventoryHistory:row=>opened.push(row.dataset),NextAiShortDates:{display:x=>x}};
 const context={document,window,URL,AbortController,Event,Option:class{constructor(text,value){this.text=text;this.value=value;}},fetch:async url=>{requests.push(url);return {ok:true,json:async()=>url.includes('/components?')?items:{positions:[{item:url.split('/')[3],locationCode:'F11'}],locations:[]}};}};
 vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/replenishment-inventory-history.js','utf8'),context);
 const link=new Element();link.href='http://localhost/app/marketplace-skus/mappings/ledger?sku=BUNDLE&code=899229';
 return {window,link,dialogs,requests,opened};
}
const items=[{itemId:'A',vendorItemCode:'899229',productName:'First',quantity:2},{itemId:'B',vendorItemCode:'372613',productName:'Second',quantity:3}];
const tick=()=>new Promise(resolve=>setImmediate(resolve));
test('bundle title waits for explicit choice and loads only the selected item',async()=>{
 const s=setup(items);const pending=s.window.openMappedInventoryLedger(s.link,true);await tick();
 assert.equal(s.dialogs.length,1);assert.equal(s.requests.length,1);assert.equal(s.opened.length,0);
 s.dialogs[0].querySelector('.bundle-ledger-options').children[1].onclick();await pending;
 assert.equal(s.requests[1],'/app/inventory/B/history-panel');assert.equal(s.opened.length,1);
});
test('cancel opens no inventory ledger',async()=>{const s=setup(items);const pending=s.window.openMappedInventoryLedger(s.link,true);await tick();s.dialogs[0].close();await pending;assert.equal(s.requests.length,1);assert.equal(s.opened.length,0);});
test('specific code bypasses chooser',async()=>{const s=setup(items);await s.window.openMappedInventoryLedger(s.link);assert.equal(s.dialogs.length,0);assert.equal(s.requests[1],'/app/inventory/A/history-panel');});
test('single mapped item bypasses chooser even for title clicks',async()=>{const s=setup(items.slice(0,1));await s.window.openMappedInventoryLedger(s.link,true);assert.equal(s.dialogs.length,0);assert.equal(s.opened.length,1);});
