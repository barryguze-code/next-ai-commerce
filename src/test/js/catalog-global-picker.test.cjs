const {test,before,after}=require('node:test');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require('playwright');
const root=path.resolve('src/main/resources');let browser;
const template=fs.readFileSync(path.join(root,'templates/fragments/catalog-add-product.html'),'utf8');
const dialog=template.match(/<dialog[^>]* id="product-dialog"[\s\S]*?<\/dialog>/)[0]
 .replace(/<option th:each="vendor:[^"]*"[^>]*>Vendor<\/option>/,'<option value="vendor-a">Own vendor</option>')
 .replace(/ th:[\w-]+="[^"]*"/g,'').replace('<form method="post"','<form action="/app/catalog/products" method="post"');
const product={id:'10000000-0000-0000-0000-000000000001',name:'Cheese Cheddar',brand:'Folios',identifierType:'UPC',identifier:'079834140503',size:'12 oz',revision:'revision-1',accountItemId:null,imageUrl:null,expirationRequired:true};
before(async()=>{browser=await chromium.launch({headless:true,channel:process.env.TEST_BROWSER_CHANNEL||'chrome'});});after(async()=>browser?.close());
async function fixture(width=1440,lookup=()=>[product],deep=false){
 const page=await browser.newPage({viewport:{width,height:900}});page.setDefaultTimeout(5000);
 await page.route('**/*',async route=>{const url=new URL(route.request().url());if(url.pathname==='/app/catalog/product-search'){
  const result=await lookup(url.searchParams.get('q'));return route.fulfill({status:result?.error?503:200,contentType:'application/json',body:JSON.stringify(result?.error?{}:result)});
 }return route.fulfill({contentType:'text/html',body:`<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head><body><button id="open" onclick="openCatalogAddProduct()">Add product</button>${dialog}</body></html>`});});
 await page.goto('http://catalog.test/app/catalog'+(deep?'?addProduct=true':''));
 for(const file of ['app.css','brand.css','access.css','catalog-add-product.css'])await page.addStyleTag({path:path.join(root,'static/css',file)});
 await page.addScriptTag({path:path.join(root,'static/js/catalog-global-picker.js')});if(!deep)await page.getByRole('button',{name:'Add product',exact:true}).click();
 await page.evaluate(()=>{window.posts=0;document.querySelector('#product-dialog form').addEventListener('submit',e=>{e.preventDefault();window.posts++;window.submitted=Object.fromEntries(new FormData(e.target));});});return page;
}
for(const width of [1440,390])test(`explicit global selection and account details at ${width}`,async()=>{
 const page=await fixture(width);try{
  const query=page.getByRole('combobox',{name:'Search Global Catalogue'});assert.equal(await query.evaluate(e=>e===document.activeElement),true);
  await query.fill('079834140503');await page.getByRole('option',{name:/Cheese Cheddar/}).waitFor();assert.equal(await page.evaluate(()=>window.posts),0);
  await query.press('ArrowDown');await query.press('Enter');assert.equal(await page.locator('[data-global-selected]').isVisible(),true);assert.equal(await page.locator('[name=globalProductId]').inputValue(),product.id);
  assert.equal(await page.locator('[data-create-product]').isVisible(),false);await page.getByLabel('Account SKU (optional)').fill('ACCOUNT-7');
  await page.getByText('Vendor details (optional)',{exact:true}).click();await page.getByRole('combobox',{name:/^Vendor \(optional\)/}).selectOption('vendor-a');await page.getByRole('textbox',{name:/^Vendor item code/}).fill('VENDOR-99');await page.getByRole('spinbutton',{name:/^Unit cost \/ each/}).fill('2.50');
  const out=path.resolve('.local/catalog-global-selection-review');fs.mkdirSync(out,{recursive:true});await page.screenshot({path:path.join(out,`${width}-selected.png`)});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);assert.equal(await page.evaluate(()=>window.posts),0);
  await page.getByRole('button',{name:'Add to account',exact:true}).click();const sent=await page.evaluate(()=>window.submitted);assert.equal(sent.globalProductId,product.id);assert.equal(sent.globalRevision,'revision-1');assert.equal(sent.accountSku,'ACCOUNT-7');assert.equal(sent.vendorItemCode,'VENDOR-99');assert.equal(sent.name,undefined);assert.equal(await page.evaluate(()=>window.posts),1);
 }finally{await page.close();}
});
test('changing search clears stale association while preserving account edits and ignoring late responses',async()=>{
 const page=await fixture(1440,async q=>{if(q==='slow')await new Promise(r=>setTimeout(r,500));return q==='no-match'?[]:[product];});try{
  const query=page.getByRole('combobox',{name:'Search Global Catalogue'});await query.fill('Cheese');await page.getByRole('option').click();await page.getByLabel('Account SKU (optional)').fill('KEEP-ME');
  await query.fill('slow');await page.waitForTimeout(240);await query.fill('no-match');await page.getByText('No matching global product.',{exact:false}).waitFor();await page.waitForTimeout(500);
  assert.equal(await page.locator('[name=globalProductId]').inputValue(),'');assert.equal(await page.getByLabel('Account SKU (optional)').inputValue(),'KEEP-ME');assert.equal(await page.getByRole('button',{name:'Add to account',exact:true}).isDisabled(),true);assert.equal(await page.getByRole('option').count(),0);
 }finally{await page.close();}
});
test('existing account shows Open existing and performs no submit or reactivation',async()=>{
 const id='20000000-0000-0000-0000-000000000002';const page=await fixture(390,()=>[{...product,accountItemId:id}]);try{
  await page.getByRole('combobox',{name:'Search Global Catalogue'}).fill('Cheese');await page.getByRole('option').click();assert.equal(await page.getByText('Already in your account.',{exact:false}).isVisible(),true);
  assert.equal(await page.getByRole('link',{name:'Open existing item'}).getAttribute('href'),'/app/catalog?q=079834140503#catalog-item-'+id);assert.equal(await page.getByRole('button',{name:'Add to account',exact:true}).isVisible(),false);assert.equal(await page.locator('[data-account-details]').isVisible(),false);assert.equal(await page.evaluate(()=>window.posts),0);
 }finally{await page.close();}
});
test('failed search is distinct from no match and create path retains explicit manual identity edits',async()=>{
 const page=await fixture(1440,q=>q==='fail'?{error:true}:[]);try{
  const query=page.getByRole('combobox',{name:'Search Global Catalogue'});await query.fill('fail');await page.getByRole('button',{name:'Retry search'}).waitFor();assert.equal(await page.getByRole('button',{name:'Create new product'}).isVisible(),false);
  await query.fill('036000291452');await page.getByRole('button',{name:'Create new product'}).click();await page.getByRole('textbox',{name:/^Product name/}).fill('Manual new product');assert.equal(await page.locator('[name=identifier]').inputValue(),'036000291452');
  await page.getByRole('button',{name:'Add to account',exact:true}).click();assert.equal(await page.evaluate(()=>window.submitted.name),'Manual new product');assert.equal(await page.evaluate(()=>window.submitted.globalProductId),undefined);
 }finally{await page.close();}
});
test('deep link focuses search and Escape closes the existing dialog',async()=>{
 const page=await fixture(390,()=>[product],true);try{const query=page.getByRole('combobox',{name:'Search Global Catalogue'});assert.equal(await query.evaluate(e=>e===document.activeElement),true);await page.keyboard.press('Escape');assert.equal(await page.locator('#product-dialog').isVisible(),false);}finally{await page.close();}
});
