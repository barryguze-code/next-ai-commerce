const {test}=require('node:test');
const assert=require('node:assert/strict');
const {chromium}=require('playwright');
const path=require('node:path');
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
