const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const {chromium}=require('playwright');
const title='Marketplace products and Amazon SKU inventory follow-up for the warehouse team';
async function fixture(run){
 const browser=await chromium.launch({headless:true});try{
 const page=await browser.newPage({viewport:{width:1440,height:1000}});page.setDefaultTimeout(5000);
 await page.route('https://header.test/**',route=>{
  const path=new URL(route.request().url()).pathname;
  if(path.startsWith('/css/')||path.startsWith('/js/'))return route.fulfill({path:'src/main/resources/static'+path,contentType:path.startsWith('/css/')?'text/css':'application/javascript'});
  if(path.startsWith('/images/')){const asset='src/main/resources/static'+path;return fs.existsSync(asset)?route.fulfill({path:asset}):route.abort();}
  if(path.endsWith('/personal/data'))return route.fulfill({json:{rows:[],reviews:{},windows:[]}});
  if(path.endsWith('/members'))return route.fulfill({json:[{id:'me',name:'Me Tester',email:'me@example.test',handle:'me'}]});
  if(path.includes('/summaries'))return route.fulfill({json:{}});
  if(path==='/app/collaboration/reviews/review1')return route.fulfill({json:{review:{id:'review1',subjectType:'PLATFORM',subjectKey:'product1',subjectLabel:'Original product',title,status:'ACTIVE',assigneeEmail:'me@example.test',messageCount:1},messages:[],messageType:'TEAM_CHAT'}});
  if(path==='/'){
   let html=fs.readFileSync('.local/browser-fixtures/collaboration-board.html','utf8');
   html=html.replace('</head>','<link rel="stylesheet" href="/css/access.css"></head>');
   html=html.replace('</main>','<button id="open-test" data-review-id="review1" data-subject-type="PLATFORM" data-subject-key="product1" data-subject-label="Original product" onclick="openCollaborationReview(this)">Open fixture conversation</button></main>');
   return route.fulfill({contentType:'text/html',body:html});
  }
  return route.fulfill({json:[]});
 });
 await page.context().addCookies([{name:'nextai_theme',value:'light',url:'https://header.test'}]);
 await page.goto('https://header.test/');await page.locator('#open-test').click();await page.locator('#thread-record-title').getByText(title,{exact:true}).waitFor();await page.waitForTimeout(250);await run(page);
 }finally{await browser.close();}
}
test('shared conversation title uses available header width and edit controls remain in a desktop row',()=>fixture(async page=>{
 const header=page.locator('.thread-dialog-header'),heading=page.locator('#thread-record-title');
 await page.screenshot({path:'../collaboration-header-local/desktop-view.png'});
 const b=await heading.boundingBox(),h=await header.boundingBox();assert.ok(b.width>450,`Title width ${b.width}; header ${h.width}x${h.height}`);assert.ok(h.height<260,`Header height ${h.height}`);
 await page.getByRole('button',{name:'Edit subject',exact:true}).click();
 const input=page.getByRole('textbox',{name:'Subject',exact:true}),save=page.locator('[data-thread-save-title]'),cancel=page.locator('[data-thread-cancel-title]');
 const [i,s,c]=await Promise.all([input.boundingBox(),save.boundingBox(),cancel.boundingBox()]);assert.ok(i.width>400);assert.ok(Math.abs(i.y-s.y)<12&&Math.abs(s.y-c.y)<12,'Input, Save and Cancel must share a row');
 await page.screenshot({path:'../collaboration-header-local/desktop-edit.png'});await cancel.click();assert.equal(await heading.textContent(),title);
 await page.getByRole('button',{name:'Edit subject',exact:true}).click();await input.fill('Cancelled title');await cancel.click();assert.equal(await heading.textContent(),title);
 await page.setViewportSize({width:390,height:844});await page.screenshot({path:'../collaboration-header-local/phone-view.png'});const phone=await heading.boundingBox();assert.ok(phone.width>180,`Phone title width ${phone.width}`);assert.ok((await header.boundingBox()).height<360);await page.getByRole('button',{name:'Edit subject',exact:true}).click();await page.screenshot({path:'../collaboration-header-local/phone-edit.png'});
}));
