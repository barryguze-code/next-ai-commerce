const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),{chromium}=require('playwright');
test('reordered packing DOM prints selected defaults even when inventory is reserved',async()=>{
 const browser=await chromium.launch({headless:true});try{const page=await browser.newPage();let fail=false,writes=[],savedDefault="fx";
 await page.route('http://packing.test/**',r=>{if(r.request().method()==='POST'){writes.push(r.request().url());if(!fail)savedDefault='f13';return r.fulfill({status:fail?409:200,contentType:'application/json',body:JSON.stringify(fail?{error:'Could not save default.'}:{itemId:'item1',description:'Product',locationCode:'F13'})});}return r.fulfill({contentType:'text/html',body:fs.readFileSync('src/main/resources/templates/packing-slip.html','utf8').replace(/<script\b[^>]*>[\s\S]*?<\/script>/g,'')});});
 const label=()=>page.locator('.packing-line-location').allTextContents();
 async function load(){await page.goto('http://packing.test/slip');await page.evaluate(saved=>{
  window.NextAiPackageNameEditor=()=>{};window.print=()=>{throw Error('No printing during verification')};
  document.querySelector('.packing-workspace').dataset.orderId='order1';const form=document.querySelector('.packing-product-form');form.dataset.accountItemId='item1';form.action='/products/item1';form.elements.description.value='Product';form.elements.locationId.innerHTML='<option value="">Keep current default</option><option value="fx" data-location-code="FX" selected>FX</option><option value="f13" data-location-code="F13">F13</option>';
  form.elements.locationId.value=saved;const row=document.querySelector('.packing-slip article');row.dataset.accountItemId='item1';row.dataset.reservedLocation='true';row.querySelector('.packing-line-location').textContent='MAIN';const other=row.cloneNode(true);other.dataset.accountItemId='item2';other.querySelector('.packing-line-location').textContent='R1';row.after(other);
 },savedDefault);await page.addScriptTag({path:'src/main/resources/static/js/packing-slip-workspace.js'});}
 await load();assert.deepEqual(await label(),['FX','R1']);assert.equal(await page.locator('.packing-slip article').first().getAttribute('data-reserved-location'),'true');
 await page.locator('select[name=locationId]').selectOption('f13');assert.deepEqual(await label(),['F13','R1']);
 await page.locator('.packing-product-form button[type=submit]').click();await page.getByText('Account details saved. Existing stock and reserved locations are unchanged.',{exact:true}).waitFor();assert.deepEqual(await label(),['F13','R1']);assert.equal(writes.length,1);assert.ok(writes[0].endsWith('/products/item1'));
 await load();assert.deepEqual(await label(),['F13','R1']);fail=true;await page.locator('select[name=locationId]').selectOption('fx');await page.locator('.packing-product-form button[type=submit]').click();await page.getByText('Could not save default.',{exact:true}).waitFor();assert.deepEqual(await label(),['FX','R1']);
 await load();assert.deepEqual(await label(),['F13','R1']);await page.locator('select[name=locationId]').selectOption('');assert.deepEqual(await label(),['MAIN','R1']);
 }finally{await browser.close();}
});
