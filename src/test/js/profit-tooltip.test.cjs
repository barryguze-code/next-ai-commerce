const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const {chromium}=require('playwright');
test('cached fee tooltip is keyboard accessible, compact, and Buy Box has no calculator',async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.TEST_BROWSER_CHANNEL?{channel:process.env.TEST_BROWSER_CHANNEL}:{})});
 try{
  const page=await browser.newPage({viewport:{width:1000,height:700}}),errors=[];let requests=0;
  page.on('pageerror',e=>errors.push(e.message));
  const view={kind:'ORDER',currency:'USD',lines:[{sku:'A',quantity:1,unitPrice:25,productCost:4.82,otherCost:1}],packages:[{amount:9.99}],totals:{shippingCost:9.99,profit:5.44}};
  const fragment=fs.readFileSync('src/main/resources/templates/fragments/profit.html','utf8');
  const dialog=fragment.slice(fragment.indexOf('<dialog'),fragment.indexOf('</dialog>')+9);
  await page.route('**/*',route=>{
   if(route.request().url().includes('/app/inventory/profit')){requests++;return route.fulfill({json:{ORDER:view}});}
   return route.fulfill({contentType:'text/html',body:`<div class="order-item"><button id="sale" data-profit-kind="ORDER" data-profit-key="ORDER" data-profit-sku="A" data-profit-price-link>$25.00</button><div class="item-buy-box"><button id="buybox" data-profit-kind="ORDER" data-profit-key="ORDER" data-profit-sku="A" data-profit-price-link>$23.00</button></div><button id="profit" data-profit-kind="ORDER" data-profit-key="ORDER" data-profit-sku="A">Review</button></div>${dialog}`});
  });
  await page.goto('http://localhost/tooltip-test');
  await page.addStyleTag({path:'src/main/resources/static/css/profit.css'});
  await page.addScriptTag({path:'src/main/resources/static/js/profit.js'});
  await page.locator('#profit .profit-calculator-art').waitFor();
  assert.equal(await page.locator('#buybox .profit-calculator-art').count(),0);
  await page.locator('#sale').focus();
  const tooltip=page.getByRole('tooltip');await tooltip.waitFor();
  assert.match(await tooltip.innerText(),/Product cost\s+\$4\.82/);
  assert.match(await tooltip.innerText(),/Shipping\s+\$9\.99/);
  assert.equal(await page.locator('#sale').getAttribute('aria-describedby'),'profit-fee-tooltip');
  const prior=requests;await page.locator('#profit').hover();await tooltip.waitFor();assert.equal(requests,prior);
  const widths=await tooltip.locator('dd').evaluateAll(nodes=>nodes.map(n=>({height:n.getBoundingClientRect().height,whiteSpace:getComputedStyle(n).whiteSpace})));
  assert.ok(widths.every(n=>n.whiteSpace==='nowrap'&&n.height<25));
  await page.keyboard.press('Escape');assert.equal(await tooltip.isVisible(),false);
  assert.deepEqual(errors,[]);
 }finally{await browser.close();}
});
