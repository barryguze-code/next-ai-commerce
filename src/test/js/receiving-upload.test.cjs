const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const {chromium}=require('playwright');
const root='src/main/resources/';
test('upload uses shared choice lists and preserves vendor currency and file contract',async()=>{
 const browser=await chromium.launch({headless:true});
 try{
  const page=await browser.newPage({viewport:{width:1280,height:800}});
  const source=fs.readFileSync(root+'templates/receiving.html','utf8');
  let dialog=source.slice(source.indexOf('<dialog id="session-dialog"'),source.indexOf('<dialog id="inline-vendor-dialog"'));
  dialog=dialog.replace(/<option th:each="v:[\s\S]*?<\/option>/,'<option value="kehe" data-freight="12.5000" data-currency="CAD">KEHE</option>');
  await page.setContent('<body class="app-page">'+dialog+'</body>');
  for(const file of ['app.css','brand.css','access.css','platform-controls.css','receiving.css'])await page.addStyleTag({path:root+'static/css/'+file});
  await page.addScriptTag({content:source.slice(source.indexOf('function prefillVendorDefaults'),source.indexOf('</script>',source.indexOf('function prefillVendorDefaults')))});
  await page.addScriptTag({path:root+'static/js/platform-controls.js'});
  await page.evaluate(()=>{window.NextAiPlatformControls.enhance(document);document.querySelector('dialog').showModal()});
  await page.getByRole('button',{name:/^Vendor:/}).click();
  await page.getByRole('option',{name:'KEHE',exact:true}).click();
  assert.equal(await page.locator('[name=vendorId]').inputValue(),'kehe');
  assert.equal(await page.locator('[name=currency]').inputValue(),'CAD');
  assert.equal(await page.locator('[name=freightAmount]').inputValue(),'12.50');
  assert.equal(await page.locator('[data-receiving-currency]').first().textContent(),'CA$');
  assert.equal(await page.locator('[name=currency]').isVisible(),false);
  assert.equal(await page.locator('[name=files]').getAttribute('multiple'),'');
  assert.equal(await page.locator('[name=files]').getAttribute('required'),'');
  assert.equal(await page.getByText('Safe draft first').count(),0);
  await page.getByRole('button',{name:/^Document type:/}).click();
  await page.getByRole('option',{name:'Packing list',exact:true}).click();
  assert.equal(await page.locator('[name=documentType]').inputValue(),'PACKING_LIST');
  if(process.env.RECEIVING_SCREENSHOT)await page.screenshot({path:process.env.RECEIVING_SCREENSHOT});
  for(const width of [390,1024,1440]){
   await page.setViewportSize({width,height:800});
   assert.equal(await page.locator('#session-dialog').evaluate(el=>{const r=el.getBoundingClientRect();return r.left>=0&&r.right<=innerWidth}),true);
  }
 }finally{await browser.close()}
});
