const {test}=require('node:test'),assert=require('node:assert/strict'),{chromium}=require('playwright');
test('shared filter icon clears column criteria while retaining search, context, status and input values',async()=>{
 const browser=await chromium.launch({headless:true});try{
  const page=await browser.newPage();page.setDefaultTimeout(3000);await page.route('http://local.test/**',r=>r.fulfill({contentType:'text/html',body:'<html><body class="app-page platform-standard-tables"><section class="data-card"><form><input type="search" value="Apple" name="q"><input name="draftQuantity" value="17"></form><table data-table-widget="rollout-fixture"><thead><tr><th>Product</th><th>Location</th></tr></thead><tbody><tr><td>Apple</td><td>MAIN</td></tr><tr><td>Apple green</td><td>COLD</td></tr></tbody></table></section></body></html>'}));
  await page.goto('http://local.test/app/inventory?q=Apple&status=HEALTHY&warehouseId=123&sort=product');
  await page.addStyleTag({path:'src/main/resources/static/css/table-widget.css'});await page.addStyleTag({path:'src/main/resources/static/css/platform-ui.css'});await page.addStyleTag({path:'src/main/resources/static/css/standard-record-tables.css'});await page.addScriptTag({path:'src/main/resources/static/js/table-widget.js'});await page.addScriptTag({path:'src/main/resources/static/js/table-data-tools.js'});
  const trigger=page.locator('.table-filter-control>summary'),reset=page.getByRole('button',{name:'Clear table filters',exact:true});
  assert.equal(await trigger.textContent(),'');assert.equal(await reset.isVisible(),false);await trigger.click();await page.getByRole('searchbox',{name:'Filter Location',exact:true}).fill('MAIN');
  assert.equal(await page.locator('.table-filter-reset').isEnabled(),true);assert.equal(await trigger.getAttribute('aria-label'),'Filters active');
  await page.getByRole('button',{name:'Done',exact:true}).click();assert.equal(await reset.isVisible(),true);await reset.click();
  assert.equal(await reset.isVisible(),false);assert.equal(await trigger.getAttribute('aria-label'),'Filters');assert.equal(await page.locator('input[name=q]').inputValue(),'Apple');assert.equal(await page.locator('[name=draftQuantity]').inputValue(),'17');assert.match(page.url(),/status=HEALTHY&warehouseId=123&sort=product/);
  assert.equal(await page.locator('dialog[open]').count(),0);assert.equal(await page.locator('tbody tr:not(.table-page-hidden)').count(),2);
 }finally{await browser.close()}
});
