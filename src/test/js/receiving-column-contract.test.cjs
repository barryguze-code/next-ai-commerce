const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const {chromium}=require('playwright');
test('matching preserves headerless samples and rejects different batch structures without submitting',async()=>{
 const browser=await chromium.launch({headless:true});
 try{
  const page=await browser.newPage();
  const template=fs.readFileSync('src/main/resources/templates/receiving.html','utf8');
  const dialogs=template.slice(template.indexOf('<dialog id="session-dialog"'),template.indexOf('<dialog id="inline-vendor-dialog"'));
  await page.route('http://receiving.test/',route=>route.fulfill({contentType:'text/html',body:dialogs}));
  const preview=name=>({filename:name,needsMapping:true,pdf:false,headed:{headers:['001','Product','42'],samples:[],totalRows:1},headerless:{headers:['Column 1','Column 2','Column 3'],samples:[{'Column 1':'001','Column 2':'Product','Column 3':'42'}],totalRows:1}});
  const first=preview('first.csv'),second=preview('different.csv');first.headed.headers=['Code','Description','Quantity'];first.headed.samples=[{Code:'001',Description:'Product',Quantity:'42'}];second.headed={...first.headed,headers:[...first.headed.headers,'Extra']};
  await page.route('**/columns/preview',route=>route.fulfill({json:[first,second]}));
  await page.goto('http://receiving.test/');
  await page.evaluate(()=>{document.querySelector('[name=vendorId]').innerHTML='<option value="vendor">Vendor</option>';document.querySelector('#session-dialog').showModal();window.submissions=0;});
  await page.addScriptTag({path:'src/main/resources/static/js/receiving-columns.js'});
  await page.evaluate(()=>document.querySelector('#session-dialog form').addEventListener('submit',e=>{if(e.defaultPrevented)return;window.submissions++;e.preventDefault();}));
  await page.locator('[name=files]').setInputFiles({name:'first.csv',mimeType:'text/csv',buffer:Buffer.from('001,Product,42')});
  await page.locator('#session-dialog .primary-button').click();
  await page.locator('[data-file-index="1"]').waitFor();
  for(const section of await page.locator('[data-file-index]').all()){
   await section.locator('[data-field=Description]').selectOption('1');
   await section.locator('[data-field=Quantity]').selectOption('2');
   await section.locator('[data-field=VendorItemCode]').selectOption('0');
   await section.locator('[data-quantity-unit]').selectOption('EACH');
  }
  await page.locator('#receiving-columns-dialog .primary-button').click();
  assert.match(await page.locator('#receiving-columns-dialog [role=alert]').textContent(),/different.csv.*columns or quantity meaning/);
  assert.equal(await page.locator('[data-field=VendorItemCode]').first().inputValue(),'0');
  assert.equal(await page.evaluate(()=>window.submissions),0);
  assert.equal(await page.locator('.receiving-column-samples').first().textContent().then(t=>t.includes('001')),true);
  assert.equal(await page.locator('#session-dialog a[href*="docs.google.com/spreadsheets"]').count(),0);
 }finally{await browser.close();}
});

