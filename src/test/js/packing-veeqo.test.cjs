const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const script=fs.readFileSync('src/main/resources/static/js/package-name-editor.js','utf8')+'\n'+fs.readFileSync('src/main/resources/static/js/packing-slip-workspace.js','utf8');
const template=fs.readFileSync('src/main/resources/templates/packing-slip.html','utf8');
const anchor=template.match(/<a\b[^>]*\bid="veeqo-shortcut"[^>]*>/)[0];

function workspace(packageName,{clipboardBlocked=false,withLocations=false}={}){
  const node=()=>({listeners:{},addEventListener(type,handler){this.listeners[type]=handler;}});
  const link={...node(),hidden:/\shidden(?:\s|>|=)/.test(anchor),style:{}};
  const names=['XSmall','Pak','UPS OMBox','Medium','Large','XLarge','__custom'];
  const select={...node(),options:names.map(value=>({value,textContent:value})),value:''};
  const custom={...node(),value:''},customWrap={hidden:true},status={textContent:''};
  const printed={textContent:packageName},copied=[],prints=[];custom.focus=()=>{};
  const nodes={'.packing-workspace':{dataset:{orderId:'order-fixture-42'}},'#packing-package':select,
    '#custom-package-field':customWrap,'#custom-package':custom,'#packing-status':status,
    '#veeqo-shortcut':link,'#print-slip':node(),'#save-package':node()};
  const locationChoice={...node(),selectedOptions:[{dataset:{locationCode:'MAIN'}}]};
  const makeRow=(item,reserved,text)=>{const label={textContent:text};return {dataset:{accountItemId:item,reservedLocation:String(reserved)},querySelector:()=>label};};
  const locationRows=withLocations?[makeRow('item-1',false,'MAIN'),makeRow('item-1',true,'F2'),makeRow('item-2',false,'FX')]:[];
  const forms=withLocations?[{...node(),dataset:{accountItemId:'item-1'},elements:{locationId:locationChoice}}]:[];
  const document={documentElement:{dataset:{}},querySelector:s=>nodes[s]||null,
    querySelectorAll:s=>s==='[data-printed-package]'?[printed]:s==='.packing-slip article'?locationRows:s==='.packing-product-form'?forms:s==='.packing-product-form select[name=locationId]'&&withLocations?[locationChoice]:[]};
  vm.runInNewContext(script,{document,window:{print:()=>prints.push(printed.textContent),addEventListener(){}},location:{search:''},URLSearchParams,
    localStorage:{getItem:()=>null},navigator:{clipboard:{writeText:async value=>{
      if(clipboardBlocked)throw Error('blocked');copied.push(value);
    }}}});
  return {link,select,custom,customWrap,status,printed,copied,prints,nodes,locationChoice,locationRows};
}

test('Veeqo is a visible native external link before JavaScript, even without its icon',()=>{
  assert.doesNotMatch(anchor,/\shidden(?:\s|>|=)|\sstyle=/);
  assert.match(anchor,/href="https:\/\/app\.veeqo\.com\/"/);
  assert.match(anchor,/target="_blank"/);
  assert.match(anchor,/rel="noopener noreferrer"/);
  assert.match(template, /onerror="this\.hidden=true">Open in Veeqo/);
});

for(const name of ['XSmall','Pak','UPS OMBox','Medium','Large','XLarge','Cold box']){
  test('Veeqo stays reachable for package '+name+' and after package changes',()=>{
    const w=workspace(name);
    assert.equal(w.link.hidden,false);
    assert.notEqual(w.link.style.display,'none');
    w.select.value='Pak';w.select.listeners.change();
    assert.equal(w.printed.textContent,'Pak');
    assert.equal(w.customWrap.hidden,true);
    assert.equal(w.link.hidden,false);
    w.select.value='__custom';w.custom.value='Customer box';w.custom.listeners.input();
    assert.equal(w.printed.textContent,'Customer box');
    assert.equal(w.customWrap.hidden,false);
    assert.equal(w.link.hidden,false);
    assert.notEqual(w.link.style.display,'none');
  });
}

test('Veeqo click still copies this order ID without cancelling native navigation',async()=>{
  const w=workspace('Pak');
  await w.link.listeners.click({preventDefault(){assert.fail('native navigation must remain intact');}});
  assert.deepEqual(w.copied,['order-fixture-42']);
  assert.equal(w.status.textContent,'Order ID copied — ready to paste into Veeqo.');
});

test('blocked clipboard access keeps Veeqo reachable and shows the order ID',async()=>{
  const w=workspace('Pak',{clipboardBlocked:true});
  await w.link.listeners.click();
  assert.equal(w.link.hidden,false);
  assert.equal(w.status.textContent,'Clipboard access was blocked. Order ID: order-fixture-42');
});

 test('print reads the current field even without an input event, and never saves implicitly',()=>{
  const w=workspace('Pak');w.select.value='__custom';w.custom.value='  Cold   box ';
  w.nodes['#print-slip'].listeners.click();assert.deepEqual(w.prints,['Cold box']);
  w.custom.value='';w.nodes['#print-slip'].listeners.click();assert.equal(w.prints.length,1);
  assert.equal(w.status.textContent,'Write a package name first.');
 });

test('selected print location updates matching reserved and unreserved rows without moving stock',()=>{
 const w=workspace('Pak',{withLocations:true});
 w.locationChoice.selectedOptions=[{dataset:{locationCode:'F13'}}];w.locationChoice.listeners.change();
 assert.deepEqual(w.locationRows.map(r=>r.querySelector().textContent),['F13','F13','FX']);
 w.locationChoice.selectedOptions=[{dataset:{locationCode:'R1'}}];w.nodes['#print-slip'].listeners.click();
 assert.equal(w.locationRows[0].querySelector().textContent,'R1');
 assert.equal(w.locationRows[1].querySelector().textContent,'R1');
 w.locationChoice.selectedOptions=[{dataset:{}}];w.locationChoice.listeners.change();
 assert.equal(w.locationRows[0].querySelector().textContent,'MAIN');
});
