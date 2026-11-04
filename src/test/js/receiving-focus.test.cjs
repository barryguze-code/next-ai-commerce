const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const {chromium}=require('playwright');
const root='src/main/resources/';
const template=fs.readFileSync(root+'templates/receiving-work.html','utf8');
const dialogs=template.slice(template.indexOf('<dialog'),template.indexOf('<script th:inline'));
test('receipt opens with the visible expiration input focused',async()=>{
 const browser=await chromium.launch({headless:true});try{const page=await browser.newPage();
  const state={documents:[{id:'doc',type:'INVOICE',number:'QA'}],lines:[{id:'line',documentId:'doc',productId:'item',product:'QA Yogurt',code:'381005',remaining:12,received:0,receipts:0,unitsPerCase:6,depositFee:0,otherFee:0,locationId:'main',unitCost:2,currency:'USD',requiresExpiration:true}],locations:[{id:'main',code:'MAIN',name:'Main storage',status:'ACTIVE'}],details:[],policy:{minimumSellableDays:4,warningDays:18}};
  await page.route('**/*',route=>{
   const path=new URL(route.request().url()).pathname;
   if(path.endsWith('/identity'))return route.fulfill({json:{imageUrl:''}});
   if(path.endsWith('/expiration-status'))return route.fulfill({json:{tone:'success',message:'Expiration OK'}});
   if(path.endsWith('/state'))return route.fulfill({json:state});
   return route.fulfill({contentType:'text/html',body:'<main class="receive-work" data-can-edit="true" data-can-add-location="false"><div id="rw-lines-card"><label class="table-standard-search"><input type="search" aria-label="Search table"></label></div><button data-receive="line">Receive</button><table class="rw-table"></table><p id="rw-feedback"></p></main>'+dialogs});
  });
  await page.goto('http://localhost/receiving-fixture');await page.evaluate(state=>window.receivingWork=state,state);
  await page.evaluate(()=>document.body.className='app-page platform-standard-tables');
  for(const file of ['app.css','brand.css','access.css','platform-controls.css','receiving-work.css','standard-record-tables.css','order-pictures.css','platform-ui.css'])await page.addStyleTag({path:root+'static/css/'+file});
  for(const file of ['platform-controls.js','short-date-input.js','receiving-work.js'])await page.addScriptTag({path:root+'static/js/'+file});
  await page.getByRole('button',{name:'Receive',exact:true}).click();
 await page.waitForFunction(()=>document.activeElement?.classList.contains('short-date-text'),{},{timeout:2000});
 }finally{await browser.close();}
});
