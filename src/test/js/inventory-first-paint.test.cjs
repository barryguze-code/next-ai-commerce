const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),{chromium}=require('playwright');
const template=fs.readFileSync('src/main/resources/templates/inventory.html','utf8');
const head=fs.readFileSync('src/main/resources/templates/fragments/table-first-paint.html','utf8').split('<th:block th:fragment="head">')[1].split('</th:block>')[0].replace(/<link[^>]*>/g,'');
const card=template.slice(template.indexOf('<section data-table-composition'),template.indexOf('<tbody>')+7)+'<tr><td>Product</td><td>Example</td></tr></tbody></table></div></section>';
async function fixture(run){const browser=await chromium.launch({headless:true});try{const p=await browser.newPage();await p.route('http://local.test/**',r=>r.fulfill({contentType:'text/html',body:`<html><head>${head}</head><body class="app-page platform-standard-tables"><main data-basket-scope="account:user">${card}</main></body></html>`}));await p.goto('http://local.test/app/inventory');await p.addStyleTag({path:'src/main/resources/static/css/table-widget.css'});await run(p);}finally{await browser.close();}}
test('Inventory first paint never exposes superseded commands while composition is pending',()=>fixture(async p=>{
 // Re-arm the actual early bootstrap to model deferred scripts still downloading before load.
 await p.evaluate(()=>document.documentElement.classList.add('table-composing'));
 assert.equal(await p.locator('[data-command-id=locations]').isVisible(),false);
 assert.equal(await p.getByRole('heading',{name:'Inventory on hand'}).isVisible(),true);
 assert.equal(await p.getByRole('searchbox').isVisible(),true);
 assert.equal(await p.locator('.table-standard-toolbar').count(),1);
 await p.addScriptTag({path:'src/main/resources/static/js/table-widget.js'});
 await p.addScriptTag({path:'src/main/resources/static/js/table-data-tools.js'});
 await p.getByLabel('Actions',{exact:true}).waitFor();
 assert.equal(await p.locator('.table-standard-toolbar').count(),1);
 assert.equal(await p.locator('[data-table-composition]').getAttribute('data-table-composed'),'true');
 assert.equal(await p.locator('.inventory-toolbar-actions [data-table-command]').count(),0);
 await p.getByLabel('Actions',{exact:true}).click();await p.locator('[data-command-id=locations]').waitFor({state:'visible'});
}));
test('Inventory failed composition restores accessible original commands without claiming ready',()=>fixture(async p=>{
 await p.evaluate(()=>{const bootstrap=document.querySelector('[data-table-first-paint-bootstrap]');delete window.NextAiTableFirstPaint;if(bootstrap)(0,eval)(bootstrap.textContent);window.dispatchEvent(new Event('load'));});
 assert.equal(await p.locator('[data-command-id=locations]').isVisible(),true);
 assert.equal(await p.locator('[data-table-composition]').getAttribute('data-table-composed'),null);
}));
test('Inventory partial initialization failure restores moved commands once',()=>fixture(async p=>{
 await p.evaluate(()=>{
  delete window.NextAiTableFirstPaint;(0,eval)(document.querySelector('[data-table-first-paint-bootstrap]').textContent);window.NextAiTableFirstPaint.capture(document.querySelector('[data-table-first-paint]'));const home=document.querySelector('.inventory-toolbar-actions'),partial=document.createElement('div');partial.className='table-header-commands';partial.hidden=true;home.after(partial);partial.append(document.querySelector('[data-command-id=locations]'));
  window.dispatchEvent(new Event('load'));
 });
 assert.equal(await p.locator('[data-command-id=locations]').count(),1);
 assert.equal(await p.locator('.inventory-toolbar-actions [data-command-id=locations]').isVisible(),true);
}));
