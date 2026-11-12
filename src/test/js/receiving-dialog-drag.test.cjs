const {test}=require('node:test'),assert=require('node:assert/strict');
const {chromium}=require('playwright');
test('Receiving upload moves transparently, restores, clamps and resets without changing fields',async()=>{
 const browser=await chromium.launch({headless:true});try{
 const page=await browser.newPage({viewport:{width:1200,height:900}});
 await page.setContent('<style>dialog{width:600px;height:500px;padding:0} .dialog-header{height:100px} dialog::backdrop{background:#0006;backdrop-filter:blur(2px)}</style><button id="behind">Background</button><dialog id="session-dialog"><header class="dialog-header"><h2>Upload receiving documents</h2><button onclick="this.closest(\'dialog\').close()">Close</button></header><input value="preserved"><input type="file"></dialog>');
 await page.addStyleTag({path:'src/main/resources/static/css/ux-components.css'});
 await page.addScriptTag({path:'src/main/resources/static/js/ux-components.js'});
 await page.evaluate(()=>document.querySelector('dialog').showModal());
 await page.locator('input[type=file]').setInputFiles({name:'packing.csv',mimeType:'text/csv',buffer:Buffer.from('SKU,Quantity\n001,3')});
 const dialog=page.locator('dialog'),header=page.locator('header');let start=await dialog.boundingBox(),h=await header.boundingBox();
 await page.mouse.move(h.x+150,h.y+40);await page.mouse.down();await page.mouse.move(h.x+250,h.y+100,{steps:5});
 assert.equal(await dialog.evaluate(e=>getComputedStyle(e).opacity),'0.25');assert.equal(await dialog.evaluate(e=>getComputedStyle(e,'::backdrop').backdropFilter),'none');
 await page.screenshot({path:'/tmp/shared-dialog-dragging.png'});await page.mouse.up();await page.screenshot({path:'/tmp/shared-dialog-dropped.png'});assert.equal(await dialog.evaluate(e=>getComputedStyle(e).opacity),'1');assert.ok((await dialog.boundingBox()).x>start.x+90);
 assert.equal(await page.locator('input').first().inputValue(),'preserved');assert.equal(await page.locator('input[type=file]').evaluate(e=>e.files[0].name),'packing.csv');
 const beforeCancel=await dialog.boundingBox();h=await header.boundingBox();await page.mouse.move(h.x+150,h.y+40);await page.mouse.down();await page.mouse.move(h.x+180,h.y+60);await page.keyboard.press('Escape');await page.mouse.up();await page.waitForTimeout(30);assert.equal(await dialog.evaluate(e=>e.open),true);assert.equal(await dialog.evaluate(e=>getComputedStyle(e).opacity),'1');assert.ok(Math.abs((await dialog.boundingBox()).x-beforeCancel.x)<1);
 await header.focus();await page.keyboard.press('Alt+ArrowRight');await page.waitForTimeout(30);assert.ok((await dialog.boundingBox()).x>start.x+110);
 h=await header.boundingBox();await page.mouse.move(h.x+150,h.y+40);await page.mouse.down();await page.mouse.move(1190,890,{steps:3});await page.mouse.up();let box=await dialog.boundingBox();assert.ok(box.x+box.width<=1188.1);assert.ok(box.y+box.height<=888.1);
 await page.evaluate(()=>{const d=document.querySelector('dialog');d.close();});await page.waitForTimeout(30);await page.evaluate(()=>document.querySelector('dialog').showModal());assert.ok(Math.abs((await dialog.boundingBox()).x-start.x)<1);
 await page.setViewportSize({width:760,height:900});h=await header.boundingBox();await page.mouse.move(h.x+150,h.y+40);await page.mouse.down();await page.mouse.move(h.x+200,h.y+70);assert.equal(await dialog.evaluate(e=>e.classList.contains('is-dragging')),false);await page.mouse.up();
 }finally{await browser.close();}
});
test('shared drag excludes drawers and interactive controls and restores original positioning',async()=>{
 const browser=await chromium.launch({headless:true});try{const page=await browser.newPage({viewport:{width:1200,height:900}});
 await page.setContent('<dialog class="drawer"><header><h2>Drawer</h2></header></dialog><dialog id="center" style="translate:2px 3px"><header><h2>Mapping</h2><input value="title"><button>Edit</button></header><p>Body</p><footer>Footer</footer></dialog>');
 await page.addScriptTag({path:'src/main/resources/static/js/ux-components.js'});
 assert.equal(await page.locator('.drawer').evaluate(e=>e.classList.contains('ux-dialog-draggable')),false);
 await page.locator('#center').evaluate(e=>e.showModal());const input=await page.locator('input').boundingBox();await page.mouse.move(input.x+10,input.y+5);await page.mouse.down();await page.mouse.move(input.x+80,input.y+25);await page.mouse.up();assert.equal(await page.locator('#center').evaluate(e=>e.style.translate),'2px 3px');
 await page.locator('#center').evaluate(e=>e.close());await page.waitForTimeout(20);assert.equal(await page.locator('#center').evaluate(e=>e.style.translate),'2px 3px');
 }finally{await browser.close();}
});
