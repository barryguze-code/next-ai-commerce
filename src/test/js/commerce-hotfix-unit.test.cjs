const {test}=require('node:test');const assert=require('node:assert/strict');const fs=require('node:fs');const vm=require('node:vm');
const read=n=>fs.readFileSync('src/main/resources/static/js/'+n,'utf8');
test('relocation excludes current source and selects an available destination',()=>{
 const source=read('inventory-ledger-preview.js');const fn=source.slice(source.indexOf('function destinationChoices()'),source.indexOf("\n  sourceSelect.addEventListener"));
 const destination={value:'MAIN',options:[{value:'MAIN',dataset:{warehouse:'one'}},{value:'F1',dataset:{warehouse:'one'}},{value:'F2',dataset:{warehouse:'two'}}],get selectedOptions(){return this.options.filter(o=>o.value===this.value);}};let location='MAIN';const document={getElementById:id=>id==='location-source'?{value:location}:destination};vm.runInNewContext(fn+';destinationChoices()',{document});assert.equal(destination.options[0].disabled,true);assert.equal(destination.value,'F1');assert.equal(destination.options[2].disabled,true);location='F1';vm.runInNewContext(fn+';destinationChoices()',{document});assert.equal(destination.options[0].disabled,false);assert.equal(destination.options[1].disabled,true);assert.equal(destination.value,'MAIN');
});
test('identifier copy carries only its exact value independently of displayed pack quantity',()=>{
 const {JSDOM}=require('jsdom'),dom=new JSDOM('<div class="order-item"><div class="item-product-copy"><button class="order-sku-copy" data-copy-sku="0000342934">0000342934 × 4</button></div></div>',{url:'https://local.test/app/orders',runScripts:'outside-only'});
 dom.window.eval(read('order-design.js'));const value=dom.window.document.querySelector('.order-sku-copy');assert.equal(value.dataset.uiCopy,'0000342934');assert.equal(value.getAttribute('aria-label'),'Copy SKU 0000342934');assert.equal(value.querySelector('svg'),null);dom.window.close();
});
test('Compare fees is offered only for confirmed differences, never for unknown or matches',()=>{
 const s=read('fee-variance.js'),fn=s.slice(s.indexOf('function hasFeeVariance('),s.indexOf(' async function previews()'));const c={};vm.createContext(c);vm.runInContext(fn,c);
 for(const state of ['MATCHES','UNKNOWN','UNAVAILABLE',null])assert.equal(c.hasFeeVariance([{order_id:'o',seller_sku:'s',state}],'o','s'),false);
 for(const state of ['HIGHER','LOWER'])assert.equal(c.hasFeeVariance([{order_id:'o',seller_sku:'s',state}],'o','s'),true);
 assert.equal(c.hasFeeVariance([{order_id:'other',seller_sku:'s',state:'HIGHER'}],'o','s'),false);assert.equal(c.hasFeeVariance([{seller_sku:'s',affected_orders:1}],null,'s'),true);assert.equal(c.hasFeeVariance([{seller_sku:'s',affected_orders:0}],null,'s'),false);
});
test('SKU navigation preserves the selected ASIN and exact Shopify product URL',()=>{
 const {JSDOM}=require('jsdom');for(const url of ['https://sellercentral.amazon.com/myinventory/inventory?searchTerm=B0ABC12345','https://sellercentral.amazon.co.uk/myinventory/inventory?searchTerm=B0ABC12345','https://unit.myshopify.com/admin/products/123']){
  const dom=new JSDOM('<div class="order-item"><div class="item-product-copy"><button class="order-sku-copy" data-copy-sku="SKU">SKU</button></div><span class="order-marketplace-links"><a href="'+url+'">Marketplace</a></span></div>',{url:'https://local.test/app/orders',runScripts:'outside-only'});dom.window.eval(read('order-design.js'));assert.equal(dom.window.document.querySelector('.order-identifier-market').href,url);dom.window.close();
 }
});
