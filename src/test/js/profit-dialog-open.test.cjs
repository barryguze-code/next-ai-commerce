const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),{chromium}=require('playwright');
for(const kind of ['SHOPIFY_ORDER','SHOPIFY_SKU','ORDER','SKU'])test(`${kind} opens shared template immediately and remains closable on failed detail load`,async()=>{
 const browser=await chromium.launch();try{
 const page=await browser.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
 const fragment=fs.readFileSync('src/main/resources/templates/fragments/profit.html','utf8');
 const dialog=fragment.slice(fragment.indexOf('<dialog'),fragment.indexOf('</dialog>')+9).replace('class="invite-dialog profit-dialog"','class="invite-dialog profit-dialog" data-can-edit="true"');
 let release;const response=new Promise(resolve=>release=resolve);
 await page.route('**/app/inventory/profit?*',async route=>{await response;await route.fulfill({status:503,json:{message:'Temporarily unavailable'}})});
 await page.route('http://local.test/review',route=>route.fulfill({contentType:'text/html',body:`<button id="open" data-profit-kind="${kind}" data-profit-key="S">Review costs</button>${dialog}`}));
 await page.goto('http://local.test/review');await page.addScriptTag({path:'src/main/resources/static/js/profit.js'});
 await page.locator('#open').click();await page.waitForTimeout(30);assert.deepEqual(errors,[]);
 assert.equal(await page.locator('#profit-dialog').evaluate(d=>d.open),true);assert.match(await page.locator('[data-profit-feedback]').innerText(),/Loading/);assert.equal(await page.locator('[data-profit-save]').isDisabled(),true);
 release();await page.getByText('Could not load cost details.',{exact:false}).waitFor({timeout:2000});await page.locator('[data-profit-close]').click();assert.equal(await page.locator('#profit-dialog').evaluate(d=>d.open),false);assert.deepEqual(errors,[]);
 }finally{await browser.close();}
});

for(const fba of [false,true])test(`Replenishment ${fba?'FBA':'FBM'} SKU renders cost details with the shared package-manager template`,async()=>{
 const browser=await chromium.launch();try{
 const page=await browser.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
 const fragment=fs.readFileSync('src/main/resources/templates/fragments/profit.html','utf8'),dialog=fragment.slice(fragment.indexOf('<dialog'),fragment.indexOf('</dialog>')+9);
 const view={kind:'SKU',key:'S',title:'Replenishment item',currency:'USD',fba,date:'2026-11-17',note:'Estimated costs',lines:[{sku:'S',title:'Item',quantity:1,unitPrice:30,productCost:4,otherCost:fba?0:1}],packages:[],totals:{revenue:30,productCost:4,otherCost:fba?0:1,profit:null}};
 await page.route('http://local.test/**',route=>{const u=new URL(route.request().url());if(u.pathname==='/app/inventory/profit')return route.fulfill({json:{S:view}});if(u.pathname.endsWith('/price-support'))return route.fulfill({json:{enabled:false}});if(u.pathname.startsWith('/app/inventory/profit/'))return route.fulfill({json:[]});return route.fulfill({contentType:'text/html',body:`<button id="open" data-profit-kind="SKU" data-profit-key="S">Review costs</button>${dialog}`})});
 await page.goto('http://local.test/review');await page.addScriptTag({path:'src/main/resources/static/js/profit.js'});await page.locator('#open').click();await page.locator('.profit-price').waitFor();assert.equal(await page.locator('#profit-dialog').evaluate(d=>d.open),true);assert.equal(await page.locator('[data-profit-subtitle]').innerText(),'Replenishment item');assert.match(await page.locator('[data-profit-totals]').innerText(),/Product cost/);assert.deepEqual(errors,[]);await page.locator('[data-profit-close]').click();
 }finally{await browser.close()}
});
