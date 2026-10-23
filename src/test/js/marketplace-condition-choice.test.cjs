const {test,before,after}=require('node:test');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require('playwright');
const root=path.resolve('src/main/resources'),out=path.resolve('.local/marketplace-family-band-20261022/review');let browser;
const template=fs.readFileSync(path.join(root,'templates/marketplace-skus.html'),'utf8');
const source=template.match(/<div class="sku-condition-preset">.*?<\/div>/)[0];
const control=source.replace(/<option th:each=.*?<\/option>/,'<option value="NEW" selected>New</option><option value="USED_GOOD">Used · Good</option><option value="LONG">Synthetic exceptionally long condition label for viewport stress</option><option value="DISABLED" disabled>Unavailable</option>').replace(/ th:[\w-]+="[^"]*"/g,'');
before(async()=>{fs.mkdirSync(out,{recursive:true});browser=await chromium.launch({headless:true,timeout:15000,channel:process.env.TEST_BROWSER_CHANNEL||'chrome'});});after(async()=>browser?.close());
async function fixture(width,theme='light',enhance=true){
 const page=await browser.newPage({viewport:{width,height:700}});page.setDefaultTimeout(5000);await page.route('**/*',r=>r.fulfill({contentType:'text/html',body:`<!doctype html><html data-theme="${theme}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head><body class="app-page"><main class="workspace sku-workspace"><div class="sku-view-filters">${control}</div><button id="after">Next control</button></main></body></html>`}));
 await page.goto('http://condition.test/app/marketplace-skus?q=tea&status=UNMAPPED&sort=price&direction=desc&page=2&f_channel=FBA&f_condition=NEW');
 for(const css of ['app.css','brand.css','access.css','platform-controls.css','marketplace-sku-responsive.css'])await page.addStyleTag({path:path.join(root,'static/css',css)});
 if(enhance)await page.addScriptTag({path:path.join(root,'static/js/platform-controls.js')});
 await page.evaluate(()=>{window.changes=0;document.querySelector('select').addEventListener('change',()=>window.changes++);});return page;
}
for(const width of [320,390,760,1440])for(const theme of ['light','dark'])test(`condition choice ${width} ${theme}`,async()=>{
 const page=await fixture(width,theme);try{
 const trigger=page.getByRole('button',{name:'Condition: New',exact:true});assert.equal(await trigger.evaluate(e=>e.firstChild.textContent),'New');assert.ok(!await page.locator('.sku-condition-preset').innerText().then(t=>t.includes('Condition:')));
 assert.ok((await trigger.boundingBox()).height>=44);await trigger.click();const menu=page.getByRole('listbox');assert.equal(await menu.isVisible(),true);assert.equal(await menu.getByRole('option',{name:'New',exact:true}).getAttribute('aria-selected'),'true');
 for(const o of await page.getByRole('option').all())assert.ok((await o.boundingBox()).height>=44);
 const b=await menu.boundingBox();assert.ok(b.x>=0&&b.x+b.width<=width&&b.y>=0&&b.y+b.height<=700);assert.equal(await page.getByRole('option',{name:'Unavailable'}).isDisabled(),true);
 await page.screenshot({path:path.join(out,`${width}-${theme}-open.png`)});await page.keyboard.press('Escape');assert.equal(await trigger.evaluate(e=>e===document.activeElement),true);
 await page.getByRole('button',{name:'Clear condition filter'}).focus();await page.screenshot({path:path.join(out,`${width}-${theme}-closed.png`)});
 assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
 }finally{await page.close();}
});
test('keyboard selection once, Escape cancellation, Tab continuity and long label',async()=>{
 const page=await fixture(320);try{let trigger=page.getByRole('button',{name:'Condition: New',exact:true});await trigger.focus();await page.keyboard.press('ArrowDown');await page.keyboard.press('End');assert.equal(await page.getByRole('option',{name:/Synthetic/}).evaluate(e=>e===document.activeElement),true);await page.keyboard.press('Home');await page.keyboard.press('ArrowDown');await page.keyboard.press('Enter');assert.equal(await page.locator('select').inputValue(),'NEW');assert.equal(await page.evaluate(()=>window.changes),1);
 await trigger.focus();await page.keyboard.press('Space');await page.keyboard.press('End');await page.keyboard.press('Escape');assert.equal(await page.locator('select').inputValue(),'NEW');
 await trigger.click();await page.keyboard.press('Tab');assert.equal(await page.getByRole('listbox').isVisible(),false);assert.equal(await page.getByRole('button',{name:'Clear condition filter'}).evaluate(e=>e===document.activeElement),true);
 await trigger.click();await page.getByRole('option',{name:/Synthetic/}).click();assert.equal(await page.locator('select').inputValue(),'LONG');assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await page.screenshot({path:path.join(out,'320-long-label.png')});
 }finally{await page.close();}
});
test('native source remains available without enhancement and Marketplace opts in explicitly',async()=>{const page=await fixture(390,'light',false);try{assert.equal(await page.getByRole('combobox',{name:'Condition',exact:true}).isVisible(),true);assert.match(source,/data-standard-choice/);assert.doesNotMatch(source,/<span>Condition:/);}finally{await page.close();}});
