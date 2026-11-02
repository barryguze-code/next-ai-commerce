const {test}=require('node:test'),assert=require('node:assert/strict'),path=require('node:path');
const {chromium}=require('playwright');
test('board updates retain anchor/focus, isolate preferences and reject late requests',async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.TEST_BROWSER_CHANNEL?{channel:process.env.TEST_BROWSER_CHANNEL}:{})});
 try{
  const page=await browser.newPage({viewport:{width:1024,height:600}});
  await page.route('http://board.test/',r=>r.fulfill({body:'<main></main>',contentType:'text/html'}));await page.goto('http://board.test/');
  await page.setContent('<style>article{height:180px}</style><input id="filter"><main>'+Array.from({length:20},(_,i)=>`<article tabindex="-1" data-card-id="${i}"><button data-focus-key="open">Open ${i}</button></article>`).join('')+'</main>');
  for(const file of ['table-preferences.js','collaboration-board-state.js'])await page.addScriptTag({path:path.resolve('src/main/resources/static/js/'+file)});
  const result=await page.evaluate(()=>{
   const root=document.querySelector('main'),state=NextAiCollaborationBoardState.create(root,'user-a','tenant-a');
   window.scrollTo(0,1100);const before=state.capture();
   root.prepend(Object.assign(document.createElement('article'),{textContent:'New card'}));state.restore(before);
   const after=state.capture();
   const control=root.querySelector('[data-card-id="8"] button');control.focus({preventScroll:true});
   const focus=state.capture();control.replaceWith(control.cloneNode(true));state.restore(focus);
   const focusRestored=document.activeElement.dataset.focusKey==='open';
   state.savePreferences({initialCards:6,scope:'store-a',title:'PRIVATE MUST NOT SAVE'});
   const own=state.preferences(),other=NextAiCollaborationBoardState.create(root,'user-b','tenant-a').preferences();
   const otherTenant=NextAiCollaborationBoardState.create(root,'user-a','tenant-b').preferences();
   const first=state.request(),second=state.request(),stale=!first.current()&&first.signal.aborted,current=second.current();state.invalidate();
   return {before:before.offset,after:after.offset,focusRestored,own,other,otherTenant,stale,current,invalidated:!second.current(),stored:Object.values(localStorage).join('')};
  });
  assert.equal(result.before,result.after);assert.ok(result.focusRestored);
  assert.equal(result.own.initialCards,6);assert.equal(result.other.initialCards,5);assert.equal(result.otherTenant.scope,'ALL');
  assert.ok(result.stale&&result.current&&result.invalidated);assert.ok(!result.stored.includes('PRIVATE'));
 }finally{await browser.close()}
});

test('an authorized notification opens its task outside the visible queue page',async()=>{
 const fs=require('node:fs');
 const html=fs.readFileSync('.local/browser-fixtures/collaboration-board.html','utf8');
 const browser=await chromium.launch({headless:true});
 try{
  const page=await browser.newPage({viewport:{width:390,height:844}});
  const id='00000000-0000-0000-0000-000000000019';
  await page.route('http://board.test/**',async route=>{
   const url=new URL(route.request().url());
   if(url.pathname==='/app/collaboration/board')return route.fulfill({contentType:'text/html',body:html});
   if(url.pathname.endsWith('/data'))return route.fulfill({json:{cards:[],more:false,revision:7,asOf:'2026-11-01T12:00:00Z'}});
   if(url.pathname.endsWith('/'+id))return route.fulfill({json:{id,title:'Count shelf stock'}});
   if(url.pathname.endsWith('/'+id+'/messages'))return route.fulfill({json:[{id,author:'Warehouse',body:'Count the top shelf',createdAt:'2026-11-01T12:00:00Z',files:[]}]});
   if(['/js/table-preferences.js','/js/collaboration-board-state.js','/js/collaboration-board.js'].includes(url.pathname))
    return route.fulfill({contentType:'text/javascript',path:path.resolve('src/main/resources/static'+url.pathname)});
   return route.fulfill({status:204});
  });
  await page.goto('http://board.test/app/collaboration/board?task='+id);
  const dialog=page.getByRole('dialog');
  await dialog.getByText('Count the top shelf').waitFor({timeout:5000});
  assert.equal(await dialog.getByRole('heading').innerText(),'Count shelf stock');
  await page.keyboard.press('Escape');
  assert.equal(await dialog.count(),0);
  assert.equal(await page.locator('.kanban-column').count(),3);
 }finally{await browser.close();}
});

