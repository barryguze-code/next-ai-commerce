const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
const {chromium}=require('playwright');
test('missing FBA costs wrap readably and open costs without requiring FBM rates',async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.TEST_BROWSER_CHANNEL?{channel:process.env.TEST_BROWSER_CHANNEL}:{})});
 try{
 const page=await browser.newPage({viewport:{width:1280,height:800}});
 const fragment=fs.readFileSync('src/main/resources/templates/fragments/profit.html','utf8');
 const dialog=fragment.slice(fragment.indexOf('<dialog'),fragment.indexOf('</dialog>')+9);
 const view={kind:'ORDER',key:'A',currency:'USD',fba:true,lines:[{sku:'A',quantity:1,otherCost:1}],packages:[],totals:{}};
 await page.route('**/*',route=>{
  const url=route.request().url();
  if(url.includes('/rates'))return route.fulfill({status:503,json:{message:'FBM rates unavailable'}});
  if(url.includes('/defaults'))return route.fulfill({json:[]});
  if(url.includes('/price-support'))return route.fulfill({json:{enabled:false}});
  if(url.includes('/app/inventory/profit'))return route.fulfill({json:{A:view}});
  return route.fulfill({contentType:'text/html',body:`<main class="orders-workspace"><div class="item-profit"><button id="cost" class="profit-open" data-profit-kind="ORDER" data-profit-key="A" data-profit-sku="A">Review costs</button></div></main>${dialog}`});
 });
 await page.goto('http://localhost/fba-cost-test');
 await page.addStyleTag({path:'src/main/resources/static/css/profit.css'});
 await page.addScriptTag({path:'src/main/resources/static/js/profit.js'});
 await page.locator('#cost .profit-calculator-art').waitFor();
 await page.locator('#cost').hover();await page.getByRole('tooltip').waitFor();
 const metrics=await page.getByRole('tooltip').evaluate(t=>({width:t.offsetWidth,overflow:t.scrollWidth>t.clientWidth,labels:[...t.querySelectorAll('dt')].map(n=>n.offsetWidth)}));
 assert.ok(metrics.labels.every(w=>w>=100),'cost labels must not collapse into single-character columns');
 assert.equal(metrics.overflow,false);
 assert.ok(await page.locator('#cost>span').evaluate(n=>parseFloat(getComputedStyle(n).fontSize)<=14));
 await page.locator('#cost').click();
 await page.locator('#profit-dialog[open]').waitFor({timeout:2000});
 assert.equal(await page.getByText('Retry profit',{exact:true}).count(),0);
 }finally{await browser.close();}
});
