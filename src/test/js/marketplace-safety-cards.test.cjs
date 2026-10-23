const {test,before,after}=require('node:test');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require('playwright');
const base=path.resolve('src/main/resources/static'),out='/tmp/sku008-010-review';let browser;
before(async()=>{assert.ok(fs.existsSync(out+'/marketplace.html'),'Run Maven verification first to render the synthetic Marketplace safety fixture');browser=await chromium.launch({headless:true,channel:process.env.TEST_BROWSER_CHANNEL||'chrome'});});
after(async()=>browser?.close());
for(const width of [320,390,760,761])for(const theme of ['light','dark'])test(`sort header containment ${width} ${theme}`,async()=>{
 const {page,errors}=await fixture(width,theme,true);
 const links=page.locator('.sku-table>thead a');
 const targets=await links.evaluateAll(nodes=>nodes.filter(e=>e.getBoundingClientRect().width>0).map(e=>({text:e.textContent.trim(),href:e.getAttribute('href')})));
 assert.ok(targets.length>0);
 const artifactDir='/tmp/sku010-sort-review';fs.mkdirSync(artifactDir,{recursive:true});
 await page.locator('.sku-table>thead').scrollIntoViewIfNeeded();
 await page.screenshot({path:`${artifactDir}/${width}-${theme}.png`});
 if(width<=760){
  const clippedLinks=()=>links.evaluateAll(nodes=>nodes.filter(e=>e.getBoundingClientRect().width>0).map(e=>{
   const r=e.getBoundingClientRect(),head=e.closest('thead').getBoundingClientRect();
   return {text:e.textContent.trim(),left:r.left,right:r.right,headLeft:head.left,headRight:head.right,height:r.height};
  }).filter(r=>r.left<r.headLeft-1||r.right>r.headRight+1||r.height<44));
  assert.deepEqual(await clippedLinks(),[],'every visible sort link must fit the mobile sort panel and retain its touch target');
  await links.first().focus();assert.equal(await links.first().evaluate(e=>e===document.activeElement),true);
  await page.keyboard.press('Tab');assert.equal(await links.nth(1).evaluate(e=>e===document.activeElement),true);
  const original=await links.nth(1).textContent();
  await links.nth(1).evaluate(e=>e.textContent='Long relationship classification and fulfillment availability label');
  assert.deepEqual(await clippedLinks(),[],'long sort labels must wrap inside the panel');
  await page.screenshot({path:`${artifactDir}/${width}-${theme}-long.png`});
  await links.nth(1).evaluate((e,text)=>e.textContent=text,original);
 }
 assert.deepEqual(await links.evaluateAll(nodes=>nodes.filter(e=>e.getBoundingClientRect().width>0).map(e=>({text:e.textContent.trim(),href:e.getAttribute('href')}))),targets);
 assert.deepEqual(errors,[]);await page.close();
});
async function fixture(width,theme,optional=false,height=900){
 const page=await browser.newPage({viewport:{width,height},colorScheme:theme});
 await page.context().addCookies([{name:'nextai_theme',value:theme,url:'http://marketplace-fixture.test'}]);page.setDefaultTimeout(5000);
 if(optional)await page.addInitScript(()=>localStorage.setItem('nextai.table.preferences.marketplace-skus',JSON.stringify({schema:2,layoutVersion:'orders-aligned-20261005',order:['record-context','product-details','status','price-shipping','shipping-template','condition','fnsku','inbound','reserved','unfulfillable','profit','available','4w-sales'],visibility:Object.fromEntries(['record-context','product-details','status','price-shipping','shipping-template','condition','fnsku','inbound','reserved','unfulfillable','profit','available','4w-sales'].map(k=>[k,true]))})));
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/*',route=>{const u=new URL(route.request().url());
  if(u.hostname!=='marketplace-fixture.test')return route.abort();
  if(/^\/(css|js|images)\//.test(u.pathname)){const file=path.join(base,u.pathname);return fs.existsSync(file)?route.fulfill({path:file}):route.fulfill({status:404,body:''});}
  if(u.pathname==='/app/marketplace-skus')return route.fulfill({contentType:'text/html',body:fs.readFileSync(out+'/marketplace.html','utf8').replace('<html','<html data-theme="'+theme+'"')});
  if(u.pathname==='/app/inventory/profit')return route.fulfill({contentType:'application/json',body:'{}'});
  if(u.pathname.startsWith('/app/table-filters/'))return route.fulfill({contentType:'application/json',body:'[]'});
  if(u.pathname.endsWith('/components'))return route.fulfill({contentType:'application/json',body:'[]'});
  return route.fulfill({contentType:'application/json',body:'{}'});
 });
 await page.goto('http://marketplace-fixture.test/app/marketplace-skus');await page.waitForTimeout(200);
 return {page,errors};
}
for(const width of [320,390,430,760,761,1440])for(const theme of ['light','dark'])test(`canonical Marketplace safety cards ${width} ${theme}`,async()=>{
 const {page,errors}=await fixture(width,theme);
 const main=page.locator('.sku-table');assert.equal(await main.locator(':scope > tbody > tr[data-sku]').count(),6);
 assert.equal(await main.locator('[data-profit-key="PARENT"]').count(),0);assert.equal(await main.locator('#mapping-dialog-main-0').count(),0);
 await main.locator('.sku-family>summary').click();await page.waitForTimeout(100);
 assert.equal(await main.locator(':scope > tbody > tr[data-sku]').count(),6);
 assert.equal(await page.locator('.sku-family-table>tbody>tr').count(),2);
 assert.equal(await page.locator('.sku-family-table .sku-open-actions').count(),2);
 const child=page.locator('.sku-family-table>tbody>tr').first();
 await child.locator('.sku-open-actions').click();await page.waitForTimeout(30);
 assert.equal(await page.locator('.sku-actions-dialog').count(),1);
 await page.keyboard.press('Escape');assert.equal(await page.locator('.sku-actions-dialog').count(),0);
 assert.equal(await child.locator('.sku-open-actions').evaluate(e=>e===document.activeElement),true);
 // Optional preference columns remain under the same stable keys and reorder with the main header.
 await page.evaluate(()=>{
  const t=document.querySelector('.sku-table');const h=t.tHead.rows[0].querySelector('[data-column="fnsku"]');h.hidden=false;h.style.display='table-cell';t.tHead.rows[0].prepend(h);
  for(const r of t.tBodies[0].rows){if(r.dataset.sku){const c=r.querySelector(':scope > [data-column="fnsku"]');if(c){c.hidden=false;c.style.display='table-cell';r.prepend(c);}}}
 });await page.waitForTimeout(40);
 assert.equal(await page.locator('.sku-family-table thead th').first().getAttribute('data-column'),'fnsku');
 const region=page.locator('.sku-family-body');await region.focus();await page.keyboard.press('End');
 const later=page.locator('.sku-family-table>tbody>tr').last();await later.locator('.sku-open-actions').focus();
 assert.equal(await later.locator('.sku-open-actions').evaluate(e=>e===document.activeElement),true);
 assert.equal(await later.locator('.table-overview-rail .collaboration-row-button').count(),1);
 if(width<=390)assert.ok(await region.evaluate(e=>e.scrollTop>0),'later child reachable by focus/scroll');
 if(width<=390){await page.screenshot({path:`${out}/${width}-${theme}-later-child-viewport.png`});await later.locator('[data-label="Available"]').scrollIntoViewIfNeeded();await page.screenshot({path:`${out}/${width}-${theme}-later-child-metrics-viewport.png`});}
 if(width===1440){const far=page.locator('.sku-family-table>tbody>tr').last().locator('[data-column="4w-sales"]');await far.scrollIntoViewIfNeeded();const box=await far.boundingBox();assert.ok(box.x>=-1&&box.x+box.width<=width+1,JSON.stringify(box));await page.screenshot({path:`${out}/${width}-${theme}-family-right-viewport.png`});}
 if(theme==='dark'){
  const contrasts=await page.locator('.fba-stock-totals dd').evaluateAll(nodes=>nodes.filter(e=>e.getBoundingClientRect().width>0).map(e=>{
   const rgb=s=>(s.match(/[\d.]+/g)||[]).map(Number),fg=rgb(getComputedStyle(e).color);let bg=[255,255,255],chain=[];
   for(let p=e;p;p=p.parentElement)chain.push(rgb(getComputedStyle(p).backgroundColor));
   for(const c of chain.reverse()){const a=c.length>3?c[3]:1;if(c.length>=3)bg=bg.map((v,i)=>c[i]*a+v*(1-a));}
   const lum=c=>c.slice(0,3).map(v=>{v/=255;return v<=.04045?v/12.92:Math.pow((v+.055)/1.055,2.4);}).reduce((a,v,i)=>a+v*[.2126,.7152,.0722][i],0);
   const a=lum(fg),b=lum(bg);return(Math.max(a,b)+.05)/(Math.min(a,b)+.05);
  }));assert.ok(contrasts.length>0);for(const value of contrasts)assert.ok(value>=4.5,'inventory number contrast '+value);
 }

 const metrics=await page.evaluate(()=>({page:document.documentElement.scrollWidth,viewport:innerWidth,sorts:[...document.querySelectorAll('.sku-table>thead a')].filter(e=>getComputedStyle(e).display!=='none').length}));
 assert.ok(metrics.page<=metrics.viewport+1,JSON.stringify(metrics));assert.ok(metrics.sorts>0);
 if(width<=760){const boxes=await page.evaluate(()=>[...document.querySelectorAll('.sku-table>tbody>tr[data-sku]')].flatMap(r=>[r,...r.cells]).filter(e=>getComputedStyle(e).display!=='none').map(e=>({key:e.dataset.column,x:e.getBoundingClientRect().x,right:e.getBoundingClientRect().right})));for(const b of boxes)assert.ok(b.x>=-1&&b.right<=width+1,JSON.stringify(b));}
 assert.deepEqual(errors,[]);
 fs.writeFileSync(`${out}/${width}-${theme}-layout.json`,JSON.stringify(await page.evaluate(()=>({scrollX,main:document.querySelector('.sku-table').getBoundingClientRect().toJSON(),rows:[...document.querySelector('.sku-table').tBodies[0].rows].slice(0,3).map(r=>({rect:r.getBoundingClientRect().toJSON(),cells:[...r.cells].map(c=>({key:c.dataset.column,hidden:c.hidden,rect:c.getBoundingClientRect().toJSON(),display:getComputedStyle(c).display,position:getComputedStyle(c).position,flex:getComputedStyle(c).flex,text:c.textContent.trim().slice(0,50)}))}))})),null,2));
 await page.evaluate(()=>scrollTo(0,0));
 await page.screenshot({path:`${out}/${width}-${theme}.png`,fullPage:true});
 const disclosure=main.locator('.sku-family'),summary=disclosure.locator(':scope > summary');
 await summary.click();assert.equal(await disclosure.evaluate(e=>e.open),false);
 // Native details dispatches toggle asynchronously; wait for the rendered state, not 20ms.
 await page.locator('[data-family-disclosure][hidden]').waitFor({state:'attached'});
 await summary.press('Space');await page.locator('[data-family-disclosure]:not([hidden])').waitFor({state:'visible'});
 assert.equal(await page.locator('[data-family-disclosure]').count(),1);
 assert.equal(await page.locator('.sku-family-table>tbody>tr').count(),2);
 await summary.press('Enter');await page.locator('[data-family-disclosure][hidden]').waitFor({state:'attached'});
 assert.equal(await summary.evaluate(e=>e===document.activeElement),true);
 await page.close();
});

test('all selected optional fields survive short mobile cards and enlarged text',async()=>{
 const {page,errors}=await fixture(390,'dark',true,480);
 await page.locator('.sku-family>summary').click();await page.waitForTimeout(50);
 for(const key of ['shipping-template','condition','fnsku','inbound','reserved','unfulfillable']){
  assert.equal(await page.locator('.sku-table>tbody>tr[data-sku]').nth(1).locator(`[data-column="${key}"]`).evaluate(e=>!e.hidden&&getComputedStyle(e).display!=='none'),true);
  assert.equal(await page.locator('.sku-family-table>tbody>tr').first().locator(`[data-column="${key}"]`).evaluate(e=>!e.hidden&&getComputedStyle(e).display!=='none'),true);
 }
 await page.evaluate(()=>document.documentElement.style.fontSize='32px');await page.waitForTimeout(50);
 const bounds=await page.evaluate(()=>[...document.querySelectorAll('.sku-table>tbody>tr[data-sku]')].flatMap(r=>[r,...r.cells]).filter(e=>getComputedStyle(e).display!=='none').map(e=>e.getBoundingClientRect().toJSON()));
 for(const b of bounds)assert.ok(b.left>=-1&&b.right<=391,JSON.stringify(b));
 await page.evaluate(()=>scrollTo(0,0));await page.screenshot({path:out+'/390-dark-optional-enlarged-short.png',fullPage:true});assert.deepEqual(errors,[]);await page.close();
});
