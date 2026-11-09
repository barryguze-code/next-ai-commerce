const {test}=require('node:test');
const assert=require('node:assert/strict');
const {chromium}=require('playwright');
const path=require('node:path');
const fs=require('node:fs');
const template=fs.readFileSync('src/main/resources/templates/replenishment-draft.html','utf8');
const dialogs=template.slice(template.indexOf('<dialog id="rp-basket"'),template.indexOf('<th:block th:replace="~{fragments/inventory-history-panel'));
const item={id:'test',name:'Product',code:'123',vendor:'Vendor',dc:'41',pack:12,each:24,cost:1,currency:'USD'};
test('basket survives pagination and menus, isolates accounts, and case save stays in place',async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.TEST_BROWSER_CHANNEL?{channel:process.env.TEST_BROWSER_CHANNEL}:{})});
 try{
  const page=await browser.newPage();
  const script=fs.readFileSync(path.resolve('src/main/resources/static/js/replenishment-draft.js'),'utf8');
  await page.route('http://basket.test/**',async route=>{
   const url=new URL(route.request().url());
   if(url.pathname==='/save'){await route.fulfill({contentType:'application/json',body:JSON.stringify({pack:6,cases:4,each:24})});return;}
   const scope=url.searchParams.get('account')||'account-a:user-a';
   if(url.pathname.endsWith('/suggested-po')){await route.fulfill({json:{ready:true,items:route.request().headers()['x-workspace-account']==='account-b'?[]:[item]}});return;}
   await route.fulfill({contentType:'text/html',body:`<main data-basket-scope="${scope}"><a href="/menu">Menu</a><a href="/?page=2">Next</a><div style="height:900px"></div><table><thead><tr><th>Item</th></tr></thead><tbody><tr data-replenishment-row data-item="test" data-name="Product" data-pack="12" data-cases="2" data-cost="1"><td><input data-po-cases type="number" step="any" aria-label="PO cases"><small data-po-each></small><div class="rp-case-line"><small class="cell-note">24 each</small><details class="rp-case-edit" open><summary><span>12 / case</span></summary><form action="/save" method="post"><input name="units" value="12"><button type="submit">Save</button></form></details></div><button data-po-remove>Remove from PO</button></td></tr></tbody></table><button data-basket-open>Basket <span data-basket-count>0</span></button>${dialogs}</main><script>${script}</script>`});
  });
  await page.goto('http://basket.test/');await page.waitForFunction(()=>document.querySelector('[data-basket-count]').textContent==='1');
  await page.goto('http://basket.test/?page=2');assert.equal(await page.locator('[data-basket-count]').textContent(),'1');
  await page.getByRole('button',{name:'Save',exact:true}).scrollIntoViewIfNeeded();
  const before=await page.evaluate(()=>({url:location.href,y:scrollY}));
  await page.getByRole('button',{name:'Save',exact:true}).click();
  await page.getByText('Case size saved. Basket quantities are unchanged.',{exact:true}).waitFor();
  assert.equal(page.url(),before.url);assert.equal(await page.locator('[data-replenishment-row]').getAttribute('data-pack'),'6');
  assert.equal(await page.getByLabel('PO cases').inputValue(),'4');
  assert.ok(Math.abs((await page.evaluate(()=>scrollY))-before.y)<150);
  await page.goto('http://basket.test/menu');await page.goto('http://basket.test/?page=2');
  await page.getByRole('button',{name:'Basket 1',exact:true}).click();
  assert.equal(await page.getByLabel('Each for Product').inputValue(),'24');assert.equal(await page.getByLabel('Cases for Product').inputValue(),'4');
  await page.getByLabel('Each for Product').fill('30');await page.getByLabel('Each for Product').press('Tab');
  await page.reload();await page.getByRole('button',{name:'Basket 1',exact:true}).click();assert.equal(await page.getByLabel('Each for Product').inputValue(),'30');
  await page.goto('http://basket.test/?account=account-b:user-a');await page.waitForFunction(()=>sessionStorage.getItem('replenishment-basket:v1:account-b:user-a:suggested-initialized')==='1');assert.equal(await page.locator('[data-basket-count]').textContent(),'0');
  await page.goto('http://basket.test/');await page.getByRole('button',{name:'Basket 1',exact:true}).click();await page.getByRole('button',{name:'Remove Product'}).click();
  await page.reload();assert.equal(await page.locator('[data-basket-count]').textContent(),'0');
 }finally{await browser.close();}
});
test('basket preserves whole units, formats cases, calculates totals and keeps duplicate edits',async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.TEST_BROWSER_CHANNEL?{channel:process.env.TEST_BROWSER_CHANNEL}:{})});
 try{const page=await browser.newPage();
 await page.route('http://basket-values.test/**',route=>{if(new URL(route.request().url()).pathname.endsWith('/suggested-po'))return route.fulfill({json:{ready:true,items:[{...item,name:'Test product',cost:1.25}]}});return route.fulfill({contentType:'text/html',body:`<main data-basket-scope="values:user"><table><thead><tr><th>Item</th></tr></thead><tbody><tr data-replenishment-row data-item="test" data-name="Test product" data-code="123" data-vendor="Vendor" data-dc="41" data-pack="12" data-cost="1.25"><td><input data-po-cases type="number" step="any"></td></tr></tbody></table><button data-basket-open>Basket preview <span data-basket-count>0</span></button>${dialogs}</main><script>${fs.readFileSync(path.resolve('src/main/resources/static/js/replenishment-draft.js'),'utf8')}</script>`});});
 await page.goto('http://basket-values.test/');await page.waitForFunction(()=>document.querySelector('[data-basket-count]').textContent==='1');
 await page.getByRole('button',{name:'Basket preview 1'}).click();
 const cases=page.getByLabel('Cases for Test product'),each=page.getByLabel('Each for Test product');
 assert.equal(await cases.inputValue(),'2');assert.equal(await each.inputValue(),'24');
 assert.match(await page.locator('.rp-basket-total').textContent(),/\$30.00/);
 await each.fill('25');await each.press('Tab');assert.equal(await cases.inputValue(),'2.08');
 assert.match(await page.locator('.rp-basket-total').textContent(),/\$31.25/);
 await cases.fill('3');await cases.press('Tab');assert.equal(await each.inputValue(),'36');assert.equal(await cases.inputValue(),'3');
 await each.fill('2.5');await each.press('Tab');assert.equal(await each.evaluate(el=>el.validity.valid),false);
 await each.fill('12');await each.press('Tab');assert.equal(await cases.inputValue(),'1');
 await page.getByRole('button',{name:'Close',exact:true}).last().click();await page.reload();await page.getByRole('button',{name:'Basket preview 1'}).click();assert.equal(await page.getByLabel('Each for Test product').inputValue(),'12');await page.getByRole('button',{name:'Close',exact:true}).last().click();
 await page.getByRole('button',{name:'Basket preview 1'}).click();await page.getByRole('button',{name:'Remove Test product'}).click();
 assert.match(await page.locator('[data-basket-body]').textContent(),/empty/);
 }finally{await browser.close();}
});
