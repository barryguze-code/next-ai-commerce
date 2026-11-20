const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync('src/main/resources/static/js/profit.js','utf8');
for(const kind of ['ORDER','SHOPIFY_ORDER','SKU'])for(const failure of ['missing','error'])test(`${kind} ${failure} keeps pending calculator and scope`,async()=>{
 const b={dataset:{profitKind:kind,profitKey:'key'},hasAttribute:()=>false,querySelector:()=>kind==='ORDER'?{textContent:'Est. order'}:null,classList:{add(){}},replaceChildren(...c){this.children=c;}};
 const c=vm.createContext({base:'/profit',query:()=>'',views:new WeakMap(),json:async()=>{if(failure==='error')throw Error('offline');return {};},calculator:()=>({tag:'img',className:'profit-calculator-art'}),el:(tag,text)=>({tag,text})});
 vm.runInContext(source.slice(source.indexOf(' function pending('),source.indexOf(' // One shared, cached fee tooltip')),c);await c.hydrate([b]);
 assert.equal(b.children[0].text,'Pending');assert.equal(b.children[1].text,kind==='ORDER'?'Est. order':kind==='SHOPIFY_ORDER'?'Est. item':'Est. / SKU');assert.equal(b.children[2].className,'profit-calculator-art');
});
