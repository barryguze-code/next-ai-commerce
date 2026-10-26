const {test,before,after}=require('node:test');
const assert=require('node:assert/strict');
const {chromium}=require('playwright');
let browser;
before(async()=>{browser=await chromium.launch({headless:true,...(process.env.TEST_BROWSER_CHANNEL?{channel:process.env.TEST_BROWSER_CHANNEL}:{})});});
after(async()=>browser?.close());
test('saved account SKU is not represented as a vendor code; hydration preserves UUID and quantity',async()=>{
 const page=await browser.newPage();page.setDefaultTimeout(4000);
 try{
  await page.route('**/*',route=>{
   const u=route.request().url();
   if(u==='http://mapping.test/')return route.fulfill({contentType:'text/html',body:'<button id="open" data-seller-sku="SYNTHETIC-BUNDLE" data-product-title="Synthetic bundle" data-can-edit="true">Review mapping</button>'});
   if(u.includes('/mappings/components?'))return route.fulfill({json:[{itemId:'00000000-0000-4000-8000-000000000001',productName:'Donuts Vanilla Glazed',vendorItemCode:null,accountSku:'2302326',quantity:4}]});
   return route.fulfill({status:404,body:''});
  });
  await page.goto('http://mapping.test/');await page.addScriptTag({path:'src/main/resources/static/js/mapping-widget.js'});
  await page.evaluate(()=>window.NextAiMappingWidget.open(document.querySelector('#open')));
  await page.waitForFunction(()=>document.querySelector('.mw-line [name=productId]')?.value);
  assert.equal(await page.locator('.mw-line [name=productId]').inputValue(),'00000000-0000-4000-8000-000000000001');
  assert.equal(await page.locator('.mw-line [name=quantity]').inputValue(),'4');
  const label=await page.locator('.mw-line [type=search]').inputValue();
  assert.equal(label,'Donuts Vanilla Glazed');assert.equal(await page.locator('.mw-identity-help').count(),0);
  assert.equal(await page.locator('.mw-save').isEnabled(),true);
 }finally{await page.close();}
});

test('mapping pictures and picture action menu remain contained at desktop and phone width',async()=>{
 for(const width of [1440,390]){
  const page=await browser.newPage({viewport:{width,height:900}});page.setDefaultTimeout(4000);
  try{
   await page.route('**/*',route=>{
    const u=route.request().url();
    if(u==='http://mapping.test/')return route.fulfill({contentType:'text/html',body:'<button id="open" data-seller-sku="SYNTHETIC" data-product-title="Kinder picture containment" data-can-edit="true">Review mapping</button>'});
    if(u.includes('/mappings/components?'))return route.fulfill({json:[{itemId:'00000000-0000-4000-8000-000000000002',productName:'Kinder',vendorItemCode:'0123',accountSku:'INTERNAL',imageUrl:'http://mapping.test/wide.svg',quantity:3}]});
    if(u.endsWith('/wide.svg'))return route.fulfill({contentType:'image/svg+xml',body:'<svg xmlns="http://www.w3.org/2000/svg" width="2000" height="1000"><rect width="2000" height="1000" fill="orange"/></svg>'});
    return route.fulfill({status:404,body:''});
   });
   await page.goto('http://mapping.test/');
   for(const file of ['app','brand','platform-ui','order-pictures','mapping-widget'])await page.addStyleTag({path:'src/main/resources/static/css/'+file+'.css'});
   await page.addScriptTag({path:'src/main/resources/static/js/mapping-widget.js'});
   await page.evaluate(()=>window.NextAiMappingWidget.open(document.querySelector('#open')));
   await page.locator('.mapping-component-picture img').waitFor();
   const geometry=await page.locator('.mapping-component-picture').evaluate(e=>{const h=e.getBoundingClientRect(),i=e.querySelector('img').getBoundingClientRect();return {h:{x:h.x,y:h.y,width:h.width,height:h.height},i:{x:i.x,y:i.y,width:i.width,height:i.height}}});
   assert.ok(geometry.i.width<=geometry.h.width&&geometry.i.height<=geometry.h.height,JSON.stringify(geometry));
   assert.match(await page.locator('.mw-line [type=search]').inputValue(),/Kinder/);assert.equal(await page.locator('.mw-selected-summary').count(),0);
   if(process.env.VISUAL_EVIDENCE_DIRECTORY)await page.screenshot({path:process.env.VISUAL_EVIDENCE_DIRECTORY+'/mapping-'+width+'.png'});
   await page.locator('.mapping-component-picture .mw-picture-edit').click();
   const menu=await page.locator('dialog.mw-picture-menu').boundingBox();
   assert.ok(menu.height<300,'two picture choices must use content height: '+JSON.stringify(menu));
   assert.ok(menu.x>=12&&menu.x+menu.width<=width-12&&menu.y>=12&&menu.y+menu.height<=888,JSON.stringify(menu));
   if(process.env.VISUAL_EVIDENCE_DIRECTORY)await page.screenshot({path:process.env.VISUAL_EVIDENCE_DIRECTORY+'/picture-menu-'+width+'.png'});
   await page.keyboard.press('Escape');
   assert.equal(await page.locator('.mw-picture-menu').count(),0);
   assert.equal(await page.locator('.mapping-component-picture .mw-picture-edit').evaluate(e=>e===document.activeElement),true);
   assert.equal(await page.locator('.mw-line [name=quantity]').inputValue(),'3');
   assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  }finally{await page.close();}
 }
});

