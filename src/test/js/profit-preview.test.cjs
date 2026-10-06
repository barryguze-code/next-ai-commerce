const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const source=fs.readFileSync('src/main/resources/static/js/profit.js','utf8');
const preview=vm.runInNewContext('('+source.slice(source.indexOf('function previewPrices('),source.indexOf(' function render('))+')');
const order={kind:'ORDER',currency:'USD',lines:[{sku:'A',unitPrice:20},{sku:'B',unitPrice:30}]};
const allocation=vm.runInNewContext(source.slice(0,source.indexOf('(() => {'))+';orderSkuProfit');
const fees=vm.runInNewContext(source.slice(0,source.indexOf('(() => {'))+';orderSkuFees');
test('fees retain quantity, referral threshold and allocated shipping cents',()=>{
 const view={totals:{shippingCost:10},packages:[{amount:10}],lines:[{sku:'A',quantity:1,unitPrice:15,productCost:2,otherCost:1},{sku:'B',quantity:2,unitPrice:20,productCost:2,otherCost:1}]};
 assert.equal(fees(view,'A').referral,1.2);assert.equal(fees(view,'B').referral,6);
 assert.equal(fees(view,'B').product,4);assert.equal(fees(view,'B').other,2);
 assert.equal(fees(view,'A').shipping+fees(view,'B').shipping,10);
 assert.equal(fees({...view,packages:[]},'A').shipping,null);
});
test('item profit prorates package shipping by SKU units and preserves pennies',()=>{
 const view={kind:'ORDER',totals:{profit:32,shippingCost:10},lines:[{sku:'A',quantity:1,unitPrice:20,productCost:2,otherCost:1,customerShipping:0},{sku:'B',quantity:2,unitPrice:20,productCost:2,otherCost:1,customerShipping:0}]};
 assert.equal(allocation(view,'A'),10.67);assert.equal(allocation(view,'B'),21.33);
 assert.equal(allocation(view,'A')+allocation(view,'B'),32);
 assert.equal(allocation({...view,totals:{profit:null}},'A'),null);
});
test('Buy Box preview changes only the selected SKU, not historical data',()=>{assert.deepEqual([...preview(order,{profitPreview:'15',profitSku:'B'})],[20,15]);assert.equal(order.lines[1].unitPrice,30);});
test('SKU price preview accepts its selected unit price',()=>{assert.deepEqual([...preview({...order,kind:'SKU',lines:[order.lines[0]]},{profitPreview:'12.08'})],[12.08]);});
test('missing, invalid, or mismatched-currency prices are not substituted',()=>{for(const data of [{},{profitPreview:'bad'},{profitPreview:'-1'},{profitPreview:'15',profitCurrency:'CAD'}])assert.equal(preview(order,data),null);});
