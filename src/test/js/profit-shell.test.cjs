const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const html=fs.readFileSync('src/main/resources/templates/fragments/profit.html','utf8');
test('profit dialog reuses its domain shell even with the CSRF input present',()=>{
 const source=fs.readFileSync('src/main/resources/static/js/ux-components.js','utf8');
 const markup=html.slice(html.indexOf('<dialog'),html.indexOf('</dialog>')+9),stack=[],children=[];
 for(const match of markup.matchAll(/<(\/?)([a-z][\w-]*)\b[^>]*>/g)){
  const [,closing,tag]=match;if(closing){stack.pop();continue;}
  if(stack.length===1)children.push({matches:s=>s.split(',').includes(tag)});
  if(!['input','img','link','br','meta'].includes(tag))stack.push(tag);
 }
 const dialog={children,firstElementChild:children[0]};
 const expression=source.match(/let shell=(.*?);/)[1];
 assert.equal(vm.runInNewContext(expression,{dialog}),children[0],'shared enhancer must use the existing shell, not add an outer header/footer');
});
test('three shipping packages sum into the existing revenue preview calculation',()=>{
 const source=fs.readFileSync('src/main/resources/static/js/profit.js','utf8'),nodes=[];
 const row={querySelector:s=>({'.profit-price':100,'.profit-other':1}[s])};
 const totals={replaceChildren(){nodes.length=0},append(...items){nodes.push(...items)}};
 const context={current:{kind:'SKU',currency:'USD',lines:[{unitPrice:100,quantity:1,productCost:20}],totals:{}},trigger:null,
  field:s=>s==='[data-profit-lines]'?{children:[row]}:s==='[data-profit-packages]'?{children:[10,12,3].map(n=>({querySelector:()=>n}))}:totals,
  productValue:()=>20,numeric:n=>n,money:n=>n,el:(tag,value)=>({tag,value,classList:{add(){}}})};
 vm.runInNewContext(source.slice(source.indexOf(' function calculate()'),source.indexOf(' async function loadDefaults('))+';calculate()',context);
 assert.equal(nodes[nodes.findIndex(n=>n.value==='Shipping')+1].value,25);
 assert.equal(nodes[nodes.findIndex(n=>n.value==='Estimated profit')+1].value,39);
});
test('Save & Close persists every package and keeps the modal open on failure',async()=>{
 const source=fs.readFileSync('src/main/resources/static/js/profit.js','utf8');
 for(const fail of [false,true]){
  let save,closed=false,payload;const button={addEventListener:(event,fn)=>save=fn},feedback={};
  const packages=[10,12,3].map(amount=>({querySelector:s=>s==='select'?{value:'FEDEX_SMALL'}:amount}));
  const context={trigger:{dataset:{}},current:{kind:'SKU',key:'SKU',packageType:'FEDEX_SMALL'},labels:{FEDEX_SMALL:'Small'},
   field:s=>s==='[data-profit-save]'?button:s==='[data-profit-feedback]'?feedback:s==='[data-profit-packages]'?{children:packages}:s==='[data-profit-packages] select'?{value:'FEDEX_SMALL'}:s==='.profit-other'?1:{children:[]},
   numeric:n=>n,post:async(path,data)=>{payload=data;if(fail)throw Error('Save failed');},refreshVisible:async()=>{},dialog:{close(){closed=true}}};
  vm.runInNewContext(source.slice(source.indexOf(" field('[data-profit-save]')?.addEventListener"),source.indexOf(" const form=field('[data-profit-rate-form]')")),context);
  await save();assert.deepEqual(Array.from(payload.packages,p=>p.amount),[10,12,3]);assert.equal(closed,!fail);assert.equal(button.disabled,false);
  if(fail)assert.equal(feedback.textContent,'Save failed');
 }
});
