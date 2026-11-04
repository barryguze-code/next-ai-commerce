const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
const {chromium}=require('playwright');
test('collaboration follows concrete workspace while broader scopes remain view-only',async()=>{
 const browser=await chromium.launch({headless:true});try{
 const page=await browser.newPage();page.setDefaultTimeout(5000);let writes=0;
 let html=fs.readFileSync('.local/browser-fixtures/personal-collaboration.html','utf8').replace(/<aside[\s\S]*?<\/aside>/,'').replace('<body ','<body style="display:block" ').replace('<main ','<main style="margin:0;width:100%" ').replace('id="personal-queue"','id="personal-queue" data-workspace-account="a" data-workspace-store="s1"');
 html=html.replace('<section class="context-popover">','<section class="context-popover"><button data-collaboration-account="a" data-name="Account A">Account A</button><button data-collaboration-account="a" data-collaboration-store="s1" data-name="Store 1">Store 1</button>');
 await page.route('http://workspace.test/**',async r=>{
 const u=new URL(r.request().url());if(r.request().method()==='POST'){writes++;return r.fulfill({status:204});}
 if(u.pathname.endsWith('/data'))return r.fulfill({json:{rows:[['one','a','s1'],['two','a','s2'],['three','b','s3']].map(([id,account,store],i)=>({position:i+1,card:{key:{type:'TASK',id},title:id,state:'OPEN',account,store}})),revision:1,reviews:{}}});
 if(/^\/(js|css)\//.test(u.pathname))return r.fulfill({path:'src/main/resources/static'+u.pathname});
 return r.fulfill({contentType:'text/html',body:html});});
 await page.goto('http://workspace.test/app/collaboration');await page.locator('tr[data-card-id]').first().waitFor();
 assert.equal(await page.locator('tr[data-card-id]').count(),1);assert.equal(await page.locator('tr[data-card-id]').first().getAttribute('id'),'TASKone');
 await page.locator('details.context-menu').evaluate(n=>n.open=true);await page.getByRole('button',{name:'Account A',exact:true}).click();assert.equal(await page.locator('tr[data-card-id]').count(),2);
 await page.locator('details.context-menu').evaluate(n=>n.open=true);await page.locator('[data-collaboration-account=""]').click();assert.equal(await page.locator('tr[data-card-id]').count(),3);assert.equal(writes,0);
 await page.reload();await page.locator('tr[data-card-id]').first().waitFor();assert.equal(await page.locator('tr[data-card-id]').count(),3);
 html=html.replace('data-workspace-store="s1"','data-workspace-store="s2"').replace('<section class="context-popover">','<section class="context-popover"><button data-collaboration-account="a" data-collaboration-store="s2" data-name="Store 2">Store 2</button>');
 await page.reload();await page.locator('tr[data-card-id]').first().waitFor();assert.equal(await page.locator('tr[data-card-id]').count(),1);assert.equal(await page.locator('tr[data-card-id]').first().getAttribute('id'),'TASKtwo');
 }finally{await browser.close();}
});
test('saved title pen and item-code copy remain adjacent to their content',async()=>{
 const browser=await chromium.launch({headless:true});try{const page=await browser.newPage();
 await page.setContent('<dialog open id="contextual-thread-dialog"><div class="thread-subject-heading"><h2>Hebrew National - Franks Beef All Natural</h2><button data-thread-edit-title><img></button></div></dialog><main class="orders-workspace"><div class="order-mapping-values"><div class="order-identifier-line"><a class="order-copy-value">2308050 × 4</a><button class="order-identifier-filter">Lock</button><button class="order-identifier-copy">Copy</button></div></div></main>');
 for(const css of ['collaboration-thread.css','order-design.css'])await page.addStyleTag({path:'src/main/resources/static/css/'+css});
 const boxes=await page.evaluate(()=>{const rect=s=>{const r=document.querySelector(s).getBoundingClientRect();return {left:r.left,right:r.right,width:r.width}};return {title:rect('h2'),pen:rect('[data-thread-edit-title]'),image:rect('[data-thread-edit-title] img'),lock:rect('.order-identifier-filter'),copy:rect('.order-identifier-copy')}});
 assert.equal(boxes.image.width,28);assert.equal(boxes.pen.width,36);assert.ok(boxes.pen.left-boxes.title.right<=9);assert.ok(boxes.copy.left-boxes.lock.right<=16);
 }finally{await browser.close();}
});
