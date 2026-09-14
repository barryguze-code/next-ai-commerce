const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../../main/resources/static/js/order-quick-actions.js'),'utf8');

function harness(writeText){
  let click,notice;
  const document={
    addEventListener:(type,handler)=>{if(type==='click')click=handler;},
    querySelector:()=>notice,
    createElement:()=>({dataset:{},setAttribute(){},classList:{add(){},remove(){}}}),
    body:{append:element=>notice=element}
  };
  vm.runInNewContext(source,{document,navigator:{clipboard:{writeText}},setTimeout:()=>1,clearTimeout(){}});
  return {copy:sku=>click({target:{closest:()=>({dataset:{copySku:sku}})}}),message:()=>notice.textContent};
}

test('copies full SKU and handles rows replaced by live refresh',async()=>{
  const copied=[];const ui=harness(async value=>copied.push(value));
  await ui.copy('IB-MKH-327842-XDRK-LILYS-4xEA');
  await ui.copy('NEWLY-SYNCED-SKU');
  assert.deepEqual(copied,['IB-MKH-327842-XDRK-LILYS-4xEA','NEWLY-SYNCED-SKU']);
  assert.equal(ui.message(),'SKU copied');
});

test('clipboard denial gives an honest error rather than a copied message',async()=>{
  const ui=harness(async()=>{throw new Error('Denied');});
  await ui.copy('SKU');
  assert.match(ui.message(),/Could not copy SKU/);
});
