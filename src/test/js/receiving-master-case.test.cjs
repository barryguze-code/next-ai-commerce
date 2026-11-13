const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm');
test('case presentation uses current catalogue pack without changing receipt pack',()=>{
 const source=fs.readFileSync('src/main/resources/static/js/receiving-work.js','utf8');
 const start=source.indexOf('const quantity='),end=source.indexOf('const doc=',start);
 const state={details:[{id:'product',catalogPack:6}]};
 const quantity=vm.runInNewContext(source.slice(start,end)+'quantity',{state,number:String});
 const line={id:'product',unitsPerCase:1};
 assert.equal(quantity(120,line),'20 cases · 120 each');
 state.details[0].catalogPack=4;
 assert.equal(quantity(120,line),'30 cases · 120 each');
 assert.equal(line.unitsPerCase,1);
});
