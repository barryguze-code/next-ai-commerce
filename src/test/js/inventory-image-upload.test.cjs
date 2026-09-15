const {test}=require('node:test');
const assert=require('node:assert/strict');
const {chromium}=require('playwright');
const path=require('node:path');
test('inventory details expose an item-scoped image picker and preserve CSRF',async()=>{
  const browser=await chromium.launch({headless:true,...(process.env.TEST_BROWSER_CHANNEL?{channel:process.env.TEST_BROWSER_CHANNEL}:{})});
  try{
    const page=await browser.newPage();
    await page.setContent(`<div class="history-summary"></div><div class="position-section-title"></div>
      <dialog id="inventory-history-dialog"><p id="history-subtitle"></p></dialog>
      <div class="inventory-row" data-item="first" data-available="3"><form class="product-image-upload" method="post" action="/app/catalog/products/first/image" enctype="multipart/form-data">
      <input type="hidden" name="_csrf" value="test-csrf"><input name="returnTo" value="/app/inventory" type="hidden">
      <label><span>Picture</span><input type="file" name="image" accept="image/png,image/jpeg,image/webp" onchange="this.form.requestSubmit()"></label></form></div>
      <div class="inventory-row" data-item="readonly" data-available="0"></div>`);
    await page.evaluate(()=>{window.openInventoryHistory=async()=>{};});
    await page.addScriptTag({path:path.resolve('src/main/resources/static/js/inventory-related-skus.js')});
    await page.evaluate(()=>openInventoryHistory(document.querySelector('.inventory-row')));
    await page.locator('#inventory-history-dialog').evaluate(d=>d.showModal());
    assert.equal(await page.getByRole('button',{name:'Upload picture',exact:true}).isVisible(),true);
    assert.equal(await page.locator('#history-image-upload form').getAttribute('action'),'/app/catalog/products/first/image');
    assert.equal(await page.locator('#history-image-upload input[name="_csrf"]').inputValue(),'test-csrf');
    const picker=page.waitForEvent('filechooser');
    await page.getByRole('button',{name:'Upload picture',exact:true}).click();await picker;
    await page.evaluate(()=>openInventoryHistory(document.querySelector('[data-item="readonly"]')));
    assert.equal(await page.locator('#history-image-upload').isVisible(),false);
  }finally{await browser.close();}
});
