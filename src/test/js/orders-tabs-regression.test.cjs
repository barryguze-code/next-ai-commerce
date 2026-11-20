const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
const {chromium}=require('playwright');
test('actual Orders compact header retains reachable status tabs before and after refresh',async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.TEST_BROWSER_CHANNEL?{channel:process.env.TEST_BROWSER_CHANNEL}:{})});
 try{for(const width of [1440,390]){
 const page=await browser.newPage({viewport:{width,height:900}});
 const html=fs.readFileSync('src/main/resources/templates/orders.html','utf8');
 const css=[...html.matchAll(/href="(\/css\/[^"?]+)(?:\?[^" ]*)?"/g)].map(m=>fs.readFileSync('src/main/resources/static'+m[1],'utf8')).join('\n');
 const tabs=['All','Unshipped','Waiting','Shipped'];
 await page.setContent(`<style>${css}</style><body class="app-page"><aside class="sidebar"></aside><main class="workspace orders-workspace"><section class="data-card order-card"><div class="order-tab-panel compact-order-tabs"><span class="order-tab-caption">Orders/Units</span><nav class="order-filters" aria-label="Orders and alerts">${tabs.map((n,i)=>`<a href="?status=${n.toUpperCase()}" ${i===0?'class="active" aria-current="page"':''}><span>${n}</span><span class="order-tab-count"><b>123</b><i>/</i><span>234</span></span></a>`).join('')}</nav><div data-column-control="orders"></div></div></section></main></body>`);
 await page.addScriptTag({path:'src/main/resources/static/js/table-widget.js'});
 await page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
 const nav=page.locator('nav.order-filters');assert.ok((await nav.boundingBox()).width>200,`${width}px Orders tabs collapsed`);
 assert.equal(await page.locator('.order-tab-panel>.order-filters').count(),1,'Orders retains its original layout ownership');
 for(const name of tabs){const link=page.getByRole('link',{name:new RegExp('^'+name)});assert.ok(await link.isVisible());await link.click({trial:true});assert.equal(await link.getAttribute('href'),'?status='+name.toUpperCase());}
 await page.evaluate(()=>window.NextAiTableWidget.refresh());assert.equal(await page.locator('.order-tab-panel>.order-filters').count(),1);
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
 await page.close();
 }}finally{await browser.close();}
});