test('explicit per-file matching accepts headed equivalents, scrolls over mapping rows, and preserves units',async()=>{
 const browser=await chromium.launch({headless:true});try{
 const page=await browser.newPage({viewport:{width:1100,height:700}});
 const template=fs.readFileSync('src/main/resources/templates/receiving.html','utf8');
 const dialogs=template.slice(template.indexOf('<dialog id="session-dialog"'),template.indexOf('<dialog id="inline-vendor-dialog"'));
 await page.route('http://receiving.test/',r=>r.fulfill({contentType:'text/html',body:dialogs}));
 const sheet={headers:['Item code','Item name','Quantity'],samples:[{'Item code':'1000015745001','Item name':'Pot','Quantity':'42'}],totalRows:1};
 const raw={headers:['Column 1','Column 2','Column 3'],samples:[{'Column 1':'1000015745001','Column 2':'Pot','Column 3':'42'}],totalRows:1};
 await page.route('**/columns/preview',r=>r.fulfill({json:[{filename:'headerless.csv',needsMapping:true,headed:sheet,headerless:raw},{filename:'headed.csv',needsMapping:false,headed:sheet,headerless:raw}]}));
 await page.goto('http://receiving.test/');
 for(const css of ['app','brand','access','platform-controls','catalog-import','receiving','receiving-work'])await page.addStyleTag({path:'src/main/resources/static/css/'+css+'.css'});
 await page.evaluate(()=>{document.querySelector('[name=vendorId]').innerHTML='<option value="vendor">Vendor</option>';document.querySelector('#session-dialog').showModal();});
 await page.addScriptTag({path:'src/main/resources/static/js/receiving-columns.js'});
 await page.locator('[name=files]').setInputFiles({name:'first.csv',mimeType:'text/csv',buffer:Buffer.from('001,Pot,42')});
 await page.locator('#session-dialog .primary-button').click();await page.locator('[data-file-index="1"]').waitFor();
 const scroller=page.locator('#receiving-columns-dialog .dialog-body');
 const row=page.locator('[data-file-index="0"] .mapping-grid label').first();await row.hover();
 await page.mouse.wheel(0,380);await page.waitForTimeout(80);
 assert.ok(await scroller.evaluate(e=>e.scrollTop)>0,'wheel over mapping rows scrolls the body');
 for(const section of await page.locator('[data-file-index]').all()){
 await section.locator('[data-field=VendorItemCode]').selectOption('');await section.locator('[data-field=Description]').selectOption('1');await section.locator('[data-field=Quantity]').selectOption('2');await section.locator('[data-field=AccountSKU]').selectOption('0');await section.locator('[data-quantity-unit]').selectOption('EACH');
 }
 await page.evaluate(()=>{const f=document.querySelector('#session-dialog form');f.action='http://receiving.test/save';});
 let submitted;await page.route('**/save',async r=>{submitted=r.request().postData();await r.fulfill({status:400,body:'<div id="session-dialog"><p role="alert">Fixture stops before saving</p></div>'});});
 await page.locator('#receiving-columns-dialog .primary-button').click();await page.waitForFunction(()=>document.querySelector('#session-dialog [role=alert]')?.textContent.includes('Fixture stops'));
 assert.ok(submitted.includes('columnMapping'));assert.ok(submitted.includes('EACH'));assert.ok(!submitted.includes('"headings":false'));assert.ok(submitted.includes('"headings":true'));
 }finally{await browser.close();}
});

test('unfamiliar headed files keep headings selected and never submit the header as quantity',async()=>{
 const browser=await chromium.launch({headless:true});try{
 const page=await browser.newPage();const template=fs.readFileSync('src/main/resources/templates/receiving.html','utf8');
 const dialogs=template.slice(template.indexOf('<dialog id="session-dialog"'),template.indexOf('<dialog id="inline-vendor-dialog"'));
 await page.route('http://receiving.test/',r=>r.fulfill({contentType:'text/html',body:dialogs}));
 const headers=['Item code','Item name','Quantity'];
 const headed={headers,samples:[{'Item code':'1000015745001','Item name':'Pot','Quantity':'42'}],totalRows:6};
 const raw={headers:['Column 1','Column 2','Column 3'],samples:[{'Column 1':'Item code','Column 2':'Item name','Column 3':'Quantity'}],totalRows:7};
 await page.route('**/columns/preview',r=>r.fulfill({json:[{filename:'TikTok Orders - Sheet2.csv',needsMapping:true,headed,headerless:raw}]}));
 await page.goto('http://receiving.test/');await page.evaluate(()=>{document.querySelector('[name=vendorId]').innerHTML='<option value="vendor">Vendor</option>';document.querySelector('#session-dialog').showModal();});
 await page.addScriptTag({path:'src/main/resources/static/js/receiving-columns.js'});
 await page.locator('[name=files]').setInputFiles({name:'TikTok Orders - Sheet2.csv',mimeType:'text/csv',buffer:Buffer.from('Item code,Item name,Quantity\n001,Pot,42')});
 await page.locator('#session-dialog .primary-button').click();await page.locator('[data-file-index]').waitFor();
 assert.equal(await page.locator('[data-has-headings]').count(),0);
 assert.equal(await page.locator('[data-field=VendorItemCode]').inputValue(),'0');
 assert.equal(await page.locator('[data-field=Description]').inputValue(),'1');
 assert.equal(await page.locator('[data-field=Quantity]').inputValue(),'2');
 }finally{await browser.close();}
});

