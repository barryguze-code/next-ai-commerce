const {test}=require('node:test'),assert=require('node:assert/strict');const {chromium}=require('playwright');
test('shared initialization reuses rendered identifier lines and exact SKU filter identity',async()=>{
 const browser=await chromium.launch({headless:true,channel:'chrome'});const page=await browser.newPage();try{
 await page.route('**/*',r=>r.fulfill({contentType:'text/html',body:`<body class="orders-workspace"><div class="order-item"><div class="item-product-copy"><div class="order-identifier-line"><span class="order-identifier-market">S</span><button class="order-sku-copy" data-copy-sku="000001-EAx3"><code>000001-EAx3</code></button></div><div class="order-mapping-inline"><button class="order-inline-mapping-trigger"><img></button></div></div></div></body>`}));
 await page.goto('http://local.test/app/orders');await page.addStyleTag({path:'src/main/resources/static/css/order-design.css'});await page.addScriptTag({path:'src/main/resources/static/js/order-design.js'});
 assert.equal(await page.locator('.order-identifier-line .order-identifier-line').count(),0);assert.equal(await page.getByRole('button',{name:'Filter orders by 000001-EAx3',exact:true}).count(),1);
 assert.equal(await page.locator('.order-identifier-market').count(),1);assert.equal(await page.locator('.order-inline-mapping-trigger').evaluate(e=>getComputedStyle(e).width),'22px');assert.equal(await page.locator('.order-inline-mapping-trigger img').evaluate(e=>getComputedStyle(e).width),'26px');
 }finally{await browser.close();}
});
