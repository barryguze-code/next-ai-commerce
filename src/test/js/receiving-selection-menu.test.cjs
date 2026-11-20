const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
const {chromium}=require('playwright');
test('receiving actions update single/multiple artwork and clear selection without banner',async()=>{
 const browser=await chromium.launch({headless:true});try{
 const page=await browser.newPage({viewport:{width:1100,height:800}});
 await page.route('http://selection.test/',r=>r.fulfill({contentType:'text/html',body:'<!doctype html><meta charset="utf-8"><body class="app-page platform-standard-tables"><div class="table-command-panel"><header class="table-action-heading"><strong>Actions</strong></header><div class="rw-selection-bar" data-selection-account="fixture"><span data-selected-count class="sr-only"></span><div class="rw-row-actions"><button data-clear-selected data-action-image="/images/platform/table/clear-selection-owner.png">Clear selection</button><button data-open-selected data-action-image="/images/platform/table/receive-documents-owner.png">Receive</button></div></div></div><table><tr><td><input type="checkbox" data-source-select value="one"></td></tr><tr><td><input type="checkbox" data-source-select value="two"></td></tr></table>'}));
 await page.goto('http://selection.test/');
 const root='src/main/resources/static/';for(const css of ['app.css','brand.css','receiving.css','receiving-work.css'])await page.addStyleTag({path:root+'css/'+css});
 // Reproduce shared command enhancement before subsequent selection changes.
 await page.evaluate(()=>document.querySelectorAll('.rw-row-actions button').forEach(b=>{b.className='table-action-entry';b.innerHTML='<span class="table-action-icon"><img src="'+b.dataset.actionImage+'"></span><span class="table-action-label"><strong>'+b.textContent+'</strong></span>'}));
 await page.addScriptTag({path:root+'js/receiving-documents.js'});
 const receive=page.locator('[data-open-selected]');
 assert.equal(await receive.locator('strong').textContent(),'Receive all open');
 await page.locator('[value=one]').check();assert.equal(await receive.locator('strong').textContent(),'Receive selected');assert.match(await receive.locator('img').getAttribute('src'),/receive-document-owner/);
 await page.locator('[value=two]').check();assert.equal(await receive.locator('strong').textContent(),'Receive selected · 2');assert.match(await receive.locator('img').getAttribute('src'),/receive-documents-owner/);
 await page.locator('[value=two]').uncheck();assert.match(await receive.locator('img').getAttribute('src'),/receive-document-owner/);
 await page.getByRole('button',{name:'Clear selection'}).click();assert.equal(await receive.locator('strong').textContent(),'Receive all open');assert.equal(await page.locator('[data-clear-selected]').isDisabled(),true);
 assert.equal(await page.locator('.has-selection').count(),0);
 await page.screenshot({path:'/tmp/receiving-selection-menu.png'});
 }finally{await browser.close()}
});
