const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const script=fs.readFileSync('src/main/resources/static/js/packing-slip-workspace.js','utf8');
const template=fs.readFileSync('src/main/resources/templates/packing-slip.html','utf8');
const anchor=template.match(/<a\b[^>]*\bid="veeqo-shortcut"[^>]*>/)[0];

function workspace(packageName,{clipboardBlocked=false}={}){
  const node=()=>({listeners:{},addEventListener(type,handler){this.listeners[type]=handler;}});
  const link={...node(),hidden:/\shidden(?:\s|>|=)/.test(anchor),style:{}};
  const names=['XSmall','Pak','UPS OMBox','Medium','Large','XLarge','__custom'];
  const select={...node(),options:names.map(value=>({value,textContent:value})),value:''};
  const custom={...node(),value:''},customWrap={hidden:true},status={textContent:''};
  const printed={textContent:packageName},copied=[];
  const nodes={'.packing-workspace':{dataset:{orderId:'order-fixture-42'}},'#packing-package':select,
    '#custom-package-field':customWrap,'#custom-package':custom,'#packing-status':status,
    '#veeqo-shortcut':link,'#print-slip':node(),'#save-package':node()};
  const document={documentElement:{dataset:{}},querySelector:s=>nodes[s]||null,
    querySelectorAll:s=>s==='[data-printed-package]'?[printed]:[]};
  vm.runInNewContext(script,{document,window:{},location:{search:''},URLSearchParams,
    localStorage:{getItem:()=>null},navigator:{clipboard:{writeText:async value=>{
      if(clipboardBlocked)throw Error('blocked');copied.push(value);
    }}}});
  return {link,select,custom,customWrap,status,printed,copied};
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
