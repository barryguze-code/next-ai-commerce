const {test}=require('node:test');
const assert=require('node:assert/strict');
const {chromium}=require('playwright');
test('SKU picture menu survives refreshed rows and respects read-only access',async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.TEST_BROWSER_CHANNEL?{channel:process.env.TEST_BROWSER_CHANNEL}:{})});
 try{
  const page=await browser.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));let requests=0;
  await page.route('**/*',route=>{if(route.request().method()==='POST'){requests++;assert.equal(route.request().headers()['x-csrf-token'],'test');return route.fulfill({json:{imageUrl:'/picture.png'}});}return route.fulfill({contentType:'text/html',body:'<div id="stream-parent"><div data-order-stream><div class="order-item"><div data-picture-actions data-item-id="item" data-seller-sku="sku" data-product-title="Test product" data-can-edit="true" data-mapping="MAPPED"><span>□</span></div></div></div></div><input id="buy-shipping-csrf" data-header="X-CSRF-TOKEN" value="test">'});});
  await page.goto('http://localhost/picture-test');
  await page.addScriptTag({path:'src/main/resources/static/js/order-pictures.js'});
  await page.getByRole('button',{name:'Edit SKU picture',exact:true}).click();
  await page.getByRole('button',{name:/Sync from Amazon/}).click();
  await page.waitForFunction(()=>document.querySelector('[data-picture-notice]')?.textContent.includes('updated'));
  assert.equal(requests,1);assert.equal(await page.locator('[data-picture-actions] > img').count(),1);
  await page.keyboard.press('Escape');assert.equal(await page.locator('.order-picture-menu').count(),0);
  await page.evaluate(()=>{document.querySelector('[data-order-stream]').innerHTML='<div class="order-item"><div data-picture-actions data-item-id="readonly" data-can-edit="false" data-product-title="Read only"></div></div>';});
  await page.getByRole('button',{name:'Actions',exact:true}).click();
  assert.equal(await page.getByRole('button',{name:/Upload from device/}).count(),0);
  assert.equal(await page.getByRole('button',{name:'Edit SKU picture',exact:true}).count(),0);
  assert.deepEqual(errors,[]);
 }finally{await browser.close();}
});
