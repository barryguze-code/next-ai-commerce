const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync('src/main/resources/static/js/stock-entry-points.js','utf8');
const block=source.slice(source.indexOf('const cells=new Map'),source.indexOf('table.tBodies[0].append(row)'));
for(const reordered of [false,true])test(`Zero-stock rows retain matching cells (${reordered?'custom':'default'} columns)`,()=>{
  const ids=reordered?['record-context','unit-cost','product','reserved','location','on-hand','physical-available','expiration','shelf-life']:['record-context','product','location','on-hand','reserved','physical-available','expiration','shelf-life','unit-cost'];
  const element=()=>({dataset:{},style:{},textContent:''});
  const cell=element(),overview=element(),children=[];
  const headings=ids.map(id=>({dataset:{column:id},style:{},hidden:id==='reserved'}));
  vm.runInNewContext(block,{cell,overview,document:{createElement:element},table:{querySelectorAll:()=>headings},row:{append:td=>children.push(td)}});
  assert.equal(children.length,9);
  assert.deepEqual(children.map(td=>td.dataset.column),ids);
  assert.equal(children[ids.indexOf('product')],cell);
  assert.equal(children[0],overview);
  assert.equal(children[ids.indexOf('unit-cost')].textContent,'Not valued');
  assert.equal(children[ids.indexOf('reserved')].hidden,true);
});
