const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const {chromium}=require('playwright');
const root='src/main/resources/';
const template=fs.readFileSync(root+'templates/receiving-work.html','utf8');
const dialogs=template.slice(template.indexOf('<dialog'),template.indexOf('<script th:inline'));
test('receiving tabs, expiration batches, fee navigation and overage confirmation',async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.TEST_BROWSER_CHANNEL?{channel:process.env.TEST_BROWSER_CHANNEL}:{})});
 try{
  const page=await browser.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));let submitted,posts=0;
  const state={documents:[{id:'doc',type:'INVOICE',number:'QA'}],lines:[{id:'line',documentId:'doc',productId:'item',product:'QA Yogurt',code:'381005',remaining:12,received:0,receipts:0,unitsPerCase:6,depositFee:0,otherFee:0,locationId:'main',unitCost:2,currency:'USD',requiresExpiration:true}],locations:[{id:'main',code:'MAIN',name:'Main storage',status:'ACTIVE'}],details:[],policy:{minimumSellableDays:4,warningDays:18}};
  await page.route('**/*',route=>{
   const path=new URL(route.request().url()).pathname;
   if(path.endsWith('/identity'))return route.fulfill({json:{imageUrl:''}});
   if(path.endsWith('/expiration-status'))return route.fulfill({json:{tone:'success',message:'Expiration OK'}});
   if(path.endsWith('/state'))return route.fulfill({json:state});
   if(path.includes('/prepare/'))return route.fulfill({json:{message:'Fees saved.'}});
   if(path.includes('/batches/')){posts++;submitted=JSON.parse(route.request().postData());return route.fulfill({json:{message:'Receipt saved.'}});}
   return route.fulfill({contentType:'text/html',body:'<main class="receive-work" data-can-edit="true" data-can-add-location="false"><div id="rw-lines-card"><label class="table-standard-search"><input type="search" aria-label="Search table"></label></div><button data-receive="line">Receive</button><table class="rw-table"></table><p id="rw-feedback"></p></main>'+dialogs});
  });
  await page.goto('http://localhost/receiving-fixture');await page.evaluate(state=>window.receivingWork=state,state);
  for(const file of ['app.css','brand.css','access.css','platform-controls.css','receiving-work.css'])await page.addStyleTag({path:root+'static/css/'+file});
  for(const file of ['platform-controls.js','receiving-work.js'])await page.addScriptTag({path:root+'static/js/'+file});
  await page.getByRole('button',{name:'Receive',exact:true}).click();
  assert.equal(await page.locator('[name=quantity]').inputValue(),'12');
  assert.equal(await page.locator('[name=cases]').inputValue(),'2');
  assert.equal(await page.locator('[name=quantity]').getAttribute('readonly'),'');
  await page.waitForFunction(()=>document.activeElement?.name==='expiration');
  await page.locator('[name=pack]').fill('5');assert.equal(await page.locator('[name=cases]').inputValue(),'2');assert.equal(await page.locator('[name=quantity]').inputValue(),'10');
  await page.locator('[name=pack]').fill('6');
  assert.equal(await page.locator('.rw-form legend').count(),0);
  assert.equal(await page.locator('[data-summary-received]').getAttribute('data-receipt-progress'),'complete');
  assert.equal(await page.getByRole('tab',{name:'Damaged',exact:true}).getAttribute('data-enabled'),'false');
  await page.getByRole('tab',{name:'Damaged',exact:true}).click();
  assert.equal(await page.locator('[name=locationId]').isVisible(),false);
  assert.equal(await page.locator('[name=damaged]').inputValue(),'6');
  await page.getByRole('tab',{name:'Damaged',exact:true}).click();await page.locator('#rw-warning-dialog').getByRole('button',{name:'Keep editing',exact:true}).last().click();
  assert.equal(await page.locator('[name=damaged]').inputValue(),'6');
  await page.getByRole('tab',{name:'Damaged',exact:true}).click();await page.getByRole('button',{name:'Clear quantity',exact:true}).click();
  // Native dialog close events are queued; wait for the confirmed action to finish.
  await page.waitForFunction(()=>document.querySelector('[name=damaged]').value==='0');
  assert.equal(await page.locator('[name=damaged]').inputValue(),'0');
  assert.equal(await page.getByRole('tab',{name:'Damaged',exact:true}).getAttribute('data-enabled'),'false');
  await page.locator('[name=cases]').fill('1');await page.locator('[name=loose]').fill('2');assert.equal(await page.locator('[name=quantity]').inputValue(),'8');
  assert.equal(await page.locator('[data-summary-received]').getAttribute('data-receipt-progress'),'partial');
  assert.match(await page.locator('[data-summary-remaining]').textContent(),/4 each remaining/);
  assert.match(await page.locator('[data-remainder=quantity]').textContent(),/1 full case \+ 2 each/);
  await page.locator('[name=expiration]').fill('2027-01-16');
  await page.getByRole('button',{name:'+ Add quantity / expiration date',exact:true}).click();
  assert.equal(await page.locator('[data-batch-quantity]').getAttribute('readonly'),'');
  await page.locator('[data-batch-loose]').fill('2');await page.locator('[data-batch-date]').fill('2027-02-16');
  await page.getByRole('tab',{name:'Damaged',exact:true}).click();assert.equal(await page.locator('[name=damaged]').inputValue(),'6');await page.locator('[name=damagedCases]').fill('0');await page.locator('[name=damagedLoose]').fill('2');
  await page.getByRole('tab',{name:'Sellable',exact:true}).click();assert.equal(await page.locator('[name=quantity]').inputValue(),'8');
  await page.getByRole('button',{name:'Item fees…',exact:true}).click();
  await page.getByRole('button',{name:'Back to receipt',exact:true}).click();assert.equal(await page.locator('[name=quantity]').inputValue(),'8');assert.equal(await page.locator('[data-batch-quantity]').inputValue(),'2');
  await page.getByRole('button',{name:'Item fees…',exact:true}).click();await page.getByRole('button',{name:'Save and continue receiving',exact:true}).click();await page.locator('[name=quantity]').waitFor();
  assert.equal(await page.locator('[name=quantity]').inputValue(),'8');assert.equal(await page.locator('[data-batch-date]').inputValue(),'2027-02-16');
  await page.locator('[name=loose]').fill('4');assert.equal(await page.locator('[name=quantity]').inputValue(),'10');
  await page.getByRole('button',{name:'Save receipt',exact:true}).click();await page.locator('#rw-warning-dialog').getByRole('button',{name:'Keep editing',exact:true}).last().click();assert.equal(posts,0);
  await page.getByRole('button',{name:'Save receipt',exact:true}).click();await page.getByRole('button',{name:'Receive with overage',exact:true}).click();
  await page.waitForFunction(()=>!document.getElementById('receiving-drawer').open);
  await page.waitForFunction(()=>document.activeElement?.getAttribute('aria-label')==='Search table');
  assert.equal(posts,1);assert.equal(submitted.confirmedOverage,2);assert.equal(submitted.damaged,2);assert.equal(submitted.unitsPerCase,undefined);
  assert.deepEqual(submitted.batches,[{quantity:10,expiration:'2027-01-16'},{quantity:2,expiration:'2027-02-16'}]);
  assert.equal(await page.locator('#rw-feedback').isVisible(),false);assert.deepEqual(errors,[]);
 }finally{await browser.close();}
});
