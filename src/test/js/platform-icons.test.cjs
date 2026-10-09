const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync('src/main/resources/static/js/platform-icons.js','utf8');
function fixture(){
  let observer,scans=0;
  const document={documentElement:{},querySelectorAll(){scans++;return []}};
  const window={};
  vm.runInNewContext(source,{window,document,MutationObserver:class{
    constructor(callback){observer=callback}observe(){}
  }});
  return {api:window.NextAiIcons,notify:records=>observer(records),scans:()=>scans};
}
test('shared icons use one versioned URL including channel artwork',()=>{
  const f=fixture();
  assert.equal(f.api.source('mapping'),f.api.source('sku-mapped'));
  assert.equal(f.api.source('amazon'),'/images/platform/table/amazon.com-logo.png?v=20261008-badges');
});
test('content mutations scan only added connected subtrees, not the document',()=>{
  const f=fixture();let scans=0;
  const root={nodeType:1,isConnected:true,parentElement:null,querySelectorAll(){scans++;return []}};
  const child={...root,parentElement:root};
  f.notify([{addedNodes:[root,child,{nodeType:3}]}]);
  assert.equal(scans,1);assert.equal(f.scans(),1);
  f.notify([{addedNodes:[{...root,isConnected:false}]}]);
  assert.equal(scans,1);
});
test('unchanged image URLs are not rewritten',()=>{
  const f=fixture();let writes=0;
  const image={dataset:{platformIcon:'mapping'},matches:()=>true,querySelectorAll:()=>[],
    getAttribute:()=>f.api.source('mapping'),setAttribute:()=>writes++};
  f.api.refresh(image);assert.equal(writes,0);
});
