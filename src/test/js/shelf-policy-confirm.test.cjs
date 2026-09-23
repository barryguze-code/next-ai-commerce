const {test}=require('node:test');
const assert=require('node:assert/strict');
const {chromium}=require('playwright');
test('shelf policy save shows live SKU count, cancellation and lookup failure never submit',async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.TEST_BROWSER_CHANNEL?{channel:process.env.TEST_BROWSER_CHANNEL}:{})});
 try{
  const page=await browser.newPage();let fail=false;
  await page.route('**/*',route=>route.request().url().includes('/policy/impact')?route.fulfill(fail?{status:500,body:''}:{json:{skuCount:42}}):route.fulfill({contentType:'text/html',body:'<div id="shelf-policy-dialog"><form><div class="dialog-body"></div><button type="submit">Save rules</button></form></div>'}));
  await page.goto('http://localhost/policy-test');
  await page.evaluate(()=>{window.requests=[];window.accept=false;window.saved=0;window.NextAiConfirm=async options=>{window.requests.push(options);return window.accept;};});
  await page.addScriptTag({path:'src/main/resources/static/js/shelf-policy-confirm.js'});
  await page.evaluate(()=>document.querySelector('form').addEventListener('submit',e=>{if(!e.defaultPrevented)window.saved++;e.preventDefault();}));
  await page.getByRole('button',{name:'Save rules'}).click();await page.waitForFunction(()=>window.requests.length===1);
  assert.match(await page.evaluate(()=>window.requests[0].message),/42 mapped SKUs/);assert.equal(await page.evaluate(()=>window.saved),0);
  fail=true;await page.getByRole('button',{name:'Save rules'}).click();await page.getByRole('alert').waitFor();assert.equal(await page.evaluate(()=>window.saved),0);
  fail=false;await page.evaluate(()=>window.accept=true);await page.getByRole('button',{name:'Save rules'}).click();await page.waitForFunction(()=>window.requests.length===2);
  await page.waitForFunction(()=>window.saved===1);
 }finally{await browser.close();}
});
