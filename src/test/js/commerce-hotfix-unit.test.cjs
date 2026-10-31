const {test}=require('node:test');const assert=require('node:assert/strict');const fs=require('node:fs');const vm=require('node:vm');
const read=n=>fs.readFileSync('src/main/resources/static/js/'+n,'utf8');
test('relocation excludes current source and selects an available destination',()=>{
 const source=read('inventory-ledger-preview.js');const fn=source.slice(source.indexOf('function destinationChoices()'),source.indexOf("\n  sourceSelect.addEventListener"));
 const destination={value:'MAIN',options:[{value:'MAIN'},{value:'F1'},{value:'F2'}]};let location='MAIN';const document={getElementById:id=>id==='location-source'?{value:location}:destination};vm.runInNewContext(fn+';destinationChoices()',{document});assert.equal(destination.options[0].disabled,true);assert.equal(destination.value,'F1');location='F1';vm.runInNewContext(fn+';destinationChoices()',{document});assert.equal(destination.options[0].disabled,false);assert.equal(destination.options[1].disabled,true);assert.equal(destination.value,'MAIN');
});
test('identifier copy carries only its exact value independently of displayed pack quantity',()=>{
 const s=read('order-design.js');const functions=s.slice(s.indexOf('function identifierCopy('),s.indexOf(' function init('));const document={createElement:()=>({dataset:{},setAttribute(){}})};const c={document};vm.createContext(c);vm.runInContext(functions+';result=identifierCopy("0000342934")',c);assert.equal(c.result.dataset.orderCopy,'0000342934');assert.match(c.result.innerHTML,/<svg/);assert.equal(c.result.className,'order-identifier-copy');
});
test('Compare fees is offered only for confirmed differences, never for unknown or matches',()=>{
 const s=read('fee-variance.js'),fn=s.slice(s.indexOf('function hasFeeVariance('),s.indexOf(' async function previews()'));const c={};vm.createContext(c);vm.runInContext(fn,c);
 for(const state of ['MATCHES','UNKNOWN','UNAVAILABLE',null])assert.equal(c.hasFeeVariance([{order_id:'o',seller_sku:'s',state}],'o','s'),false);
 for(const state of ['HIGHER','LOWER'])assert.equal(c.hasFeeVariance([{order_id:'o',seller_sku:'s',state}],'o','s'),true);
 assert.equal(c.hasFeeVariance([{order_id:'other',seller_sku:'s',state:'HIGHER'}],'o','s'),false);assert.equal(c.hasFeeVariance([{seller_sku:'s',affected_orders:1}],null,'s'),true);assert.equal(c.hasFeeVariance([{seller_sku:'s',affected_orders:0}],null,'s'),false);
});
test('SKU navigation uses the selected ASIN on Seller Central and exact Shopify product URL',()=>{
 const s=read('order-design.js'),fn=s.slice(s.indexOf('function marketplaceProductUrl('),s.indexOf(' function init('));const c={URL,encodeURIComponent};vm.createContext(c);vm.runInContext(fn,c);
 assert.equal(c.marketplaceProductUrl(null,'B0ABC12345','https://www.amazon.com/dp/other'),'https://sellercentral.amazon.com/myinventory/inventory?searchTerm=B0ABC12345');
 assert.equal(c.marketplaceProductUrl(null,'B0ABC12345','https://sellercentral.amazon.co.uk/inventory'),'https://sellercentral.amazon.co.uk/myinventory/inventory?searchTerm=B0ABC12345');
 assert.equal(c.marketplaceProductUrl('https://unit.myshopify.com/admin/products/123',null,null),'https://unit.myshopify.com/admin/products/123');assert.equal(c.marketplaceProductUrl(null,null,'https://amazon.com'),null);
});
