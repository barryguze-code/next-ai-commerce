const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const read=p=>fs.readFileSync('src/main/resources/'+p,'utf8');
test('temporary recipient fields clear completely between orders',()=>{
 const source=read('static/js/fedex-shipping.js');
 const names=['name','phone','street','street2','street3','city','state','postalCode','country'];
 const fields=Object.fromEntries([...names,'residential'].map(k=>[k,{}]));
 const start=source.indexOf('function fillAddress('),end=source.indexOf('\n',start);
 const render=vm.runInNewContext('('+source.slice(start,end).trim()+')',{addressNames:names,form:{elements:{...fields,namedItem:k=>fields[k]}}});
 render({name:'Synthetic person',city:'Test city',residential:true});assert.equal(fields.name.value,'Synthetic person');assert.equal(fields.residential.checked,true);
 render(null);for(const name of names)assert.equal(fields[name].value,'');assert.equal(fields.residential.checked,false);
 render({city:'Next city'});assert.equal(fields.name.value,'');assert.equal(fields.city.value,'Next city');
});
test('FedEx package length resolves the input rather than form collection length',()=>{
 const source=read('static/js/fedex-shipping.js');
 const start=source.indexOf('function applyPackaging()');
 const end=source.indexOf('\n',start);
 const fields={packagingId:{value:'PAK_PADDED'},length:{},width:{},height:{}};
 const form={elements:{length:28,packagingId:fields.packagingId,namedItem:name=>fields[name]}};
 vm.runInNewContext(source.slice(start,end)+';applyPackaging();',{form,dialog:{querySelector:()=>({})},packaging:[{id:'PAK_PADDED',length:14.75,width:11.75,height:0.5}]});
 assert.equal(fields.length.value,14.75);
 assert.equal(fields.length.readOnly,true);
});
test('Orders decoration keeps direct shipping visible on repeated row updates',()=>{
 const source=read('static/js/order-design.js');
 const start=source.indexOf("actions.querySelectorAll('.order-icon-action').forEach(b=>{");
 const end=source.indexOf('\n    });',start)+8;
 const tip={hidden:false},button={dataset:{},classList:{contains:()=>false},
  matches:selector=>selector==='[data-fedex-shipping]',
  closest:selector=>selector==='.order-action-tip'?tip:null};
 const actions={querySelectorAll:()=>[button]};
 for(let i=0;i<2;i++)vm.runInNewContext(source.slice(start,end),{actions});
 assert.equal(tip.hidden,false,'Buy Shipping must survive the shared Orders action decorator');
});
test('shipping hub does not load or render batch workflow',()=>{
 const html=read('templates/shipping.html');
 assert.doesNotMatch(html,/shipping-desk\.js|data-create-batch|data-batches|data-policy-form|batch-review/);
 assert.match(html,/\/app\/orders\?f_channel=FBM/);
 assert.match(html,/\/app\/shipping\/preferences/);
});
test('Orders keeps gated carrier entry in the shared packing action capability',()=>{
 const html=read('templates/orders.html');
 assert.match(html,/data-fedex-amazon-shipping=\$\{fedexAmazonSandbox == true\}/);assert.match(html,/order-packing-actions\.js/);const actions=read('static/js/order-packing-actions.js');assert.match(actions,/provider==='Seller Central'&&amazonFedEx/);assert.match(actions,/disabled:shipping.dataset.fedexEligible!=='true'/);
 assert.match(read('templates/fragments/sidebar-nav.html'),/Buy Shipping/);
 assert.match(read('static/js/fedex-shipping.js'),/window.openFedExShipping=/);
 assert.doesNotMatch(html,/onclick="openBuyShipping\(this\)"/);
});
test('preferences use shared custom choices with native popup suppressed',()=>{
 const html=read('templates/shipping-preferences.html');
 const selects=html.match(/<select\b[^>]*>/g)||[];
 assert.equal(selects.length,2);
 for(const select of selects){assert.match(select,/data-standard-choice/);assert.match(select,/hidden/);assert.match(select,/aria-label=/);}
 assert.match(read('templates/fragments/sidebar-nav.html'),/platform-controls\.js/);
 assert.match(read('static/js/platform-controls.js'),/role','listbox/);
});
test('label generation is direct, preserves history, and no longer needs queue',()=>{
 const js=read('static/js/fedex-shipping.js');
 assert.doesNotMatch(js,/NextAiConfirm|Save package for queue|appear in the Buy Shipping queue/);
 assert.match(js,/Buy shipping label/);
 assert.match(js,/prepareShippingLabelPrint\(preview,/);
 assert.match(js,/testRecipient/);
 assert.match(js,/function renderHistory/);assert.match(js,/Reprint label/);
});
