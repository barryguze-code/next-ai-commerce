const {test,before,after}=require('node:test');
const assert=require('node:assert/strict'),fs=require('node:fs');
const {chromium}=require('playwright');
let browser;
before(async()=>{browser=await chromium.launch({headless:true,...(process.env.TEST_BROWSER_CHANNEL?{channel:process.env.TEST_BROWSER_CHANNEL}:{})});});
after(async()=>browser?.close());
for(const state of ['success','success-mobile-dark','success-mobile-light','failed','truncated','login','invalid-schema'])test('Marketplace typed CSV: '+state,async()=>{
 const page=await browser.newPage({viewport:{width:state.includes("mobile")?(state.endsWith("dark")?390:320):1440,height:900},colorScheme:state.endsWith("dark")?"dark":"light"});const requests=[];
 await page.route('**/*',async route=>{const u=new URL(route.request().url());requests.push({url:u.href,method:route.request().method()});
  if(u.pathname==='/app/marketplace-skus')return route.fulfill({contentType:'text/html',body:`<div class="data-card"><div data-column-control="marketplace-skus"></div><table data-table-widget="marketplace-skus" data-table-widget-ready="true"><thead><tr><th data-column="product-details">Product details</th><th data-column="status" hidden>Status</th><th data-column="profit" style="order:1">Profit</th></tr></thead><tbody><tr><td data-column="product-details">000147</td><td data-column="status">Active</td><td data-column="profit">Review costs</td></tr></tbody></table></div>`});
  assert.equal(u.pathname,'/app/marketplace-skus/export.csv');assert.equal(route.request().method(),'GET');assert.equal(u.searchParams.get('q'),'Tea');assert.equal(u.searchParams.get('status'),'ALL');assert.equal(u.searchParams.get('columns'),'product-details,profit');assert.equal(u.searchParams.get('page'),null);assert.equal(u.searchParams.get('size'),null);assert.equal(u.searchParams.get('sort'),'price');assert.equal(u.searchParams.get('direction'),'desc');
  if(state==='failed')return route.fulfill({status:400,contentType:'application/problem+json',body:JSON.stringify({detail:'More than 10,000 matching listings. Narrow the filters.'})});
  if(state==='login')return route.fulfill({contentType:'text/html',body:'Login'});
  const body='\uFEFF"Seller SKU","Estimated profit per listing unit"\r\n"000147","-2.50"\r\n';
  return route.fulfill({contentType:'text/csv;charset=UTF-8',headers:{'X-CSV-Rows':'1','X-CSV-Schema':state==='invalid-schema'?'wrong':'marketplace-skus-v1','Content-Length':String(Buffer.byteLength(body)+(state==='truncated'?5:0)),'Content-Disposition':'attachment; filename="marketplace-skus-20261019T120000Z.csv"'},body});
 });
 await page.goto('http://sku-csv.test/app/marketplace-skus?q=Tea&status=ALL&sort=price&direction=desc&page=3&size=25');
 await page.evaluate(()=>{window.downloads=[];HTMLAnchorElement.prototype.click=function(){window.downloads.push({name:this.download,href:this.href});};window.NextAiBackgroundJobs={start(){return{complete(message){window.progress=message},fail(message){window.progress=message}}}};});
 await page.addScriptTag({content:fs.readFileSync('src/main/resources/static/js/table-data-tools.js','utf8')});
 await page.locator('.table-export').click();
 await page.waitForFunction(()=>document.querySelector('.table-export')&&!document.querySelector('.table-export').disabled);
 const result=await page.evaluate(()=>({downloads:window.downloads,progress:window.progress}));
 if(state.startsWith('success')){assert.equal(result.downloads.length,1);assert.equal(result.downloads[0].name,'marketplace-skus-20261019T120000Z.csv');assert.match(result.progress,/CSV prepared: 1 rows. Download started/);}else{assert.equal(result.downloads.length,0);assert.match(result.progress,/Export failed:/);}
 assert.equal(requests.length,2);assert.ok(requests.every(r=>r.method==='GET'&&r.url.startsWith('http://sku-csv.test/')));await page.close();
});

test('collapsing every family preserves typed CSV request and canonical rows',async()=>{
 const page=await browser.newPage({viewport:{width:390,height:900}}),exports=[];
 try{
 await page.route('**/*',async route=>{const u=new URL(route.request().url());
  if(u.pathname==='/app/marketplace-skus')return route.fulfill({contentType:'text/html',body:'<main class="sku-workspace"><div class="data-card"><div data-column-control="marketplace-skus"></div><table id="marketplace-listings-table" class="sku-table" data-table-widget="marketplace-skus" data-table-widget-ready="true"><thead><tr><th data-column="product-details">Product details</th><th data-column="status">Status</th></tr></thead><tbody><tr data-family-heading="PARENT"><td colspan="2">Parent SKU PARENT · Parent ASIN P-ASIN</td></tr><tr data-sku="000A" data-family-parent="PARENT"><td data-column="product-details">000A</td><td>Active</td></tr><tr data-sku="000B" data-family-parent="PARENT"><td data-column="product-details">000B</td><td>Active</td></tr><tr data-sku="STANDALONE"><td data-column="product-details">STANDALONE</td><td>Active</td></tr></tbody></table></div></main>'});
  assert.equal(u.pathname,'/app/marketplace-skus/export.csv');exports.push(u.href);const body='\uFEFF"Seller SKU"\r\n"000A"\r\n"000B"\r\n"STANDALONE"\r\n';return route.fulfill({contentType:'text/csv;charset=UTF-8',headers:{'X-CSV-Rows':'3','Content-Disposition':'attachment; filename="marketplace-skus-20261022T220000Z.csv"','X-CSV-Schema':'marketplace-skus-v1','Content-Length':String(Buffer.byteLength(body))},body});
 });
 await page.goto('http://sku-csv.test/app/marketplace-skus?q=&status=ALL&f_condition=NEW&sort=price&direction=desc');await page.addStyleTag({path:'src/main/resources/static/css/marketplace-sku-responsive.css'});
 await page.evaluate(()=>{window.downloads=0;HTMLAnchorElement.prototype.click=function(){window.downloads++};});
 await page.addScriptTag({path:'src/main/resources/static/js/marketplace-sku-design.js'});await page.addScriptTag({path:'src/main/resources/static/js/table-data-tools.js'});
 await page.locator('.table-export').click();await page.waitForFunction(()=>window.downloads===1);
 await page.locator('.sku-family-toggle').click();assert.equal(await page.locator('tr[data-sku="000A"]').isVisible(),false);assert.equal(await page.locator('tr[data-sku]').count(),3);
 await page.locator('.table-export').click();await page.waitForFunction(()=>window.downloads===2);assert.equal(exports.length,2);assert.equal(exports[0],exports[1]);assert.match(exports[1],/f_condition=NEW/);assert.match(exports[1],/columns=product-details%2Cstatus/);
 }finally{await page.close();}
});
