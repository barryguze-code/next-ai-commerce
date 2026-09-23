const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const source=fs.readFileSync('src/main/resources/static/js/table-data-tools.js','utf8');
const match=vm.runInNewContext(source.slice(source.indexOf('function filterMatch('),source.indexOf('function init(root)'))+';filterMatch');
test('numeric ranges include boundaries and reject missing values',()=>{
 assert.equal(match('$1,250.50 Cost pending',{type:'number',min:'1250.5',max:'1250.5'}),true);
 assert.equal(match('0 each',{type:'number',min:'0',max:'0'}),true);
 assert.equal(match('Not valued',{type:'number',min:'0',max:''}),false);
 assert.equal(match('12 each',{type:'number',min:'13',max:''}),false);
});
test('calendar ranges include date boundaries',()=>{
 assert.equal(match('09/22/26 · 6:23 PM',{type:'date',min:'2026-09-22',max:'2026-09-22'}),true);
 assert.equal(match('09/21/26',{type:'date',min:'2026-09-22',max:''}),false);
 assert.equal(match('No expiration date',{type:'date',min:'2026-09-22',max:''}),false);
});
test('text matching remains case insensitive and clear removes restrictions',()=>{
 assert.equal(match('Cheese Gjetost','GJET'),true);
 assert.equal(match('Cheese Gjetost','yogurt'),false);
 assert.equal(match('Anything',''),true);
});