test('wide invoice previews remain inside the fixed matcher with enhanced choices',async()=>{
 const browser=await chromium.launch({headless:true});try{
 const page=await browser.newPage({viewport:{width:1280,height:900}});
 const template=fs.readFileSync('src/main/resources/templates/receiving.html','utf8');const dialogs=template.slice(template.indexOf('<dialog id="session-dialog"'),template.indexOf('<dialog id="inline-vendor-dialog"'));
 await page.route('http://receiving.test/',r=>r.fulfill({contentType:'text/html',body:dialogs}));
 const headers=['Line','Status','OrderQuantity','ShipQuantity','QuantityNotShipped','InvalidReason','ShipItem','PackSize','Brand','Description','Upc','Retail','SuggestedRetail','Wholesale','AdjustedWholesale','DiscountPercent','Discount','NetEach','NetBillable','Upcharges','BottleTax','InvoiceNumber','InvoiceDate','StoreNumber'];
 const sample=Object.fromEntries(headers.map(h=>[h,h==='Description'?'Fixture product':h==='ShipQuantity'?'42':'1']));
 await page.route('**/columns/preview',r=>r.fulfill({json:[{filename:'invoice.csv',needsMapping:false,headed:{headers,samples:[sample],totalRows:1}}]}));
 await page.goto('http://receiving.test/');for(const css of ['app','brand','access','platform-controls','catalog-import','receiving','receiving-work'])await page.addStyleTag({path:'src/main/resources/static/css/'+css+'.css'});
 await page.evaluate(()=>{document.querySelector('[name=vendorId]').innerHTML='<option value="vendor">Vendor</option>';document.querySelector('#session-dialog').showModal();});
 await page.addScriptTag({path:'src/main/resources/static/js/platform-controls.js'});await page.addScriptTag({path:'src/main/resources/static/js/receiving-columns.js'});
 await page.locator('[name=files]').setInputFiles({name:'invoice.csv',mimeType:'text/csv',buffer:Buffer.from('fixture')});await page.locator('#session-dialog .primary-button').click();await page.locator('[data-file-index]').waitFor();
 await page.addStyleTag({path:'src/main/resources/static/css/ux-components.css'});
 await page.addScriptTag({path:'src/main/resources/static/js/ux-components.js'});
 assert.ok(await page.locator('#receiving-columns-dialog .standard-choice-trigger').count()>0);
 const bounds=await page.locator('#receiving-columns-dialog').boundingBox();assert.ok(bounds.width<=960);assert.ok(bounds.height<=820);
 const rows=await page.locator('#receiving-columns-dialog .mapping-grid>label').all();for(const row of rows){if(!await row.isVisible())continue;const box=await row.boundingBox();assert.ok(box.height<140,'mapping row must remain compact');assert.ok(box.x>=bounds.x&&box.x+box.width<=bounds.x+bounds.width+1,JSON.stringify({box,bounds,styles:await row.evaluate(e=>({width:getComputedStyle(e).width,minWidth:getComputedStyle(e).minWidth,grid:getComputedStyle(e).gridTemplateColumns}))}));}
 await page.locator('#receiving-columns-dialog').evaluate(e=>e.style.translate='10px 0px');
 for(const trigger of await page.locator('#receiving-columns-dialog .standard-choice-trigger').all()){
  if(!await trigger.isVisible()||!await trigger.isEnabled())continue;
  await trigger.click();
  await page.locator('#receiving-columns-dialog .dialog-body').evaluate(e=>e.dispatchEvent(new Event('scroll')));
  await page.waitForTimeout(80);
  assert.equal(await trigger.getAttribute('aria-expanded'),'true','every mapping choice remains open after focus: '+await trigger.getAttribute('aria-label'));
  const popup=page.locator('#'+await trigger.getAttribute('aria-controls'));
  const pbox=await popup.boundingBox();assert.ok(pbox.y>=bounds.y&&pbox.y+pbox.height<=bounds.y+bounds.height+1,'popup stays inside dialog');
  await page.screenshot({path:'/tmp/receiving-choice-open.png'});
  await popup.locator('button').last().click();
  assert.equal(await trigger.getAttribute('aria-expanded'),'false');
  await trigger.press('ArrowDown');await popup.locator('button').first().press('Escape');
  assert.equal(await trigger.getAttribute('aria-expanded'),'false');
 }
 await page.screenshot({path:'/tmp/receiving-kehe-bounded.png'});
 }finally{await browser.close();}
});
test('Receiving page loads its column matching and document runtime',()=>{
 const template=fs.readFileSync('src/main/resources/templates/receiving.html','utf8');
 for(const asset of ['receiving-work.css','receiving-documents.js','receiving-columns.js'])assert.ok(template.includes('/'+asset.split('.').pop()+'/'+asset),`Missing Receiving asset: ${asset}`);
});