test('lookup text stays unselected until a product is chosen and uses the same identity label',async()=>{
 const page=await browser.newPage();page.setDefaultTimeout(4000);let writes=0;
 try{
  await page.route('**/*',route=>{
   const u=route.request().url();if(route.request().method()==='POST'){writes++;return route.fulfill({status:500});}
   if(u==='http://mapping.test/')return route.fulfill({contentType:'text/html',body:'<button id="open" data-seller-sku="BUNDLE" data-can-edit="true">Review mapping</button>'});
   if(u.includes('/mappings/components?'))return route.fulfill({json:[]});
   if(u.includes('/product-search?'))return route.fulfill({json:[{id:'00000000-0000-4000-8000-000000000003',name:'Sauce Mole',vendorItemCode:'399644',identifier:'850032994026',accountSku:'INTERNAL-MOLE'}]});
   return route.fulfill({status:404,body:''});
  });
  await page.goto('http://mapping.test/');await page.addScriptTag({path:'src/main/resources/static/js/mapping-widget.js'});
  await page.evaluate(()=>window.NextAiMappingWidget.open(document.querySelector('#open')));
  await page.locator('.mw-line [type=search]').fill('299402');
  await page.locator('.mw-option').waitFor();
  assert.equal(await page.locator('.mw-line [name=productId]').inputValue(),'');
  assert.equal(await page.locator('.mw-selected-summary').count(),0);
  assert.equal(await page.locator('.mw-save').isEnabled(),false);
  assert.match(await page.locator('.mw-selection-status').innerText(),/Select a product to map/);
  assert.match(await page.locator('.mw-option').innerText(),/Sauce Mole.*399644/s);
  await page.locator('.mw-option').click();
  const expected=await page.evaluate(()=>window.NextAiMappingWidget.identityLabel({name:'Sauce Mole',vendorItemCode:'399644',accountSku:'INTERNAL-MOLE'}));
  assert.equal(await page.locator('.mw-line [type=search]').inputValue(),expected);
  assert.equal(await page.locator('.mw-line [name=productId]').inputValue(),'00000000-0000-4000-8000-000000000003');
  assert.equal(await page.locator('.mw-save').isEnabled(),true);assert.equal(writes,0);
 }finally{await page.close();}
});
