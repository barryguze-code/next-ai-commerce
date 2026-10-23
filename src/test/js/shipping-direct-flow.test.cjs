const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const read=p=>fs.readFileSync('src/main/resources/'+p,'utf8');
test('suppressed recipient opens blank temporary sandbox fields and resets across orders',()=>{
 const source=read('static/js/fedex-shipping.js');
 const start=source.indexOf('function renderRecipient('),end=source.indexOf('\n window.openFedExShipping',start);
 const fields=Object.fromEntries(['name','phone','street','street2','street3','city','state','postalCode','country','residential','testRecipient'].map(k=>[k,{}]));
 const status={classList:{toggle:(name,on)=>status.warning=on}};
 const render=vm.runInNewContext('('+source.slice(start,end).trim()+')',{form:{elements:{...fields,namedItem:k=>fields[k]}},dialog:{querySelector:()=>status}});
 render({status:'INCOMPLETE',recipient:{city:'Imported city'}});
 assert.equal(fields.city.value,'');assert.equal(fields.testRecipient.checked,true);
 assert.match(status.textContent,/Restricted customer PII suppressed by Amazon/);assert.equal(status.warning,true);
 render({status:'AVAILABLE',recipient:{name:'Synthetic person'}});
 assert.equal(fields.name.value,'Synthetic person');assert.equal(fields.testRecipient.checked,false);assert.equal(status.warning,false);
 render({status:'DENIED'});assert.equal(fields.name.value,'');assert.equal(fields.testRecipient.checked,true);
});
test('FedEx package length resolves the input rather than form collection length',()=>{
 const source=read('static/js/fedex-shipping.js');
 const start=source.indexOf('function applyPackaging()');
 const end=source.indexOf('\n',start);
 const fields={packagingId:{value:'PAK_PADDED'},length:{},width:{},height:{}};
 const form={elements:{length:28,packagingId:fields.packagingId,namedItem:name=>fields[name]}};
 vm.runInNewContext(source.slice(start,end)+';applyPackaging();',{form,packagingOptions:[{id:'PAK_PADDED',length:14.75,width:11.75,height:0.5}]});
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
test('Orders table omits shipping entry while preserving the shared shipping capability',()=>{
 const html=read('templates/orders.html');
 assert.doesNotMatch(html,/data-fedex-shipping|onclick="openFedExShipping\(this\)"|data-tooltip="Buy shipping label"/);
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
 assert.match(js,/Generate & open label/);
 assert.match(js,/preview\.location\.replace\(labelUrl\)/);
 assert.match(js,/testRecipient/);
 assert.match(js,/Shipment history/);
});
