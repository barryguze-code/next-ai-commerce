const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require('playwright');
test('account actions open in place inside the shared menu and save separate names',async()=>{
 const templates='src/main/resources/templates/';
 const dialog=fs.readFileSync(templates+'accounts.html','utf8').match(/<dialog id="business-details"[\s\S]*?<\/dialog>/)[0];
 const invite=fs.readFileSync(templates+'users.html','utf8').match(/<dialog th:if="[^"]+" id="invite-dialog"[\s\S]*?<\/dialog>/)[0].replace('name="account"','name="account" value="acct"');
 const browser=await chromium.launch();try{
  const page=await browser.newPage();let saved='',inviteWrites=0;
  await page.route('http://account.test/**',route=>{
   const req=route.request(),url=new URL(req.url());
   if(url.pathname.endsWith('/business-details')){
    if(req.method()==='POST'){saved=req.postData();return route.fulfill({status:204});}
    return route.fulfill({json:{displayName:'Account A',name:'Legal Ltd',address:'Fixture address'}});
   }
   if(url.pathname==='/app/users')return route.fulfill({contentType:'text/html',body:invite});
   if(req.method()==='POST'){inviteWrites++;return route.abort();}
   return route.fulfill({contentType:'text/html',body:'<table><tr data-title="Account A"><td><details open onclick="event.stopPropagation()"><summary>Actions</summary><button data-account="acct" data-business-details>Details</button><button data-account="acct" data-account-invite>Invite</button></details></td></tr></table>'+dialog+'<p id="account-invite-status"></p>'});
  });
  await page.goto('http://account.test/accounts');await page.addScriptTag({path:path.resolve('src/main/resources/static/js/account-business-details.js')});
  await page.getByText('Details',{exact:true}).click();await page.locator('[name=displayName]:not([readonly])').waitFor();
  assert.equal(await page.locator('[name=name]').inputValue(),'Legal Ltd');
  await page.locator('[name=displayName]').fill('Account B');await page.getByText('Save',{exact:true}).click();await page.getByText('Business details saved.').waitFor();
  assert.match(saved,/Account B/);assert.match(saved,/Legal Ltd/);
  await page.keyboard.press('Escape');await page.getByText('Actions',{exact:true}).click();await page.getByText('Invite',{exact:true}).click();
  await page.locator('#invite-dialog[open]').waitFor();assert.equal(page.url(),'http://account.test/accounts');assert.equal(inviteWrites,0);
 }finally{await browser.close();}
});

test('store-name action uses the shared menu and submits only the display-name identity contract',async()=>{
 const browser=await chromium.launch({channel:'chrome',headless:true});try{
  const page=await browser.newPage();page.setDefaultTimeout(5000);let posted;
  await page.route('https://connection.test/**',async route=>{
   const url=new URL(route.request().url()),pathname=url.pathname;
   if(pathname==='/app/connections/name'){posted=new URLSearchParams(route.request().postData());return route.fulfill({contentType:'text/html',body:'Saved'});}
   if(pathname.startsWith('/images/'))return route.abort();
   if(pathname.startsWith('/js/')||pathname.startsWith('/css/'))return route.fulfill({path:'src/main/resources/static'+pathname,contentType:pathname.startsWith('/js/')?'application/javascript':'text/css'});
   let html=fs.readFileSync('.local/browser-fixtures/connections-final.html','utf8').replace('<aside ','<aside style="display:none" ').replace('<body ','<body style="display:block" ').replace('<main ','<main style="margin:0;width:100%" ');
   return route.fulfill({contentType:'text/html',body:html});
  });
  await page.context().addCookies([{name:'nextai_theme',value:'light',url:'https://connection.test'}]);
  await page.goto('https://connection.test/app/connections');
  await page.locator('.table-row-warning summary').first().click();
  await page.getByRole('button',{name:/Edit store name/}).click();
  await page.locator('#connection-name-dialog[open]').waitFor();
  assert.equal(await page.locator('#connection-name-dialog [name=displayName]').inputValue(),'Store one');
  const id=await page.locator('#connection-name-dialog [name=id]').inputValue();
  await page.locator('#connection-name-dialog [name=displayName]').fill('Renamed store');
  await page.locator('#connection-name-dialog button[type=submit]').click();
  await page.waitForURL('**/app/connections/name');
  assert.equal(posted.get('id'),id);assert.equal(posted.get('displayName'),'Renamed store');
  assert.equal(posted.has('sellerIdentifier'),false);assert.equal(posted.has('clientSecret'),false);assert.equal(posted.has('channel'),false);
 }finally{await browser.close();}
});
