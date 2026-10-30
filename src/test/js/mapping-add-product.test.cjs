const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require('playwright');
test('mapping Add product returns to its exact draft row without saving mappings; cancel and existing selection preserve quantities',async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.TEST_BROWSER_CHANNEL?{channel:process.env.TEST_BROWSER_CHANNEL}:{})}),page=await browser.newPage({viewport:{width:1440,height:1000}});page.setDefaultTimeout(5000);
 const root=path.resolve('src/main/resources'),out=path.resolve('.local/catalog-mapping-add-review');fs.mkdirSync(out,{recursive:true});
 const fragment=fs.readFileSync(path.join(root,'templates/fragments/catalog-add-product.html'),'utf8').replace(/<option th:each="vendor:[^"]*"[^>]*>Vendor<\/option>/,'').replace(/ th:[\w-]+="[^"]*"/g,'').replace('<form method="post"','<form action="/app/catalog/products" method="post"');
 const a='00000000-0000-4000-8000-000000000001',b='00000000-0000-4000-8000-000000000002',added='00000000-0000-4000-8000-000000000003';let posts=0,mappingPosts=0,existing=false,failure=false;
 const product={id:'10000000-0000-4000-8000-000000000001',name:'Synthetic Cheddar',brand:'Test',identifierType:'UPC',identifier:'079834140503',revision:'r1',accountItemId:null};
 try{
  await page.route('**/*',route=>{const req=route.request(),u=new URL(req.url());
   if(u.pathname==='/')return route.fulfill({contentType:'text/html',body:`<button id="open" data-seller-sku="TEST-BUNDLE" data-can-edit="true">Review mapping</button>${fragment}`});
   if(u.pathname.endsWith('/mappings/components'))return route.fulfill({json:[{itemId:a,productName:'Keep this component',accountSku:'KEEP',quantity:3},{itemId:b,productName:'Other component',quantity:2}]});
   if(u.pathname.endsWith('/product-search'))return route.fulfill({json:u.searchParams.get('scope')==='global'?[{...product,accountItemId:existing?added:null}]:[{id:b,name:'Unrelated Mole',identifier:'850032994026'}]});
   if(u.pathname==='/app/catalog/products'){posts++;return route.fulfill({status:failure?400:200,json:failure?{error:'Choose again.'}:{id:added,identifier:product.identifier,created:true}});}
   if(u.pathname.includes('/mappings')&&req.method()==='POST')mappingPosts++;
   return route.fulfill({status:404,body:''});
  });
  await page.goto('http://mapping.test/');for(const file of ['app','brand','access','mapping-widget','catalog-add-product'])await page.addStyleTag({path:path.join(root,'static/css',file+'.css')});
  for(const file of ['mapping-widget','catalog-global-picker'])await page.addScriptTag({path:path.join(root,'static/js',file+'.js')});
  await page.getByRole('button',{name:'Review mapping'}).click({trial:true});await page.evaluate(()=>NextAiMappingWidget.open(document.getElementById('open')));
  const rows=page.locator('.mw-line');await rows.nth(1).waitFor();const target=rows.nth(1),input=target.locator('[type=search]');await input.fill('299402');await page.getByRole('button',{name:/Unrelated Mole/}).waitFor();
  assert.equal(await target.getByRole('button',{name:'Add to catalogue',exact:true}).isVisible(),true);await page.screenshot({path:path.join(out,'mapping-add-desktop.png')});
  await target.getByRole('button',{name:'Add to catalogue',exact:true}).click();await page.getByRole('option',{name:/Synthetic Cheddar/}).click();assert.equal(posts,0);await page.getByRole('button',{name:'Add to account',exact:true}).click();
  await page.locator('#product-dialog').waitFor({state:'hidden'});assert.equal(await rows.nth(0).locator('[name=productId]').inputValue(),a);assert.equal(await rows.nth(0).locator('[name=quantity]').inputValue(),'3');assert.equal(await target.locator('[name=productId]').inputValue(),added);assert.equal(await target.locator('[name=quantity]').inputValue(),'2');assert.equal(mappingPosts,0);assert.equal(posts,1);assert.match(await target.locator('.mw-selection-status').innerText(),/Save mapping/);
  const draft=await page.locator('.mapping-widget form').evaluate(e=>Array.from(new FormData(e).entries()));
  await target.getByRole('button',{name:'Add to catalogue',exact:true}).click();await page.locator('#product-dialog').getByRole('button',{name:'Cancel',exact:true}).click();await page.locator('#product-dialog').waitFor({state:'hidden'});assert.deepEqual(await page.locator('.mapping-widget form').evaluate(e=>Array.from(new FormData(e).entries())),draft);assert.equal(await target.getByRole('button',{name:'Add to catalogue',exact:true}).evaluate(e=>e===document.activeElement),true);
  existing=true;await target.getByRole('button',{name:'Add to catalogue',exact:true}).click();await page.getByRole('option',{name:/Synthetic Cheddar/}).click();await page.getByRole('link',{name:'Use existing product'}).click();assert.equal(posts,1);assert.equal(mappingPosts,0);assert.equal(await target.locator('[name=quantity]').inputValue(),'2');
  existing=false;failure=true;await target.getByRole('button',{name:'Add to catalogue',exact:true}).click();await page.getByRole('option',{name:/Synthetic Cheddar/}).click();await page.getByRole('button',{name:'Add to account',exact:true}).click();await page.getByText('Choose again.',{exact:true}).waitFor();assert.equal(mappingPosts,0);assert.equal(await target.locator('[name=productId]').inputValue(),added);await page.locator('#product-dialog').getByRole('button',{name:'Cancel',exact:true}).click();
  await page.setViewportSize({width:390,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);await page.screenshot({path:path.join(out,'mapping-add-phone.png')});
 }finally{await browser.close();}
});
