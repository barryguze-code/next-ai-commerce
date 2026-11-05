const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
const {chromium}=require('playwright');
const root='src/main/resources/';
async function scripts(page,files){for(const f of files)await page.addScriptTag({path:root+'static/js/'+f});}
test('selected-document action survives the shared menu stopping bubbling and retains exact selection',async()=>{
 const browser=await chromium.launch({headless:true});try{const page=await browser.newPage();
 await page.route('http://localhost/**',r=>r.fulfill({contentType:'text/html',body:'<div class="rw-selection-bar" data-selection-account="tenant"><span data-selected-count></span><button data-clear-selected></button><button data-open-selected></button></div><table><tr><td><input data-source-select type="checkbox" value="doc-one"><input data-source-select type="checkbox" value="doc-two"><div id="menu"><button data-open-selection>Receive selected</button></div></td></tr></table>'}));
 await page.goto('http://localhost/app/receiving');await scripts(page,['receiving-documents.js']);
 await page.locator('[data-source-select]').nth(0).check();await page.locator('[data-source-select]').nth(1).check();
 await page.locator('#menu').evaluate(e=>e.addEventListener('click',event=>event.stopPropagation()));
 await Promise.all([page.waitForURL('**/app/receiving/work?**'),page.locator('[data-open-selection]').click()]);
 assert.equal(new URL(page.url()).searchParams.get('documents'),'doc-one,doc-two');
 }finally{await browser.close();}
});
test('inventory CSV uses atomic source identifiers and numeric quantities across all matching pages',async()=>{
 const browser=await chromium.launch({headless:true});try{const page=await browser.newPage();
 await page.route('http://inventory.test/**',r=>r.fulfill({contentType:'text/html',body:'<section class="data-card"><table data-table-widget="available-inventory" data-overview-ready="true"><thead><tr><th data-column="product">Product</th><th>Location</th><th>On hand</th><th>Reserved</th><th data-column="physical-available">Available</th><th>Expiration</th><th>Shelf life</th><th>Unit cost</th></tr></thead><tbody>'+Array.from({length:30},(_,i)=>`<tr data-product="Product ${i}" data-export-brand="Brand" data-export-account-sku="0000123" data-export-vendor-code="=unsafe" data-export-identifier="0012345678905" data-export-asin="B000000001" data-location-code="MAIN" data-export-location-name="Main warehouse" data-export-on-hand="12.5" data-export-reserved="3.25" data-export-available="9.25" data-expiration="2027-02-01" data-status="HEALTHY" data-export-unit-cost="-2.50" data-export-currency="USD"><td>Product and combined identifiers</td><td>MA</td><td>2 cases / 12 each</td><td>3 each Open orders</td><td>9 each</td><td>Date and edit</td><td>Healthy</td><td>USD -2.50</td></tr>`).join('')+'</tbody></table></section>'}));
 await page.goto('http://inventory.test/app/inventory');await scripts(page,['table-preferences.js','table-widget.js','table-data-tools.js']);
 const download=page.waitForEvent('download');await page.getByRole('button',{name:/CSV|download/i}).click();const file=await(await download).path();const csv=fs.readFileSync(file,'utf8');
 const lines=csv.replace(/^\uFEFF/,'').split('\r\n');assert.equal(lines.length,31);
 assert.match(lines[0],/"Account SKU","Vendor item code","UPC \/ EAN \/ identifier","ASIN"/);
 assert.match(lines[1],/"0000123","'=unsafe","0012345678905","B000000001"/);assert.match(lines[1],/"12.5","3.25","9.25"/);assert.match(lines[1],/"-2.50","USD"/);assert.doesNotMatch(csv,/2 cases|Open orders|combined identifiers/);
 }finally{await browser.close();}
});
test('receipt save keeps the shared table page and scroll position',async()=>{
 const browser=await chromium.launch({headless:true});try{const page=await browser.newPage({viewport:{width:1200,height:800}});
 page.on('pageerror',e=>console.error(e.message));
 const template=fs.readFileSync(root+'templates/receiving-work.html','utf8'),dialogs=template.slice(template.indexOf('<dialog'),template.indexOf('<script th:inline'));
 const state={documents:[{id:'doc',type:'INVOICE',number:'QA'}],lines:Array.from({length:60},(_,i)=>({id:'line-'+i,documentId:'doc',productId:'item-'+i,product:'Product '+i,remaining:12,received:0,receipts:0,unitsPerCase:1,unitCost:2,currency:'USD',expected:12,depositFee:0,otherFee:0,requiresExpiration:true,locationId:'main'})),locations:[{id:'main',code:'MAIN',name:'Main',status:'ACTIVE'}],details:[],policy:{minimumSellableDays:4,warningDays:18}};
 await page.route('http://localhost/**',r=>{const path=new URL(r.request().url()).pathname;if(path.endsWith('/state'))return r.fulfill({json:state});if(path.endsWith('/identity'))return r.fulfill({json:{imageUrl:''}});if(path.endsWith('/expiration-status'))return r.fulfill({json:{tone:'success',message:'Expiration OK'}});if(r.request().method()==='POST')return r.fulfill({json:{message:'Receipt saved.'}});
 return r.fulfill({contentType:'text/html',body:'<main class="receive-work" data-can-edit="true" style="height:650px;overflow:auto"><section id="rw-lines-card" class="data-card"><table class="rw-table" data-table-widget="receiving-work" data-overview-ready="true"><thead><tr><th data-column="product">Product</th><th data-column="expected">Expected</th><th data-column="received">Received</th><th data-column="remaining">Remaining</th><th data-column="expiration">Expiration</th><th data-column="condition">Condition</th><th data-column="actions">Actions</th></tr></thead><tbody>'+state.lines.map(l=>`<tr data-line="${l.id}" style="height:75px"><td>${l.product}</td><td data-column="expected">12</td><td data-column="received">0</td><td data-column="remaining">12</td><td data-column="expiration"></td><td data-column="condition"></td><td data-column="actions"><button data-receive="${l.id}">Receive</button></td></tr>`).join('')+'</tbody></table></section><p id="rw-feedback"></p></main>'+dialogs});});
 await page.goto('http://localhost/app/receiving/work');await page.evaluate(s=>window.receivingWork=s,state);await scripts(page,['table-preferences.js','table-widget.js','table-data-tools.js','receiving-work.js']);
 await page.getByRole('button',{name:'Next',exact:true}).click();await page.locator('[data-line="line-30"]').getByRole('button',{name:'Receive',exact:true}).scrollIntoViewIfNeeded();const before=await page.locator('main').evaluate(e=>e.scrollTop);
 await page.locator('[data-line="line-30"]').getByRole('button',{name:'Receive',exact:true}).click();await page.locator('[name=expiration]').fill('2027-08-01');await page.getByRole('button',{name:'Save receipt',exact:true}).click();await page.waitForFunction(()=>!document.getElementById('receiving-drawer').open);await page.waitForTimeout(50);
 assert.equal(await page.getByRole('spinbutton',{name:'Page number'}).inputValue(),'2');assert.ok(Math.abs(await page.locator('main').evaluate(e=>e.scrollTop)-before)<3);
 }finally{await browser.close();}
});
