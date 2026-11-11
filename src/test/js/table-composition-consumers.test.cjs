const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),{chromium}=require('playwright');
const root='src/main/resources/';
const firstPaint=fs.readFileSync(root+'templates/fragments/table-first-paint.html','utf8').split('<th:block th:fragment="head">')[1].split('</th:block>')[0].replace(/<link[^>]*>/g,'');
const consumers=['inventory.html','orders.html','fragments/shopify-orders.html','marketplace-skus.html','fragments/shopify-skus.html','account-catalog.html','global-catalog.html','vendors.html','fba-shipments.html','receiving.html','replenishment-draft.html'];
test('all requested consumers compose once, keep original commands and search form fields',async()=>{
 const browser=await chromium.launch({headless:true});try{for(const file of consumers){
  const source=fs.readFileSync(root+'templates/'+file,'utf8'),start=source.search(/<section\b[^>]*data-table-composition/);assert.ok(start>=0,file);
  const grid=file.includes('orders.html'),end=grid?source.indexOf('<article class="order-row',start):source.indexOf('<tbody>',start)+7;
  assert.ok(end>start,file);const markup=source.slice(start,end)+(grid?'</div></section>':'<tr><td>Product</td></tr></tbody></table></div></section>');
  const p=await browser.newPage();p.setDefaultTimeout(2500);await p.route('http://local.test/**',r=>r.fulfill({contentType:'text/html',body:`<html><head>${firstPaint}</head><body class="app-page platform-standard-tables"><main data-basket-scope="account:user">${markup}</main></body></html>`}));await p.goto('http://local.test/app/'+file);await p.addStyleTag({path:root+'static/css/table-widget.css'});
  const before=await p.evaluate(()=>{window.originalCommands=[...document.querySelectorAll('[data-table-command]')];window.invoked=0;for(const b of document.querySelectorAll('[data-command-id]'))b.onclick=()=>invoked++;const input=document.querySelector('[data-table-toolbar] input[type=search]');window.originalSearch=input;return {fields:[...input.form.elements].map(e=>e.name).filter(Boolean).sort(),id:input.id};});
  await p.evaluate(()=>document.documentElement.classList.add('table-composing'));
  assert.equal(await p.locator('[data-table-command]').filter({visible:true}).count(),0,file+' pending commands');
  assert.equal(await p.locator('[data-table-toolbar] input[type=search]').isVisible(),true,file+' search');
  await p.addScriptTag({path:root+'static/js/table-widget.js'});await p.addScriptTag({path:root+'static/js/table-data-tools.js'});
  assert.equal(await p.locator('[data-table-composition]').getAttribute('data-table-composed'),'true',file+' ready');
  assert.equal(await p.locator('.table-standard-toolbar').count(),1,file+' single toolbar');
  assert.equal(await p.getByLabel('Actions',{exact:true}).count(),1,file+' single Actions');
  assert.deepEqual(await p.evaluate(()=>({fields:[...originalSearch.form.elements].map(e=>e.name).filter(Boolean).sort(),id:originalSearch.id})),before,file+' form scope retained');
  assert.equal(await p.evaluate(()=>originalCommands.every(e=>e.isConnected&&!!e.closest('.table-command-menu'))),true,file+' moved original commands');
  const plus=p.getByLabel('Add shortcuts menu',{exact:true});
  const available=await p.locator('.table-command-group [data-command-id]:not(:disabled)').count();
  if(available){assert.equal(await plus.isVisible(),true,file+' plus');await plus.click();const command=p.locator('.table-shortcut-command [data-command-id]:not(:disabled)').filter({visible:true}).first();await command.click();assert.equal(await p.evaluate(()=>invoked),0,file+' plus never executes');assert.equal(await p.locator('[data-shortcut-id]').count(),1,file+' one shortcut');}
  else assert.equal(await plus.isVisible(),false,file+' no forged commands');
  await p.close();
 }}finally{await browser.close();}
});
