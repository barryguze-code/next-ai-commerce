const {test}=require('node:test');
const assert=require('node:assert/strict');
const {chromium}=require('playwright');
const fs=require('node:fs');
const path=require('node:path');
test('all Inventory stylesheet consumers request the current correction',()=>{
 for(const file of ['inventory.html','inventory-ledger.html']){
  const html=fs.readFileSync('src/main/resources/templates/'+file,'utf8');
  assert.match(html,/href="\/css\/inventory\.css\?v=1\.10\.2"/);
 }
});
test('actual Inventory tab strip uses desktop space and retains semantic selection through narrow overflow',async()=>{
 const browser=await chromium.launch({headless:true});
 try{
 const page=await browser.newPage({viewport:{width:1440,height:900}});
 fs.mkdirSync('.local/tab-verification',{recursive:true});
 const template=fs.readFileSync('src/main/resources/templates/inventory.html','utf8');
 const row=template.match(/<div class="inventory-filter-row">.*?<\/div><\/div>/)[0];
 await page.setContent('<body class="app-page"><main style="width:calc(100vw - 48px);min-width:0">'+row+'</main></body>');
 for(const file of ['inventory-record-fixes.css','app.css','brand.css','access.css','inventory.css','locations.css','collaboration.css','collaboration-detail.css','collaboration-chat.css','collaboration-smart.css','collaboration-thread.css','catalog-inventory-controls.css','standard-record-tables.css','order-pictures.css','confirm-dialog.css','platform-ui.css','action-suggestions.css','platform-controls.css','mobile-navigation.css','ux-components.css','product-thumbnails.css','short-date-input.css','huddle-floating.css','chat-composer.css','collaboration-accessibility.css','general-task.css','collaboration-task-dates.css','table-widget.css','background-jobs.css','profile-picture.css','release-panel.css','locations.css','stock-editor.css','profit.css','inventory-ledger-preview.css'])await page.addStyleTag({path:path.resolve('src/main/resources/static/css',file)});
 await page.addScriptTag({path:path.resolve('src/main/resources/static/js/table-widget.js')});
 await page.waitForSelector('.table-tab-overflow');
 await page.evaluate(()=>new Promise(requestAnimationFrame));
 const bar=page.locator('.inventory-filter-bar');
 assert.ok((await bar.boundingBox()).width>1000,'desktop tabs must receive available row width');
 assert.deepEqual(await bar.locator('button').evaluateAll(es=>es.map(e=>e.dataset.filter)),['ALL','HEALTHY','BLOCKED','ACT_SOON']);
 assert.equal(await page.locator('.table-tab-arrow:visible').count(),0);
 for(const theme of ['light','dark']){
 await page.locator('html').evaluate((e,t)=>e.dataset.theme=t,theme);
 for(const status of ['BLOCKED','ACT_SOON']){
 const button=bar.locator('[data-filter="'+status+'"]');
 await bar.locator('button').evaluateAll(es=>es.forEach(e=>e.classList.remove('active')));
 const normal=await button.evaluate(e=>getComputedStyle(e).color);
 await button.evaluate(e=>e.classList.add('active'));
 assert.equal(await button.evaluate(e=>getComputedStyle(e).color),normal,'selection retains semantic text hue');
 assert.equal(await button.locator('b').evaluate(e=>getComputedStyle(e).color),normal,'badge retains semantic hue');
 await page.locator('.inventory-filter-row').screenshot({path:'.local/tab-verification/'+theme+'-'+status+'.png'});
 }
 }
 await page.setViewportSize({width:320,height:800});
 await page.waitForFunction(()=>[...document.querySelectorAll('.table-tab-arrow')].some(e=>!e.hidden));
 assert.ok(await page.locator('.table-tab-arrow:visible').count()>0,'narrow overflow has directional controls');
 assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'no page overflow');
 await page.setViewportSize({width:1440,height:900});
 await page.waitForFunction(()=>[...document.querySelectorAll('.table-tab-arrow')].every(e=>e.hidden));
 assert.ok((await bar.boundingBox()).width>1000);
 }finally{await browser.close();}
});
