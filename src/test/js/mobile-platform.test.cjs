const {test,before,after}=require('node:test');
const assert=require('node:assert/strict');
const path=require('node:path');
const {chromium}=require('playwright');
let browser;
before(async()=>browser=await chromium.launch({headless:true,...(process.env.TEST_BROWSER_CHANNEL?{channel:process.env.TEST_BROWSER_CHANNEL}:{})}));
after(async()=>browser?.close());
const resource=file=>path.resolve('src/main/resources/static',file);
async function fixture(width,height=640,page){
 page=page||await browser.newPage({viewport:{width,height}});
 await page.route('http://fixture.test/**',route=>route.fulfill({body:'<!doctype html><body></body>',contentType:'text/html'}));await page.goto('http://fixture.test/app');
 await page.setContent(`<body class="app-page"><aside class="sidebar"><a class="brand" href="/app">Next AI Commerce</a><nav class="product-nav"><a href="/app/orders">Orders</a><details open><summary>Catalogue</summary><a class="active" href="/app/marketplace-skus">Marketplace SKUs</a></details></nav><div class="sidebar-user"><button aria-label="Theme">Theme</button><button>Sign out</button></div></aside><main class="workspace"><header><div class="context-menu"><button>QA store</button></div></header><section class="data-card"><div class="table-wrap"><table data-table-widget="mobile-regression"><thead><tr>${Array.from({length:25},(_,i)=>`<th>Field ${i}</th>`).join('')}</tr></thead><tbody><tr>${Array.from({length:25},(_,i)=>`<td>${i}</td>`).join('')}</tr></tbody></table></div></section><p style="height:1000px">Page content</p></main></body>`);
 for(const file of ['app.css','brand.css','table-widget.css','mobile-navigation.css'])await page.addStyleTag({path:resource('css/'+file)});
 for(const file of ['table-preferences.js','table-widget.js','mobile-navigation.js'])await page.addScriptTag({path:resource('js/'+file)});
 return page;
}
test('mobile Menu reuses links, keeps context, supports keyboard/Escape and breakpoint focus',async()=>{
 for(const width of [320,390,430,760,761]){
  const p=await fixture(width),menu=p.getByRole('button',{name:'Menu',exact:true});
  assert.equal(await menu.isVisible(),width<=760);
  assert.equal(await p.locator('.product-nav').count(),1);
  assert.equal(await p.locator('a[aria-current=page]').count(),1);
  assert.equal(await p.getByRole('button',{name:'QA store'}).isVisible(),true);
  if(width<=760){
   assert.equal(await p.getByRole('link',{name:'Orders',exact:true}).isVisible(),false);
   await menu.focus();await menu.press('Enter');
   assert.equal(await menu.getAttribute('aria-expanded'),'true');
   assert.equal(await p.getByRole('button',{name:'Sign out'}).isVisible(),true);
   const geometry=await p.evaluate(()=>({navBottom:document.querySelector('.sidebar').getBoundingClientRect().bottom,mainTop:document.querySelector('main').getBoundingClientRect().top,overflow:getComputedStyle(document.body).overflow}));
   assert.ok(geometry.mainTop>=geometry.navBottom-1);assert.notEqual(geometry.overflow,'hidden');
   await p.getByRole('link',{name:'Orders',exact:true}).press('Escape');assert.equal(await menu.getAttribute('aria-expanded'),'false');assert.equal(await menu.evaluate(e=>e===document.activeElement),true);
   await p.setViewportSize({width:761,height:640});await p.waitForFunction(()=>document.activeElement.matches('.product-nav a.active'));assert.equal(await p.locator('.product-nav a.active').evaluate(e=>e===document.activeElement),true);
   await p.setViewportSize({width,height:640});await p.waitForFunction(()=>document.activeElement.matches('.mobile-navigation-trigger'));assert.equal(await menu.evaluate(e=>e===document.activeElement),true);
  }
  assert.ok(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await p.close();
 }
});
test('Columns stays bounded, scrolls internally, returns focus and retains preferences after reload',async()=>{
 for(const width of [320,390,420,430,760]){
  const p=await fixture(width),trigger=p.locator('.table-column-control>summary');await trigger.click();
  const panel=p.locator('.table-column-panel');
  async function bounded(){await p.waitForTimeout(40);const r=await panel.boundingBox(),v=p.viewportSize();assert.ok(r.x>=11&&r.x+r.width<=v.width-11,JSON.stringify(r));assert.ok(r.y>=11&&r.y+r.height<=v.height-11,JSON.stringify(r));}
  await bounded();assert.equal(await p.locator('.table-column-list').evaluate(e=>e.scrollHeight>e.clientHeight),true);
  await p.locator('.table-column-search input').fill('Field 8');await p.getByRole('button',{name:'Toggle Field 8',exact:true}).click();
  const saved=await p.evaluate(()=>localStorage.getItem('nextai.table.preferences.mobile-regression'));
  assert.ok(saved);await p.mouse.wheel(0,180);await bounded();await p.setViewportSize({width,height:400});await bounded();
  await trigger.press('Escape');assert.equal(await trigger.evaluate(e=>e===document.activeElement),true);
  await p.evaluate(()=>document.documentElement.dataset.theme='dark');await trigger.click();await bounded();
  assert.equal(await p.evaluate(()=>localStorage.getItem('nextai.table.preferences.mobile-regression')),saved);
  await p.reload();await fixture(width,640,p);await p.locator('.table-column-control>summary').click();
  assert.equal(await p.getByRole('button',{name:'Toggle Field 8',exact:true}).getAttribute('aria-pressed'),'false');
  await p.close();
 }
});
