const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
const {chromium}=require('playwright');
test('FBA uses shared Record size and exact postal identity including missing and leading-zero codes',async()=>{
 const browser=await chromium.launch({headless:true});try{const page=await browser.newPage();
 await page.setContent('<body class="app-page fba-shipments-page"><main><section class="data-card"><table class="fba-shipment-table"><thead><tr><th>Shipment</th></tr></thead><tbody><tr data-ship-from-postal="00123" data-ship-from-city="Tomball"><td>FBA1</td></tr><tr><td>FBA2</td></tr></tbody></table></section></main></body>');
 for(const file of ['table-widget.css','fba.css'])await page.addStyleTag({path:'src/main/resources/static/css/'+file});
 await page.addScriptTag({path:'src/main/resources/static/js/table-widget.js'});assert.equal(await page.locator('.fba-origin-city').first().textContent(),'Tomball');assert.equal(await page.locator('.fba-origin-frame').first().evaluate(e=>getComputedStyle(e).borderTopStyle),'solid');
 await page.locator('.fba-origin-postal').first().waitFor();assert.equal(await page.locator('.fba-origin-postal').first().textContent(),'00123');assert.equal(await page.locator('.fba-origin-postal').last().textContent(),'—');
 assert.deepEqual(await page.locator('.table-overview-picture').first().evaluate(e=>({w:e.getBoundingClientRect().width,h:e.getBoundingClientRect().height})),{w:80,h:88});
 assert.ok(await page.locator('th[data-column="record-context"]').evaluate(e=>e.getBoundingClientRect().width>=142));
 }finally{await browser.close();}
});
test('Available Inventory keeps Search button with its existing input when shared toolbar mounts',async()=>{
 const browser=await chromium.launch({headless:true});try{const page=await browser.newPage();
 const source=fs.readFileSync('src/main/resources/templates/inventory.html','utf8'),form=source.match(/<form class="inventory-search-form"[\s\S]*?<\/form>/)[0];
 await page.route('http://inventory.test/**',r=>r.fulfill({contentType:'text/html',body:'<main><section class="data-card">'+form+'<div data-column-control="available-inventory"></div><table class="available-inventory-table" data-table-widget="available-inventory"><thead><tr><th>Product</th></tr></thead><tbody><tr><td>Alpha</td></tr></tbody></table></section></main>'}));await page.goto('http://inventory.test');
 await page.evaluate(()=>{window.queries=[];window.setInventorySearch=value=>queries.push(value);window.filters=0;document.querySelector('table').addEventListener('table:filter',()=>filters++);});
 for(const file of ['table-widget.js','table-data-tools.js'])await page.addScriptTag({path:'src/main/resources/static/js/'+file});
 await page.getByRole('searchbox',{name:'Search available inventory'}).fill('SKU-001');await page.getByRole('button',{name:'Search',exact:true}).click();assert.equal(await page.evaluate(()=>queries.at(-1)),'SKU-001');assert.ok(await page.evaluate(()=>filters>0));
 await page.getByRole('searchbox',{name:'Search available inventory'}).press('Enter');assert.equal(page.url(),'http://inventory.test/');assert.equal(await page.locator('.table-standard-toolbar form button[type="submit"]').textContent(),'Search');
 }finally{await browser.close();}
});
