const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const script=fs.readFileSync('src/main/resources/static/js/packing-slip-workspace.js','utf8');

for(const origin of ['http://localhost:8080','https://app.nextaicommerce.com']){
  test('blocked-tab help shows the current origin: '+origin,()=>{
    const nodes=[{},{}],help={hidden:true,querySelectorAll:()=>nodes};
    const status={textContent:'Package saved'};
    const document={documentElement:{dataset:{}},querySelector:selector=>selector==='#packing-popup-help'?help:selector==='#packing-status'?status:null};
    vm.runInNewContext(script,{document,location:{origin,search:'?sellerCentralBlocked=true'},URLSearchParams,localStorage:{getItem:()=>null}});
    assert.equal(help.hidden,false);
    assert.deepEqual(nodes.map(node=>node.textContent),[origin,origin]);
    assert.equal(status.textContent,'Package saved');
  });
}
test('help stays hidden without the blocked-tab flag',()=>{
  const help={hidden:true};
  const document={documentElement:{dataset:{}},querySelector:selector=>selector==='#packing-popup-help'?help:null};
  vm.runInNewContext(script,{document,location:{origin:'http://localhost:8080',search:''},URLSearchParams,localStorage:{getItem:()=>null}});
  assert.equal(help.hidden,true);
});
