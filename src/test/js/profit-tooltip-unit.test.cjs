const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync('src/main/resources/static/js/profit.js','utf8');
function fixture(){
 const events={},nodes=[],views=new Map();
 const el=(tag,text,cls)=>({tag,textContent:text,className:cls,style:{},children:[],attrs:{},hidden:false,
  append(...items){this.children.push(...items)},replaceChildren(...items){this.children=items},setAttribute(k,v){this.attrs[k]=v},removeAttribute(k){delete this.attrs[k]},
  addEventListener(){},contains(n){return n===this},getBoundingClientRect(){return {left:950,top:660,bottom:680,width:238,height:170}}});
 const context=vm.createContext({el,views,innerWidth:1000,innerHeight:700,clearTimeout,setTimeout,money:n=>n==null?'Pending':'$'+Number(n).toFixed(2),document:{body:{append(n){nodes.push(n)}},addEventListener(k,fn){events[k]=fn}},window:{addEventListener(){}}});
 vm.runInContext(source.slice(0,source.indexOf('(() => {'))+source.slice(source.indexOf(' const feeTip='),source.indexOf(' function enhancePrices(')),context);
 const button=el('button');button.dataset={profitSku:'A'};button.querySelector=()=>({});views.set(button,{kind:'ORDER',lines:[{sku:'A',quantity:1,productCost:4.82,unitPrice:25,otherCost:1}],packages:[{amount:9.99}],totals:{shippingCost:9.99}});context.button=button;
 return {context,button,tip:nodes[0],events,views};
}
test('fee tooltip uses cached amounts, stays within viewport and supports Escape',()=>{
 const f=fixture();vm.runInContext('showFees(button)',f.context);
 assert.equal(f.tip.hidden,false);assert.equal(f.button.attrs['aria-describedby'],'profit-fee-tooltip');
 assert.deepEqual(f.tip.children[1].children.map(n=>n.textContent),['Product cost','$4.82','Referral fee','$3.75','Other cost','$1.00','Shipping','$9.99']);
 assert.equal(f.tip.style.left,'754px');assert.equal(f.tip.style.top,'482px');
 f.events.keydown({key:'Escape'});assert.equal(f.tip.hidden,true);assert.equal(f.button.attrs['aria-describedby'],undefined);
});
test('missing costs stay Pending and links without calculators do not show a tooltip',()=>{
 const f=fixture();f.views.set(f.button,{kind:'SKU',totals:{otherCost:1}});vm.runInContext('showFees(button)',f.context);
 assert.deepEqual(f.tip.children[1].children.filter(n=>n.tag==='dd').map(n=>n.textContent),['Pending','Pending','$1.00','Pending']);
 vm.runInContext('hideFees()',f.context);f.button.querySelector=()=>null;vm.runInContext('showFees(button)',f.context);assert.equal(f.tip.hidden,true);
});
