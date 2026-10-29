const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
const {chromium}=require('playwright');
test('Shopify cost review and Amazon calculator share a dialog without carrying hidden fields or write behavior',async()=>{
 const browser=await chromium.launch({headless:true,channel:'chrome'});
 try{
  const page=await browser.newPage({viewport:{width:1280,height:850}}),errors=[],requests=[];
  const fragment=fs.readFileSync('src/main/resources/templates/fragments/profit.html','utf8');
  const dialog=fragment.slice(fragment.indexOf('<dialog'),fragment.indexOf('</dialog>')+9).replace('class="invite-dialog profit-dialog"','class="invite-dialog profit-dialog" data-can-edit="true"');
  const shopify={kind:'SHOPIFY_ORDER',key:'S',title:'#123',currency:'USD',note:'Saved UPS box · planning estimate, not a live carrier quote. Fees unavailable.',lines:[{sku:'S',title:'Product',quantity:6,unitPrice:20,productCost:3}],packages:[{description:'UPS box',amount:14.20}],totals:{revenue:120,productCost:18,shippingCost:14.20,profit:null}};
  const amazon={kind:'ORDER',key:'A',title:'Amazon',currency:'USD',date:'2026-10-29',note:'Amazon estimate',lines:[{sku:'A',title:'Other product',quantity:1,unitPrice:30,productCost:4,otherCost:1}],packages:[{description:'UPS',amount:14.20}],totals:{revenue:30,productCost:4,referralFee:4.5,otherCost:1,shippingCost:14.20,profit:6.30}};
  page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/*',route=>{const url=new URL(route.request().url());requests.push({path:url.pathname,method:route.request().method()});if(url.pathname==='/app/inventory/profit')return route.fulfill({json:url.searchParams.get('kind')==='SHOPIFY_ORDER'?{S:shopify}:{A:amazon}});if(url.pathname.endsWith('/rates')||url.pathname.endsWith('/defaults'))return route.fulfill({json:[]});if(url.pathname.endsWith('/price-support'))return route.fulfill({json:{enabled:false}});return route.fulfill({contentType:'text/html',body:`<button id="shopify" data-profit-kind="SHOPIFY_ORDER" data-profit-key="S">Review costs</button><button id="amazon" data-profit-kind="ORDER" data-profit-key="A">Review costs</button>${dialog}`});});
  await page.goto('http://local.test/review');await page.addStyleTag({path:'src/main/resources/static/css/app.css'});await page.addStyleTag({path:'src/main/resources/static/css/profit.css'});await page.addScriptTag({path:'src/main/resources/static/js/profit.js'});
  await page.locator('#shopify .profit-calculator-art').waitFor();await page.locator('#shopify').click();await page.locator('#profit-dialog[open]').waitFor();
  assert.equal(await page.locator('[data-profit-save]').isVisible(),false);assert.equal(await page.locator('.profit-item-defaults').isVisible(),false);assert.equal(await page.locator('.profit-price').getAttribute('readonly'),'');assert.match(await page.locator('[data-profit-totals]').innerText(),/Mapped product cost\s+\$18\.00/);assert.match(await page.locator('[data-profit-totals]').innerText(),/Net profit\s+Unavailable/);
  await page.locator('[data-profit-close]').click();await page.locator('#amazon').click();await page.locator('#profit-dialog[open]').waitFor();
  assert.equal(await page.locator('[data-profit-save]').isVisible(),true);assert.equal(await page.locator('.profit-item-defaults').isVisible(),true);assert.equal(await page.locator('.profit-packages').isVisible(),true);assert.equal(await page.locator('.profit-price').getAttribute('readonly'),null);assert.equal(await page.locator('.profit-price').inputValue(),'30.00');assert.doesNotMatch(await page.locator('[data-profit-lines]').innerText(),/Mapped cost \/ unit/);
  assert.ok(requests.every(r=>r.method==='GET'));assert.deepEqual(errors,[]);
 }finally{await browser.close();}
});
for(const kind of ['SHOPIFY_ORDER','SHOPIFY_SKU'])test(`${kind} Other cost saves explicit zero by scoped variant identity and refreshes the estimate`,async()=>{
 const browser=await chromium.launch({headless:true,channel:'chrome'});
 try{
  const page=await browser.newPage(),writes=[];let amount=1,reads=0;
  const fragment=fs.readFileSync('src/main/resources/templates/fragments/profit.html','utf8');
  const dialog=fragment.slice(fragment.indexOf('<dialog'),fragment.indexOf('</dialog>')+9).replace('class="invite-dialog profit-dialog"','class="invite-dialog profit-dialog" data-can-edit="true"');
  const variant='00000000-0000-0000-0000-000000000002',connection='00000000-0000-0000-0000-000000000003';
  await page.route('**/*',route=>{const url=new URL(route.request().url());
   if(url.pathname.endsWith('/shopify-other-cost')){const body=route.request().postDataJSON();writes.push(body);amount=body.changes[0].amount;return route.fulfill({json:{message:'Saved'}});}
   if(url.pathname==='/app/inventory/profit'){reads++;return route.fulfill({json:{S:{kind,key:'S',title:'#123',currency:'USD',lines:[{sku:'same sku',variantId:variant,quantity:1,unitPrice:20,productCost:3,otherCost:amount}],packages:[],totals:{revenue:20,productCost:3,shippingCost:kind==='SHOPIFY_SKU'?null:5,otherCost:amount,profit:kind==='SHOPIFY_SKU'?null:12-amount}}}});}
   return route.fulfill({contentType:'text/html',body:`<button id="shopify" data-profit-kind="${kind}" data-profit-key="S" data-profit-connection="${connection}">Costs</button>${dialog}`});
  });
  await page.goto('http://local.test/review');await page.addStyleTag({path:'src/main/resources/static/css/profit.css'});await page.addScriptTag({path:'src/main/resources/static/js/profit.js'});
  await page.locator('#shopify .profit-calculator-art').waitFor();await page.locator('#shopify').click();
  await page.locator('#profit-dialog[open]').waitFor();if(kind==='SHOPIFY_SKU'){assert.match(await page.locator('[data-profit-currency]').innerText(),/Listing cost estimate/);assert.equal(await page.locator('.profit-price').getAttribute('readonly'),'');assert.equal(await page.locator('.profit-packages').isVisible(),false);assert.match(await page.locator('[data-profit-totals]').innerText(),/Estimated shipping\s+Unavailable/);}await page.locator('.profit-other').fill('0');await page.locator('[data-profit-save]').click();
  await page.locator('#profit-dialog').waitFor({state:'hidden'});
  assert.deepEqual(writes,[{connection,currency:'USD',changes:[{variant,amount:0}]}]);assert.ok(reads>=2);
 }finally{await browser.close();}
});
