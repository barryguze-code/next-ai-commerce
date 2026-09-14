const {test,after,before}=require('node:test');
const assert=require('node:assert/strict');
const {chromium}=require('playwright');
const path=require('node:path');
let browser;
test('marking an order refreshes counts without navigating or losing scroll',async()=>{
  const page=await browser.newPage();
  let saved=false;
  const content=()=>'<main class="orders-workspace" style="height:500px;overflow:auto"><div data-order-stream><p id="count">'+(saved?'9':'10')+'</p><div class="order-list table-widget-scroll" style="height:300px;overflow:auto"><div style="height:300px"></div><form class="platform-pickup-action" action="/app/orders/test/pickup-override" method="post"><input name="waiting" value="true" type="hidden"><input name="_csrf" value="test-token" type="hidden"><button type="submit">Mark as shipped</button></form><div style="height:900px"></div></div><div style="height:900px"></div></div></main>';
  await page.route('http://orders.test/**',async route=>{
    if(route.request().method()==='POST'){
      assert.match(route.request().postData(),/test-token/);saved=true;
      await route.fulfill({contentType:'text/html',body:'Saved'});
    }else await route.fulfill({contentType:'text/html',body:content()});
  });
  const url='http://orders.test/app/orders?status=UNSHIPPED&q=chocolate&size=100&page=2';
  await page.goto(url);
  await page.addScriptTag({path:path.resolve('src/main/resources/static/js/order-quick-actions.js')});
  await page.locator('.orders-workspace').evaluate(el=>el.scrollTop=40);
  await page.locator('.order-list').evaluate(el=>el.scrollTop=210);
  await page.getByRole('button',{name:'Mark as shipped',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('#count').textContent==='9');
  assert.equal(page.url(),url);
  assert.equal(await page.locator('.orders-workspace').evaluate(el=>el.scrollTop),40);
  assert.equal(await page.locator('.order-list').evaluate(el=>el.scrollTop),210);
  assert.match(await page.locator('[data-order-action-notice]').textContent(),/waiting for pickup/);
  await page.close();
});
test('order completion action stays compact and workspace scroll is contained',async()=>{
  const page=await browser.newPage({viewport:{width:1280,height:800}});
  await page.setContent('<body class="app-page"><aside class="sidebar">Navigation</aside><main class="workspace orders-workspace"><div class="order-list table-widget"><div class="order-item"><div class="item-actions"><button class="order-icon-action" style="min-width:32px;width:32px;padding:0">P</button><button class="order-icon-action" style="min-width:32px;width:32px;padding:0">S</button><form class="platform-pickup-action"><button class="order-icon-action" title="Mark as shipped">✓</button></form></div><div class="item-number">USD 28.97</div></div></div><div style="height:1400px">Rows</div></main></body>');
  for(const file of ['app.css','orders.css','order-stream.css','table-widget.css'])await page.addStyleTag({path:path.resolve('src/main/resources/static/css',file)});
  for(const theme of ['light','dark']){
    await page.locator('html').evaluate((el,value)=>el.dataset.theme=value,theme);
    const button=await page.getByTitle('Mark as shipped').boundingBox();
    const price=await page.locator('.item-number').boundingBox();
    assert.equal(button.width,32);assert.ok(button.x+button.width<=price.x);
    assert.equal(await page.locator('.orders-workspace').evaluate(el=>getComputedStyle(el).overflowY),'auto');
    assert.equal(await page.locator('body').evaluate(el=>getComputedStyle(el).overflowY),'hidden');
  }
  await page.close();
});
before(async()=>{browser=await chromium.launch({headless:true,...(process.env.TEST_BROWSER_CHANNEL?{channel:process.env.TEST_BROWSER_CHANNEL}:{})})});
after(async()=>{await browser?.close()});
async function fixture(){
  const page=await browser.newPage();
  await page.route('http://table.test/**',route=>route.fulfill({contentType:'text/html',body:'<html><body></body></html>'}));
  await page.goto('http://table.test/app/inventory');
  await page.setContent('<section class="data-card"><div class="table-wrap"><table data-table-widget="test"><thead><tr><th data-column-required="true">Product</th><th>Location</th><th>Action</th></tr></thead><tbody>'+Array.from({length:61},(_,i)=>'<tr><td><button>Product '+i+'</button></td><td>'+(i%2?'West':'East')+'</td><td><button>Edit</button></td></tr>').join('')+'</tbody></table></div></section>');
  for(const file of ['table-preferences.js','table-widget.js','table-data-tools.js'])await page.addScriptTag({path:path.resolve('src/main/resources/static/js',file)});
  return page;
}
test('pagination, search, column filters and refresh share one state',async()=>{
  const page=await fixture();
  assert.equal(await page.locator('tbody tr:not(.table-data-hidden)').count(),25);
  await page.getByRole('button',{name:'Next',exact:true}).click();
  assert.match(await page.locator('.table-standard-pagination').innerText(),/26–50 of 61/);
  await page.getByRole('searchbox',{name:'Search table',exact:true}).fill('Product 60');
  assert.equal(await page.locator('tbody tr:not(.table-data-hidden)').count(),1);
  await page.getByRole('searchbox',{name:'Search table',exact:true}).fill('');
  await page.locator('.table-filter-control summary').click();
  await page.getByRole('searchbox',{name:'Filter Location',exact:true}).fill('West');
  assert.match(await page.locator('.table-standard-pagination').innerText(),/of 30/);
  await page.evaluate(()=>window.NextAiTableWidget.refresh());
  assert.equal(await page.locator('.table-standard-toolbar').count(),1);
  await page.close();
});
test('CSV preserves button-backed data, quotes and guards formulas',async()=>{
  const page=await fixture();
  assert.equal(await page.evaluate(()=>window.NextAiTableDataTools.csvCell('=SUM(A1)')),'"\'=SUM(A1)"');
  assert.equal(await page.evaluate(()=>window.NextAiTableDataTools.csvCell('a,"b"')),'"a,""b"""');
  const download=page.waitForEvent('download');await page.getByRole('button',{name:'Download table as CSV',exact:true}).click();
  const stream=await (await download).createReadStream();let csv='';for await(const chunk of stream)csv+=chunk.toString();
  assert.match(csv,/Product 60/);assert.equal(csv.trim().split('\r\n').length,62);assert.doesNotMatch(csv,/Edit/);
  await page.close();
});
test('shared pager resets size, bounds page jumps and keeps icon download accessible',async()=>{
  const page=await fixture();
  await page.getByRole('combobox',{name:'Rows per page'}).selectOption('50');
  assert.match(await page.locator('.table-standard-pagination').innerText(),/1–50 of 61/);
  await page.getByRole('spinbutton',{name:'Page number'}).fill('999');
  await page.getByRole('spinbutton',{name:'Page number'}).press('Enter');
  assert.match(await page.locator('.table-standard-pagination').innerText(),/51–61 of 61/);
  assert.equal(await page.getByRole('button',{name:'Next',exact:true}).isDisabled(),true);
  const download=page.getByRole('button',{name:'Download table as CSV',exact:true});
  assert.equal(await download.innerText(),'');
  assert.equal(await download.locator('svg').count(),1);
  await page.close();
});
test('context column is pinned without consuming textual Conversation data',async()=>{
  const page=await browser.newPage();
  await page.route('http://table.test/**',route=>route.fulfill({contentType:'text/html',body:'<html><body></body></html>'}));
  await page.goto('http://table.test/context');
  await page.setContent('<section class="data-card"><div class="table-wrap"><table data-table-widget="context-test"><thead><tr><th>Product</th><th data-column="conversation">Conversation</th><th>Action</th></tr></thead><tbody><tr><td>Product A</td><td>Keep this text in exports</td><td><button class="collaboration-row-button" aria-label="Open chat">Chat</button></td></tr></tbody></table></div></section>');
  for(const file of ['table-preferences.js','table-widget.js','table-data-tools.js'])await page.addScriptTag({path:path.resolve('src/main/resources/static/js',file)});
  assert.equal(await page.locator('thead th').first().getAttribute('data-column'),'record-context');
  assert.equal(await page.locator('tbody td').first().locator('button').count(),1);
  assert.equal(await page.locator('[data-column=conversation]').last().innerText(),'Keep this text in exports');
  await page.locator('.table-filter-control summary').click();
  assert.equal(await page.getByRole('searchbox',{name:'Filter Conversation',exact:true}).count(),1);
  await page.close();
});
