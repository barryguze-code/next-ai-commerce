const {test,before,after}=require('node:test');
const assert=require('node:assert/strict');
const {chromium}=require('playwright');
const path=require('node:path');
let browser;
before(async()=>{browser=await chromium.launch(process.env.TEST_BROWSER_CHANNEL?{channel:process.env.TEST_BROWSER_CHANNEL}:{});});
after(async()=>{await browser?.close();});
test('legacy modal retains form and cancellation guards, adds accessible close and footer',async()=>{
 const page=await browser.newPage();await page.setContent('<button id="open" onclick="document.querySelector(\'dialog\').showModal()">Open</button><dialog><form><div class="dialog-header"><h2>Edit item</h2></div><div class="dialog-body"><input name="cost" value="5.07"></div><div class="dialog-actions"><button type="button">Save changes</button></div></form></dialog>');
 await page.addScriptTag({path:path.resolve('src/main/resources/static/js/ux-components.js')});
 await page.getByRole('button',{name:'Open',exact:true}).click();
 assert.equal(await page.getByRole('dialog',{name:'Edit item'}).count(),1);
 assert.equal(await page.locator('.ux-dialog-footer').count(),1);
 assert.equal(await page.locator('form input').getAttribute('value'),'5.07');
 await page.evaluate(()=>{document.querySelector('dialog').addEventListener('cancel',e=>e.preventDefault(),{once:true});});
 await page.getByRole('button',{name:'Close Edit item'}).click();assert.equal(await page.locator('dialog').evaluate(d=>d.open),true);
 await page.keyboard.press('Escape');assert.equal(await page.locator('dialog').evaluate(d=>d.open),false);await page.close();
});
test('dynamic dialogs are enhanced once and retain one scroll body',async()=>{
 const page=await browser.newPage();await page.setContent('<main></main>');await page.addScriptTag({path:path.resolve('src/main/resources/static/js/ux-components.js')});
 await page.evaluate(()=>{const d=document.createElement('dialog');d.innerHTML='<header><h2>Refunded orders</h2></header><p>Posting dates</p><div>SKU evidence</div>';document.body.append(d);d.showModal();});
 await page.locator('.ux-dialog-body').waitFor();assert.equal(await page.locator('.ux-dialog-body').count(),1);assert.equal(await page.locator('.ux-dialog-footer').count(),1);
 await page.evaluate(()=>window.NextAiDialogs.enhance());assert.equal(await page.locator('.ux-dialog-close').count(),1);await page.getByRole('button',{name:'Done',exact:true}).click();assert.equal(await page.locator('dialog').evaluate(d=>d.open),false);await page.close();
});
test('metric details fetch only on demand and preserve filter scope',async()=>{
 const page=await browser.newPage();let requests=[];
 await page.route('http://ux.test/**',route=>{requests.push(route.request().url());return route.fulfill({contentType:'text/html',body:'<div data-order-stream><article class="order-row"><a class="order-number">123-456</a><div class="item-order-reference"><small>10/07/26</small></div><div class="order-item"><div class="item-product">Yogurt · SKU-1</div><div class="item-number">2</div><div class="item-sales">$12.00</div></div></article></div><div class="table-pagination" data-page-max="2"></div>'});});
 await page.goto('http://ux.test/app/orders?status=SHIPPED&q=yogurt&f_channel=FBM');
 await page.setContent('<section class="order-summary"><article>Today<div class="summary-filtered">Filtered sales</div></article></section>');
 await page.addScriptTag({path:path.resolve('src/main/resources/static/js/ux-components.js')});await page.addScriptTag({path:path.resolve('src/main/resources/static/js/metric-drilldown.js')});
 assert.equal(requests.length,1);await page.getByRole('button',{name:'Filtered sales',exact:true}).click();await page.getByText('Yogurt · SKU-1').waitFor();
 const url=new URL(requests.at(-1));assert.equal(url.searchParams.get('f_channel'),'FBM');assert.equal(url.searchParams.get('q'),'yogurt');assert.equal(url.searchParams.get('status'),'SHIPPED');assert.equal(url.searchParams.get('size'),'25');
 await page.getByRole('button',{name:'Next',exact:true}).click();await page.getByText('Page 2 of 2').waitFor();assert.equal(new URL(requests.at(-1)).searchParams.get('page'),'1');await page.close();
});