test('dragging across a filtered gap submits the saved tenant position and revision',async()=>{
 const html=require('node:fs').readFileSync('.local/browser-fixtures/collaboration-board.html','utf8');
 const browser=await chromium.launch({headless:true});
 try{
  const page=await browser.newPage();
  let submitted;
  await page.route('http://board.test/**',async route=>{
   const req=route.request(),url=new URL(req.url());
   if(url.pathname==='/app/collaboration/board')return route.fulfill({contentType:'text/html',body:html});
   if(url.pathname.endsWith('/data'))return route.fulfill({json:{cards:url.searchParams.get('state')==='OPEN'?
    [{id:'source',title:'First',position:2,state:'OPEN'},{id:'target',title:'Last',position:8,state:'OPEN'}]:[],revision:13,more:false,asOf:'2026-11-01T12:00:00Z'}});
   if(url.pathname.endsWith('/tasks/source/position')){submitted=req.postData();return route.fulfill({json:{}});}
   if(['/js/table-preferences.js','/js/collaboration-board-state.js','/js/collaboration-board.js'].includes(url.pathname))
    return route.fulfill({contentType:'text/javascript',path:path.resolve('src/main/resources/static'+url.pathname)});
   return route.fulfill({status:204});
  });
  await page.goto('http://board.test/app/collaboration/board');
  await page.locator('[data-card-id="source"] .queue-grip').dragTo(page.locator('[data-card-id="target"]'),{timeout:5000});
  assert.match(submitted,/name="position"\r\n\r\n8\r\n/);
  assert.match(submitted,/name="revision"\r\n\r\n13\r\n/);
 }finally{await browser.close();}
});

test('Closed restores expanded pages after navigation and retries a failed group in place',async()=>{
 const html=require('node:fs').readFileSync('.local/browser-fixtures/collaboration-board.html','utf8');
 const browser=await chromium.launch();try{
  const page=await browser.newPage();let fail=true;
  await page.route('http://board.test/**',route=>{
   const url=new URL(route.request().url()),p=url.searchParams;
   if(url.pathname==='/app/collaboration/board')return route.fulfill({contentType:'text/html',body:html});
   if(url.pathname.endsWith('/data')){
    if(p.get('group')&&fail){fail=false;return route.fulfill({status:503,json:{error:'Please retry.'}});}
    const older=p.has('before'),id=older?'older':'recent';
    return route.fulfill({json:{revision:3,asOf:'2026-11-01T18:00:00Z',groups:{THIS_WEEK:2},
     more:!!p.get('group')&&!older,cards:p.get('group')?[{id,title:id,state:'DONE',closedAt:'2026-10-31T12:00:00Z'}]:[]}});
   }
   if(['/js/table-preferences.js','/js/collaboration-board-state.js','/js/collaboration-board.js'].includes(url.pathname))
    return route.fulfill({contentType:'text/javascript',path:path.resolve('src/main/resources/static'+url.pathname)});
   return route.fulfill({status:204});
  });
  await page.goto('http://board.test/app/collaboration/board');
  const group=page.locator('details[data-group=THIS_WEEK]');
  await group.locator('summary').click();
  await group.getByRole('button',{name:'Retry',exact:true}).click({timeout:3000});
  await group.locator('[data-more]').click();
  await group.locator('[data-card-id=older]').waitFor();
  await page.reload();await group.locator('[data-card-id=older]').waitFor({timeout:4000});
  assert.deepEqual(await group.locator('[data-card-id]').evaluateAll(cards=>cards.map(c=>c.dataset.cardId)),['recent','older']);
 }finally{await browser.close();}
});
