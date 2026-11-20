const {test,before,after}=require('node:test');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require('playwright');
const root=path.resolve('src/main/resources/static'),out=path.resolve('.local/marketplace-thumbnail-ratio-20261022/review');
const beforeCss=fs.readFileSync('src/test/fixtures/marketplace-thumbnail-before.css','utf8');
const afterCss=fs.readFileSync(path.join(root,'css/marketplace-sku-responsive.css'),'utf8');let browser;
before(async()=>{fs.mkdirSync(out,{recursive:true});browser=await chromium.launch({headless:true,channel:'chrome'});});after(async()=>browser?.close());
const shapes={square:[100,100,'white'],beige:[100,100,'#c9bca3'],transparent:[100,100,'none'],portrait:[60,120,'white'],landscape:[160,80,'#c9bca3']};
function svg(kind){const[w,h,bg]=shapes[kind];return `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}"><rect width="100%" height="100%" fill="${bg}"/><rect x="3" y="3" width="${w-6}" height="${h-6}" fill="none" stroke="#17293c" stroke-width="2"/><ellipse cx="${w/2}" cy="${h/2}" rx="${w/3}" ry="${h/3}" fill="#608392"/><circle cx="8" cy="8" r="3" fill="#bd4141"/><circle cx="${w-8}" cy="${h-8}" r="3" fill="#bd4141"/></svg>`;}
for(const width of [320,1440])for(const theme of ['light','dark'])for(const kind of [...Object.keys(shapes),'broken','placeholder'])test(`thumbnail ${kind} ${width} ${theme}`,async()=>{
 const page=await browser.newPage({viewport:{width,height:1000},colorScheme:theme,hasTouch:width===320,isMobile:width===320});const errors=[],requests=[];page.on('pageerror',e=>errors.push(e.message));
 await page.context().addCookies([{name:'nextai_theme',value:theme,url:'http://thumbnail.test'}]);
 await page.route('**/*',route=>{const u=new URL(route.request().url());requests.push({method:route.request().method(),url:u.pathname});if(u.hostname!=='thumbnail.test')return route.abort();
  if(u.pathname==='/fixtures/image.svg')return kind==='broken'?route.fulfill({status:404,body:''}):route.fulfill({contentType:'image/svg+xml',body:svg(kind==='placeholder'?'square':kind)});
  if(u.pathname==='/css/marketplace-sku-responsive.css')return route.fulfill({contentType:'text/css',body:beforeCss});
  if(/^\/(css|js|images)\//.test(u.pathname)){const file=path.join(root,u.pathname);return fs.existsSync(file)?route.fulfill({path:file}):route.fulfill({status:404,body:''});}
  if(u.pathname==='/app/marketplace-skus'){
   let html=fs.readFileSync('/tmp/marketplace-organization-review/marketplace.html','utf8').replace('<html','<html data-theme="'+theme+'"');
   html=html.replace(/(<span class="sku-image product-avatar"[^>]*)(>)(<b>)/g,(_,open,end,b)=>open+' data-picture-editable="true">'+(kind==='placeholder'?'':`<img src="/fixtures/image.svg" alt="Synthetic ${kind}" onerror="this.hidden=true;this.nextElementSibling.hidden=false">`)+b.replace('<b>','<b'+(kind==='placeholder'?'':' hidden')+'>'));
   // Non-listing controls deliberately retain their previous shared styling.
   html=html.replace('</body>','<div class="sku-mapping-dialog"><span class="sku-image product-avatar table-overview-picture" id="mapping-control"><img src="/fixtures/image.svg"></span></div><div class="orders-workspace"><span class="sku-image product-avatar table-overview-picture" id="orders-control"><img src="/fixtures/image.svg"></span></div></body>');
   return route.fulfill({contentType:'text/html',body:html});
  }
  return route.fulfill({contentType:'application/json',body:u.pathname==='/app/inventory/profit'?'{}':'[]'});
 });
 try{
 await page.goto('http://thumbnail.test/app/marketplace-skus');const frame=page.locator('#marketplace-listings-table .table-overview-picture').first();await frame.waitFor();
 if(kind==='broken')await frame.locator('img').waitFor({state:'hidden'});else if(kind!=='placeholder')await page.waitForFunction(()=>document.querySelector('#marketplace-listings-table .table-overview-picture img')?.naturalWidth>0);
 const inspect=()=>page.evaluate(()=>{const frame=document.querySelector('#marketplace-listings-table .table-overview-picture'),img=frame.querySelector('img'),edit=frame.querySelector('button'),s=getComputedStyle(frame);return{frame:{w:frame.getBoundingClientRect().width,h:frame.getBoundingClientRect().height,radius:s.borderRadius,overflow:s.overflow,bg:s.backgroundColor},layout:{w:frame.parentElement.getBoundingClientRect().width,h:frame.parentElement.getBoundingClientRect().height},rail:{x:frame.parentElement.querySelector('.table-overview-rail').getBoundingClientRect().x,w:frame.parentElement.querySelector('.table-overview-rail').getBoundingClientRect().width},image:img?{naturalWidth:img.naturalWidth,naturalHeight:img.naturalHeight,w:img.getBoundingClientRect().width,h:img.getBoundingClientRect().height,fit:getComputedStyle(img).objectFit,padding:getComputedStyle(img).padding,margin:getComputedStyle(img).margin,hidden:img.hidden}:null,edit:edit?{w:edit.getBoundingClientRect().width,h:edit.getBoundingClientRect().height}:null,mapping:getComputedStyle(document.querySelector('#mapping-control img')).padding,columns:[...document.querySelectorAll('#marketplace-listings-table th')].map(e=>({text:e.textContent,display:getComputedStyle(e).display})),orders:getComputedStyle(document.querySelector('#orders-control img')).padding}});
 if(width===320)assert.deepEqual(await page.evaluate(()=>({hover:matchMedia('(hover:none)').matches,pointer:matchMedia('(pointer:coarse)').matches})),{hover:true,pointer:true});
 const old=await inspect();await page.addStyleTag({content:afterCss});const now=await inspect();
 if(kind==='broken'||kind==='placeholder')assert.deepEqual(now.frame,old.frame);else{assert.ok(now.frame.w<=80&&now.frame.h<=88);assert.ok(Math.abs(now.image.w/now.image.h-now.image.naturalWidth/now.image.naturalHeight)<0.02);if(kind==='square'||kind==='beige'||kind==='transparent')assert.deepEqual([now.frame.w,now.frame.h],[80,80]);}
 assert.deepEqual(now.layout,old.layout);assert.deepEqual(now.rail,old.rail);assert.deepEqual(now.edit,old.edit);assert.equal(now.mapping,old.mapping);assert.equal(now.orders,old.orders);assert.deepEqual(now.columns,old.columns);
 if(kind!=='broken'&&kind!=='placeholder'){assert.equal(now.frame.bg,'rgb(255, 255, 255)');assert.equal(now.image.fit,'contain');assert.equal(now.image.padding,'0px');assert.equal(now.image.margin,'0px');assert.equal(now.image.w,now.frame.w-2);assert.equal(now.image.h,now.frame.h-2);}
 else {assert.equal(await frame.locator('b').isVisible(),true);if(kind==='broken')assert.equal(await frame.locator('img').isVisible(),false);assert.equal(now.frame.bg,old.frame.bg);}
 assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await frame.scrollIntoViewIfNeeded();await frame.screenshot({path:`${out}/${kind}-${width}-${theme}.png`});
 const edit=frame.getByRole('button',{name:'Edit SKU picture',exact:true});if(width===320){assert.equal(await edit.evaluate(e=>getComputedStyle(e).opacity),'1');await edit.tap();}else{await edit.focus();await page.keyboard.press('Enter');}await page.getByRole('dialog').filter({hasText:'Edit SKU picture'}).waitFor();await page.keyboard.press('Escape');assert.equal(await edit.evaluate(e=>e===document.activeElement),true);
 assert.equal(requests.filter(r=>r.method!=='GET').length,0);assert.deepEqual(errors,[]);fs.writeFileSync(`${out}/${kind}-${width}-${theme}.json`,JSON.stringify({old,now},null,2));if(kind==='square'){const media=()=>page.evaluate(()=>({hoverNone:matchMedia('(hover:none)').matches,pointerCoarse:matchMedia('(pointer:coarse)').matches}));const beforeCapture=await media();await page.screenshot({path:`${out}/context-${width}-${theme}.png`,fullPage:true});fs.writeFileSync(`${out}/capture-media-${width}-${theme}.json`,JSON.stringify({beforeCapture,afterCapture:await media()},null,2));}
 }finally{await page.close();}
});
