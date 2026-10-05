const {test}=require('node:test');
const assert=require('node:assert/strict');
const {chromium}=require('playwright');
const path=require('node:path');
const fs=require('node:fs');
test('basket survives pagination and menus, isolates accounts, and case save stays in place',async()=>{
 const browser=await chromium.launch({headless:true});
 try{
  const page=await browser.newPage();
  const script=fs.readFileSync(path.resolve('src/main/resources/static/js/replenishment-draft.js'),'utf8');
  await page.route('http://basket.test/**',async route=>{
   const url=new URL(route.request().url());
   if(url.pathname==='/save'){await route.fulfill({contentType:'application/json',body:JSON.stringify({pack:6,cases:4,each:24})});return;}
   const scope=url.searchParams.get('account')||'account-a:user-a';
   await route.fulfill({contentType:'text/html',body:`<main data-basket-scope="${scope}"><a href="/menu">Menu</a><a href="/?page=2">Next</a><div style="height:900px"></div><table><thead><tr><th>Item</th></tr></thead><tbody><tr data-replenishment-row data-item="test" data-name="Product" data-pack="12" data-cases="2" data-cost="1"><td><strong>2 cases</strong><div class="rp-case-line"><small class="cell-note">24 each</small><details class="rp-case-edit" open><summary><span>12 / case</span></summary><form action="/save" method="post"><input name="units" value="12"><button type="submit">Save</button></form></details></div><button data-add-basket>Add</button></td></tr></tbody></table><button data-basket-open>Basket <span data-basket-count>0</span></button><dialog id="rp-basket"><div data-basket-body></div><button data-po-preview>Preview</button><button data-close-dialog>Close</button></dialog></main><script>${script}</script>`});
  });
  await page.goto('http://basket.test/');await page.getByRole('button',{name:'Add',exact:true}).click();
  await page.goto('http://basket.test/?page=2');assert.equal(await page.locator('[data-basket-count]').textContent(),'1');
  await page.getByRole('button',{name:'Save',exact:true}).scrollIntoViewIfNeeded();
  const before=await page.evaluate(()=>({url:location.href,y:scrollY}));
  await page.getByRole('button',{name:'Save',exact:true}).click();
  await page.getByText('Case size saved. Basket quantities are unchanged.',{exact:true}).waitFor();
  assert.equal(page.url(),before.url);assert.equal(await page.locator('[data-replenishment-row]').getAttribute('data-pack'),'6');
  assert.equal(await page.locator('td strong').textContent(),'4 cases');
  assert.ok(Math.abs((await page.evaluate(()=>scrollY))-before.y)<150);
  await page.goto('http://basket.test/menu');await page.goto('http://basket.test/?page=2');
  await page.getByRole('button',{name:'Basket 1',exact:true}).click();
  assert.equal(await page.getByLabel('Each for Product').inputValue(),'24');assert.equal(await page.getByLabel('Cases for Product').inputValue(),'4');
  await page.getByLabel('Each for Product').fill('30');await page.getByLabel('Each for Product').press('Tab');
  await page.reload();await page.getByRole('button',{name:'Basket 1',exact:true}).click();assert.equal(await page.getByLabel('Each for Product').inputValue(),'30');
  await page.goto('http://basket.test/?account=account-b:user-a');assert.equal(await page.locator('[data-basket-count]').textContent(),'0');
  await page.goto('http://basket.test/');await page.getByRole('button',{name:'Basket 1',exact:true}).click();await page.getByRole('button',{name:'Remove Product'}).click();
  await page.reload();assert.equal(await page.locator('[data-basket-count]').textContent(),'0');
 }finally{await browser.close();}
});
test('basket preserves whole units, formats cases, calculates totals and keeps duplicate edits',async()=>{
 const browser=await chromium.launch({headless:true});
 try{const page=await browser.newPage();
 await page.setContent(`<table><thead><tr><th>Item</th></tr></thead><tbody><tr data-replenishment-row data-item="test" data-name="Test product" data-code="123" data-vendor="Vendor" data-dc="41" data-pack="12" data-cases="2" data-cost="1.25" data-image="data:image/gif;base64,R0lGODlhAQABAAAAACw="><td><button data-add-basket>Add to basket</button></td></tr></tbody></table><button data-basket-open>Basket preview <span data-basket-count>0</span></button><dialog id="rp-basket"><div data-basket-body></div><button data-po-preview>Review by vendor</button><button data-close-dialog>Close</button></dialog>`);
 await page.addScriptTag({path:path.resolve('src/main/resources/static/js/replenishment-draft.js')});
 await page.getByRole('button',{name:'Add to basket',exact:true}).click();
 assert.match(await page.getByRole('status').textContent(),/Added to basket.*Test product.*24 each · 2 cases/);
 await page.getByRole('button',{name:'Basket preview 1'}).click();
 const cases=page.getByLabel('Cases for Test product'),each=page.getByLabel('Each for Test product');
 assert.equal(await cases.inputValue(),'2');assert.equal(await each.inputValue(),'24');
 assert.match(await page.locator('.rp-basket-total').textContent(),/\$30.00/);
 await each.fill('25');await each.press('Tab');assert.equal(await cases.inputValue(),'2.08');
 assert.match(await page.locator('.rp-basket-total').textContent(),/\$31.25/);
 await cases.fill('3');await cases.press('Tab');assert.equal(await each.inputValue(),'36');assert.equal(await cases.inputValue(),'3');
 await each.fill('2.5');await each.press('Tab');assert.equal(await each.evaluate(el=>el.validity.valid),false);
 await each.fill('12');await each.press('Tab');assert.equal(await cases.inputValue(),'1');
 await page.getByRole('button',{name:'Close',exact:true}).click();await page.getByRole('button',{name:'Add to basket',exact:true}).click();
 assert.match(await page.getByRole('status').textContent(),/Already in basket.*12 each · 1 cases/);
 await page.getByRole('button',{name:'Basket preview 1'}).click();await page.getByRole('button',{name:'Remove Test product'}).click();
 assert.match(await page.locator('[data-basket-body]').textContent(),/empty/);
 }finally{await browser.close();}
});
