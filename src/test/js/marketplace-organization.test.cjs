const {test,before,after}=require('node:test');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require('playwright');
const base=path.resolve('src/main/resources/static'),fixtureDir='/tmp/marketplace-organization-review',out=path.resolve('.local/marketplace-family-band-20261022/review-v2');let browser;
before(async()=>{fs.mkdirSync(out,{recursive:true});browser=await chromium.launch({headless:true,channel:process.env.TEST_BROWSER_CHANNEL||'chrome'});});
after(async()=>browser?.close());
async function fixture(width,theme,expired=false,optional=false){
 const page=await browser.newPage({viewport:{width,height:1000},colorScheme:theme});await page.context().addCookies([{name:'nextai_theme',value:theme,url:'http://organization.test'}]);const errors=[];page.on('pageerror',e=>errors.push(e.message));
 if(optional)await page.addInitScript(()=>localStorage.setItem('nextai.table.preferences.marketplace-skus',JSON.stringify({schema:2,layoutVersion:'orders-aligned-20261005',order:['record-context','product-details','status','price-shipping','fnsku','condition','shipping-template','inbound','reserved','unfulfillable','profit','available','4w-sales'],visibility:Object.fromEntries(['record-context','product-details','status','price-shipping','fnsku','condition','shipping-template','inbound','reserved','unfulfillable','profit','available','4w-sales'].map(k=>[k,true]))})));
 await page.route('**/*',route=>{const u=new URL(route.request().url());if(u.hostname!=='organization.test')return route.abort();
  if(/^\/(css|js|images)\//.test(u.pathname)){const file=path.join(base,u.pathname);return fs.existsSync(file)?route.fulfill({path:file}):route.fulfill({status:404,body:''});}
  if(u.pathname==='/app/marketplace-skus')return route.fulfill({contentType:'text/html',body:fs.readFileSync(fixtureDir+(expired==='empty'?'/empty-parent.html':expired?'/expired.html':'/marketplace.html'),'utf8').replace('<html','<html data-theme="'+theme+'"').replaceAll('<span>Parent SKU <span>','<span class="sku-family-identity">Parent SKU <span>').replaceAll('<span> · Parent ASIN <span>','<span class="sku-family-asin">ASIN <span>').replace('<span>Condition:</span>','').replace('<select data-sku-view-filter="f_condition"','<select data-standard-choice data-sku-view-filter="f_condition"')});
  return route.fulfill({contentType:'application/json',body:u.pathname==='/app/inventory/profit'?'{}':'[]'});
 });
 await page.goto('http://organization.test/app/marketplace-skus?q=tea&f_condition=NEW&f_channel=FBA&sort=price&direction=desc&page=2');await page.waitForTimeout(200);return{page,errors};
}
for(const width of [320,390,430,760,761,1440])for(const theme of ['light','dark'])test(`Marketplace organization ${width} ${theme}`,async()=>{
 const{page,errors}=await fixture(width,theme,false,true);
 const rows=page.locator('.sku-table>tbody>tr[data-sku]'),heads=page.locator('.sku-table>tbody>[data-family-heading]');
 assert.equal(await rows.count(),4);assert.equal(await heads.count(),2);assert.equal(await page.locator('.sku-family').count(),0);
 assert.equal(await page.locator('[data-sku-view-filter=f_condition]').inputValue(),'NEW');
 for(const heading of await heads.all()){assert.equal(await heading.locator('button.sku-family-toggle').count(),1);assert.equal(await heading.locator('a,img,[data-profit-key]').count(),0);assert.match(await heading.innerText(),/Parent SKU.*000PARENT/s);}
 const check=await page.evaluate(()=>({page:document.documentElement.scrollWidth,viewport:innerWidth,heads:[...document.querySelectorAll('[data-family-heading]')].map(e=>e.getBoundingClientRect().toJSON()),controls:[...document.querySelectorAll('[data-sku-view-filter]:not([hidden]),.sku-condition-choice-trigger')].map(e=>e.getBoundingClientRect().toJSON())}));
 assert.ok(check.page<=width+1,JSON.stringify(check));for(const c of check.controls){assert.ok(c.height>=44);assert.ok(c.x>=-1&&c.right<=width+1);}
 for(const h of check.heads)if(width<=760)assert.ok(h.x>=-1&&h.right<=width+1);
 const contrasts=await heads.locator('td,.sku-family-toggle,.sku-family-asin,.sku-family-count').evaluateAll(nodes=>nodes.map(e=>{const rgb=s=>{const values=(s.match(/[\d.]+/g)||[]).map(Number);return s.startsWith('color(srgb')?values.map((v,i)=>i<3?v*255:v):values},fg=rgb(getComputedStyle(e).color);let bg=[255,255,255],chain=[];for(let p=e;p;p=p.parentElement)chain.push(rgb(getComputedStyle(p).backgroundColor));for(const c of chain.reverse()){const a=c.length>3?c[3]:1;if(c.length>=3)bg=bg.map((v,i)=>c[i]*a+v*(1-a));}const lum=c=>c.slice(0,3).map(v=>{v/=255;return v<=.04045?v/12.92:Math.pow((v+.055)/1.055,2.4);}).reduce((a,v,i)=>a+v*[.2126,.7152,.0722][i],0);const a=lum(fg),b=lum(bg);return {ratio:(Math.max(a,b)+.05)/(Math.min(a,b)+.05),text:e.textContent,color:getComputedStyle(e).color,bg};}));for(const c of contrasts)assert.ok(c.ratio>=4.5,'heading contrast '+JSON.stringify(c));
 await page.getByRole('group',{name:'Fulfillment',exact:true}).getByRole('button',{name:'All',exact:true}).focus();assert.equal(await page.getByRole('group',{name:'Fulfillment',exact:true}).getByRole('button',{name:'All',exact:true}).evaluate(e=>e===document.activeElement),true);assert.equal(await page.locator('.sku-family-end').count(),2);assert.equal(await page.locator('.sku-family-member').count(),3);
 await page.screenshot({path:`${out}/${width}-${theme}.png`,fullPage:true});
 if(width===390){await page.evaluate(()=>scrollTo(0,0));await page.screenshot({path:`${out}/390-${theme}-viewport.png`});}
 if(width>=761)assert.ok(await page.locator('[data-column="shipping-template"]').last().evaluate(e=>e.getBoundingClientRect().width)>=170);
 await rows.first().locator('.sku-open-actions').click();assert.equal(await page.locator('.sku-actions-dialog').count(),1);await page.keyboard.press('Escape');assert.equal(await page.locator('.sku-actions-dialog').count(),0);
 assert.deepEqual(errors,[]);await page.close();
});
test('filter choice preserves query/order and resets page without including hidden siblings',async()=>{
 const{page}=await fixture(390,'light');await page.getByRole('button',{name:'Condition: New',exact:true}).click();await page.getByRole('option',{name:'All conditions',exact:true}).click();await page.waitForURL('**f_condition=ALL**');
 let u=new URL(page.url());assert.equal(u.searchParams.get('q'),'tea');assert.equal(u.searchParams.get('f_channel'),'FBA');assert.equal(u.searchParams.get('sort'),'price');assert.equal(u.searchParams.has('page'),false);
 await page.getByRole('group',{name:'Fulfillment',exact:true}).getByRole('button',{name:'FBM',exact:true}).click();await page.waitForURL('**f_channel=FBM**');u=new URL(page.url());assert.equal(u.searchParams.get('f_condition'),'ALL');assert.equal(u.searchParams.get('direction'),'desc');await page.close();
});
test('expired or unresolved family has no verified heading and preserves ordinary rows',async()=>{const{page,errors}=await fixture(390,'dark',true);assert.equal(await page.locator('[data-family-heading]').count(),0);assert.equal(await page.locator('.sku-table>tbody>tr[data-sku]').count(),4);assert.deepEqual(errors,[]);await page.screenshot({path:`${out}/390-dark-expired.png`,fullPage:true});await page.close();});

test('page-local column filtering hides orphan headings without changing canonical rows',async()=>{
 const{page,errors}=await fixture(390,'light');await page.locator('.table-filter-panel input[aria-label="Filter Product details"]').evaluate(e=>{e.value='STANDALONE';e.dispatchEvent(new Event('input',{bubbles:true}))});
 await page.waitForFunction(()=>[...document.querySelectorAll('[data-family-heading]')].every(h=>h.hidden));assert.equal(await page.locator('tr[data-sku]').count(),4);
 await page.locator('.table-filter-panel input[aria-label="Filter Product details"]').evaluate(e=>{e.value='000CHILD-A';e.dispatchEvent(new Event('input',{bubbles:true}))});await page.locator('[data-family-heading]').first().waitFor({state:'visible'});
 assert.equal(await page.locator('[data-family-heading]:not([hidden])').count(),1);assert.deepEqual(errors,[]);await page.close();
});

for(const theme of ['light','dark'])test('default columns desktop '+theme,async()=>{const{page,errors}=await fixture(1440,theme,false,false);assert.equal(await page.locator('tr[data-sku]').count(),4);await page.screenshot({path:`${out}/1440-${theme}-default.png`,fullPage:true});assert.deepEqual(errors,[]);await page.close();});

test('preset controls precede Filters and clearing condition explicitly selects ALL',async()=>{
 const{page}=await fixture(1440,'light');await page.getByRole('button',{name:'Filters · Active',exact:true}).waitFor();
 assert.equal(await page.locator('.sku-view-filters').evaluate(e=>e.nextElementSibling?.classList.contains('order-smart-filter-button')),true);
 await page.getByRole('button',{name:'Clear condition filter',exact:true}).click();await page.waitForURL('**f_condition=ALL**');let u=new URL(page.url());assert.equal(u.searchParams.get('q'),'tea');assert.equal(u.searchParams.get('sort'),'price');assert.equal(u.searchParams.get('f_channel'),'FBA');await page.close();
});
test('clear all filters removes presets with explicit ALL and preserves sort',async()=>{
 const{page}=await fixture(390,'dark');await page.getByRole('button',{name:'Filters · Active',exact:true}).waitFor();
 await page.getByRole('button',{name:'Clear all table filters',exact:true}).click();await page.waitForURL('**f_condition=ALL**');const u=new URL(page.url());assert.equal(u.searchParams.has('f_channel'),false);assert.equal(u.searchParams.has('q'),false);assert.equal(u.searchParams.get('sort'),'price');assert.equal(u.searchParams.has('page'),false);await page.close();
});
test('existing custom filter drawer includes condition and preserves combined explicit choice',async()=>{
 const{page}=await fixture(390,'light');await page.getByRole('button',{name:'Filters · Active',exact:true}).click();
 const dialog=page.getByRole('dialog');assert.equal(await dialog.locator('[name=f_condition]').isVisible(),true);await dialog.locator('[name=f_condition]').selectOption('ALL');await dialog.locator('[name=f_channel]').selectOption('FBM');await dialog.locator('[name=f_sku]').fill('000CHILD');await dialog.getByRole('button',{name:'Apply filters',exact:true}).click();await page.waitForURL('**f_sku=000CHILD**');const u=new URL(page.url());assert.equal(u.searchParams.get('f_condition'),'ALL');assert.equal(u.searchParams.get('f_channel'),'FBM');assert.equal(u.searchParams.get('q'),'tea');assert.equal(u.searchParams.get('sort'),'price');await page.close();
});

for(const width of [320,390,1440])for(const theme of ['light','dark'])test(`run disclosure ${width} ${theme}`,async()=>{
 const{page,errors}=await fixture(width,theme);try{
 const toggles=page.locator('.sku-family-toggle'),rows=page.locator('.sku-table>tbody>tr[data-sku]');
 assert.equal(await toggles.count(),2);assert.equal(await page.locator('.sku-family-count').nth(0).innerText(),'3 variations · 2 in this group');assert.equal(await page.locator('.sku-family-count').nth(1).innerText(),'3 variations · 1 in this group');
 const before=await rows.count();await toggles.nth(0).focus();await page.keyboard.press('Space');assert.equal(await toggles.nth(0).getAttribute('aria-expanded'),'false');assert.equal(await toggles.nth(1).getAttribute('aria-expanded'),'true');assert.equal(await rows.count(),before);assert.equal(await rows.nth(0).isVisible(),false);assert.equal(await rows.nth(2).isVisible(),true);assert.equal(await rows.nth(3).isVisible(),true);assert.equal(await toggles.nth(0).evaluate(e=>e===document.activeElement),true);
 for(let n=0;n<5;n++)await page.evaluate(()=>window.NextAiMarketplaceRows.refreshFamilies());assert.equal(await toggles.count(),2);assert.equal(await toggles.nth(0).getAttribute('aria-expanded'),'false');
 const ids=await toggles.nth(0).getAttribute('aria-controls');assert.equal(ids.split(' ').length,2);for(const id of ids.split(' '))assert.equal(await page.locator('[id="'+id+'"]').count(),1);
 await page.screenshot({path:`${out}/disclosure-${width}-${theme}-collapsed.png`,fullPage:true});await page.setViewportSize({width:width===320?390:320,height:1000});assert.equal(await toggles.nth(0).getAttribute('aria-expanded'),'false');await page.setViewportSize({width,height:1000});await toggles.nth(0).focus();await page.keyboard.press('Enter');assert.equal(await rows.nth(0).isVisible(),true);await page.screenshot({path:`${out}/disclosure-${width}-${theme}-expanded.png`,fullPage:true});
 await rows.first().locator('.sku-open-actions').click();assert.equal(await page.locator('.sku-actions-dialog').count(),1);await toggles.nth(0).click();assert.equal(await page.locator('.sku-actions-dialog').count(),0);assert.equal(await toggles.nth(0).evaluate(e=>e===document.activeElement),true);
 await page.locator('.table-filter-panel input[aria-label="Filter Product details"]').evaluate(e=>{e.value='000CHILD-A';e.dispatchEvent(new Event('input',{bubbles:true}))});await page.waitForFunction(()=>document.querySelector('.sku-family-toggle').getAttribute('aria-expanded')==='true');assert.equal(await page.locator('.sku-family-count').nth(0).innerText(),'3 variations · 1 in this group');assert.equal(await page.locator('[data-family-heading]:not([hidden])').count(),1);await page.screenshot({path:`${out}/filtered-${width}-${theme}.png`,fullPage:true});assert.deepEqual(errors,[]);
 }finally{await page.close();}
});

for(const width of [320,390,1440])for(const theme of ['light','dark'])test(`integrated choice evidence ${width} ${theme}`,async()=>{
 const{page,errors}=await fixture(width,theme);try{
 const trigger=page.getByRole('button',{name:'Condition: New',exact:true});
 const style=await trigger.evaluate(e=>({content:e.querySelector('.sku-choice-chevron')?.textContent,display:e.querySelector('.sku-choice-chevron')&&getComputedStyle(e.querySelector('.sku-choice-chevron')).display,width:e.getBoundingClientRect().width,html:e.outerHTML}));assert.equal(style.content,'⌄');assert.notEqual(style.display,'none');assert.ok(style.width>=58);
 await page.screenshot({path:`${out}/integrated-choice-${width}-${theme}-closed.png`});await trigger.click();await page.getByRole('listbox').waitFor({state:'visible'});assert.equal(await page.getByRole('option',{name:'New',exact:true}).getAttribute('aria-selected'),'true');await page.screenshot({path:`${out}/integrated-choice-${width}-${theme}-open.png`});
 await page.keyboard.press('Escape');const heading=page.locator('[data-family-heading]').first(),toggle=heading.locator('button');await toggle.focus();await toggle.scrollIntoViewIfNeeded();await heading.screenshot({path:`${out}/heading-${width}-${theme}-focus-element.png`});await page.screenshot({path:`${out}/heading-${width}-${theme}-focus-context.png`});const metrics=await heading.evaluate(e=>({row:e.getBoundingClientRect().toJSON(),cell:e.cells[0].getBoundingClientRect().toJSON(),children:[...e.cells[0].children].map(c=>({text:c.textContent,rect:c.getBoundingClientRect().toJSON()})),scrollY}));fs.writeFileSync(`${out}/heading-${width}-${theme}-metrics.json`,JSON.stringify(metrics,null,2));assert.deepEqual(errors,[]);
 }finally{await page.close();}
});

for(const theme of ['light','dark'])test(`compact family title and unverified denominator ${theme}`,async()=>{
 const{page}=await fixture(1440,theme);try{
 const heading=page.locator('[data-family-heading]').first(),toggle=heading.locator('button');
 const geometry=await heading.evaluate(e=>({height:e.getBoundingClientRect().height,button:e.querySelector('button').getBoundingClientRect().height,color:getComputedStyle(e.querySelector('button')).color,blue:getComputedStyle(e).getPropertyValue('--blue').trim()}));
 assert.ok(geometry.height<=46,JSON.stringify(geometry));assert.ok(geometry.button>=44);assert.equal(await toggle.getAttribute('aria-label'),'Collapse family for Parent SKU 000PARENT-WITH-LONG-EXACT-IDENTITY-US-FBA');
 await heading.locator('.sku-family-asin').click();assert.equal(await toggle.getAttribute('aria-expanded'),'true');
 await toggle.hover();await heading.screenshot({path:`${out}/compact-title-${theme}-hover.png`});await toggle.focus();await page.keyboard.press('Space');assert.equal(await toggle.getAttribute('aria-expanded'),'false');assert.equal(await heading.locator('.sku-family-count').innerText(),'3 variations · 2 in this group');
 await page.evaluate(()=>{document.querySelector('[data-family-heading]').removeAttribute('data-family-total');window.NextAiMarketplaceRows.refreshFamilies()});assert.equal(await heading.locator('.sku-family-count').innerText(),'Family total unverified · 2 in this group');await heading.screenshot({path:`${out}/unverified-${theme}.png`});
 await page.evaluate(()=>{document.querySelector('[data-family-heading]').dataset.familyTotal='2';window.NextAiMarketplaceRows.refreshFamilies()});assert.equal(await heading.locator('.sku-family-count').innerText(),'2 variations');
 }finally{await page.close();}
});

for(const width of [320,1440])for(const theme of ['light','dark'])test(`exact parent empty context ${width} ${theme}`,async()=>{
 const{page}=await fixture(width,theme,'empty');try{
 assert.equal(await page.locator('.sku-table>tbody>tr[data-sku]').count(),0);assert.equal(await page.locator('.sku-family-toggle').count(),0);
 assert.match(await page.getByRole('note',{name:'Verified variation family'}).innerText(),/3 variations.*No variations match/s);assert.match(await page.locator('.table-empty').innerText(),/Adjust the current filters/);assert.match(await page.locator('body').innerText(),/Showing 0 listings/);
 assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
 await page.screenshot({path:`${out}/empty-parent-${width}-${theme}.png`,fullPage:true});
 }finally{await page.close();}
});
