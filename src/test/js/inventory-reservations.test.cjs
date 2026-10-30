const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
async function render(row){
 const nodes=new Map();const node=id=>nodes.get(id)||nodes.set(id,{textContent:'',innerHTML:'',classList:{toggle(){}}}).get(id);
 const dialog={querySelector:node,querySelectorAll:()=>[],addEventListener(){},showModal(){},close(){}};
 const document={createElement:()=>dialog,body:{append(){}},getElementById:node};
 const context={document,window:{},fetch:async()=>({ok:true,json:async()=>[row]}),URLSearchParams,Intl,Date,Number,formatCalendarDate:x=>x,escapeHistory:x=>String(x).replace(/</g,'&lt;')};
 vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/inventory-reservations.js','utf8'),context);
 await context.window.openInventoryReservations({dataset:{item:'item',product:'Product',locationCode:'MAIN',available:'0'}});
 return node('reservation-orders').innerHTML;
}
test('Shopify reservation uses its logo and one human-readable provider subtitle',async()=>{
 const html=await render({provider:'Shopify',orderId:'#1022',orderStatus:'Shopify · UNFULFILLED',orderedSkus:1,reservedEaches:8});
 assert.match(html,/shopify-bag.png/);assert.match(html,/<strong>Shopify<\/strong><i>·<\/i><strong>Unshipped<\/strong><i>·<\/i><strong>Qty 1/);
 assert.doesNotMatch(html,/Amazon|UNFULFILLED/);
});
test('Amazon reservation preserves its logo, subtitle and status formatting',async()=>{
 const html=await render({provider:'Amazon',orderId:'112-example',orderStatus:'PartiallyShipped',orderedSkus:2,reservedEaches:8});
 assert.match(html,/amazon-seller.png/);assert.match(html,/<strong>Amazon<\/strong><i>·<\/i><strong>Partially Shipped<\/strong>/);assert.doesNotMatch(html,/Shopify/);
});
