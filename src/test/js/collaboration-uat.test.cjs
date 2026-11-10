const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const {chromium}=require('playwright');
test('only actual assignees are red; visible initial count persists across a new session',async()=>{
 const browser=await chromium.launch({channel:'chrome',headless:true});try{
 const context=await browser.newContext();const page=await context.newPage();page.setDefaultTimeout(5000);
 await context.route('https://uat.test/**',async route=>{
  const url=new URL(route.request().url()),path=url.pathname;
  if(path.endsWith('/personal/data'))return route.fulfill({json:{rows:Array.from({length:12},(_,i)=>({position:i+1,card:{key:{type:'REVIEW',id:'r'+i},title:'Task '+i,context:'Account',account:'account',state:'OPEN'}})),reviews:Object.fromEntries(Array.from({length:12},(_,i)=>['r'+i,{id:'r'+i,status:'ACTIVE',title:'Task '+i,subjectLabel:'Product '+i,assigneeEmail:i===0?'jack@example.test':'me@example.test',messageCount:2}])),windows:[],revision:1}});
  if(path.startsWith('/images/'))return route.abort();
  if(path.startsWith('/js/')||path.startsWith('/css/'))return route.fulfill({path:'src/main/resources/static'+path,contentType:path.startsWith('/js/')?'application/javascript':'text/css'});
  let html=fs.readFileSync('src/main/resources/templates/personal-collaboration.html','utf8').replace(/<aside[\s\S]*?<\/aside>/,'').replace('th:attr="data-user=${personalUser}"','data-user="me@example.test"');return route.fulfill({contentType:'text/html',body:html});
 });
 await page.goto('https://uat.test/');await page.locator('tr[data-card-id]').first().waitFor();
 assert.equal(await page.locator('#REVIEWr0 .collaboration-row-button').getAttribute('data-assigned-to-me'),'false');
 assert.match(await page.locator('#REVIEWr0 .collaboration-row-button img').getAttribute('src'),/optimized32\/collaboration-default/);
 assert.equal(await page.locator('#REVIEWr1 .collaboration-row-button').getAttribute('data-assigned-to-me'),'true');
 const size=page.getByLabel('Show first tasks');await size.fill('8');await size.press('Tab');
 await page.waitForFunction(()=>document.querySelectorAll('tr[data-card-id]').length===8);
 await page.evaluate(()=>sessionStorage.clear());await page.reload();await page.locator('tr[data-card-id]').first().waitFor();
 assert.equal(await size.inputValue(),'8');assert.equal(await page.locator('tr[data-card-id]').count(),8);
 assert.equal(await page.locator('.collaboration-topic').first().textContent(),'2 messages');
 assert.equal(await page.locator('.collaboration-record-copy>strong').first().textContent(),'Task 0');
 }finally{await browser.close();}
});
test('one shared dialog handles initial subjects, assignment, mentions and saved subject editing',async()=>{
 const browser=await chromium.launch({channel:'chrome',headless:true});try{
 const page=await browser.newPage();page.setDefaultTimeout(5000);let taskPost,subjectPost,showThreads=false,releaseReply,replySent=false;
 const review={id:'review1',subjectType:'CATALOG',subjectKey:'product1',subjectLabel:'Original product',title:'Saved subject',status:'ACTIVE',assigneeEmail:'me@example.test',assigneeName:'Me',messageCount:1};
 await page.route('https://dialog.test/**',async route=>{
  const url=new URL(route.request().url()),path=url.pathname;
  if(path.startsWith('/images/'))return route.abort();
  if(path.startsWith('/js/')||path.startsWith('/css/'))return route.fulfill({path:'src/main/resources/static'+path,contentType:path.startsWith('/js/')?'application/javascript':'text/css'});
  if(path.endsWith('/personal/data'))return route.fulfill({json:{rows:[],reviews:{},windows:[]}});
  if(path.endsWith('/members'))return route.fulfill({json:[{id:'me',name:'Me Tester',email:'me@example.test',handle:'me'},{id:'jack',name:'Jack Tester',email:'jack@example.test',handle:'jack'}]});
  if(path.endsWith('/personal/create')){taskPost=route.request().postData();return route.fulfill({status:400,json:{error:'Fixture retained draft'}});}
  if(path.endsWith('/reply')){replySent=true;await new Promise(resolve=>releaseReply=resolve);return route.fulfill({json:{reviewId:'review1'}});}
  if(path.endsWith('/title')){subjectPost=route.request().postData();review.title='Updated subject';return route.fulfill({json:review});}
  if(path==='/app/collaboration/reviews/review2')return route.fulfill({json:{review:{...review,id:'review2',title:'Second conversation'},messages:[],messageType:'TEAM_CHAT'}});
  if(path==='/app/collaboration/reviews/review1')return route.fulfill({json:{review,messages:[],messageType:'TEAM_CHAT'}});
  if(path.endsWith('/subject'))return route.fulfill({json:showThreads?[{review},{review:{...review,id:'review2',title:'Second conversation'}}]:[]});
  if(path.includes('/summaries'))return route.fulfill({json:{}});
  let html=fs.readFileSync('.local/browser-fixtures/personal-collaboration.html','utf8').replace(/<aside[\s\S]*?<\/aside>/,'').replace('data-user="fixture@example.test"','data-user="me@example.test"').replace('id="thread-management"','id="thread-management" data-current-email="me@example.test"');
  html=html.replace('</main>','<button data-collaboration-account="a1" data-name="Account one">Account</button></main>');
  html=html.replace('</body>','<button id="new-general" data-new-task>Create task</button><button id="initial" data-entity-type="CATALOG" data-entity-id="product1" data-title="Original product" onclick="openRecordCollaboration(this)">New record conversation</button><button id="existing" data-review-id="review1" data-subject-type="CATALOG" data-subject-key="product1" data-subject-label="Original product" onclick="openCollaborationReview(this)">Existing record conversation</button><script src="/js/general-task.js"></script><script src="/js/chat-composer.js"></script></body>');
  return route.fulfill({contentType:'text/html',body:html});
 });
 await page.goto('https://dialog.test/');await page.locator('#initial').click();
 const dialog=page.locator('#contextual-thread-dialog');const title=dialog.getByRole('textbox',{name:'Subject',exact:true});
 await title.waitFor({state:'visible'});assert.equal(await title.inputValue(),'Original product');assert.ok((await title.boundingBox()).width>400,'Subject must use the dialog width');await page.screenshot({path:'.local/collaboration-subject-final.png'});assert.equal(await title.evaluate(n=>document.activeElement===n),true);
 await dialog.locator('[data-draft-audience]').waitFor();assert.match(await dialog.locator('[data-draft-audience]').textContent(),/Assigned to you.*Nobody else/);
 assert.equal(await dialog.locator('#thread-management').isVisible(),true);assert.equal(await dialog.locator('.conversation-close-button').isVisible(),false);
 await title.fill('Product follow up');await dialog.locator('#thread-conversation-start textarea').fill('Unsent record draft');await dialog.locator('#thread-conversation-start input[type=file]').setInputFiles({name:'draft.txt',mimeType:'text/plain',buffer:Buffer.from('Private draft')});await dialog.getByRole('button',{name:'Assign to me',exact:true}).click();assert.match(await dialog.locator('[data-thread-assignees]').textContent(),/Me Tester/);
 await dialog.locator('[data-thread-dialog-close]').first().click();if(await page.getByRole('button',{name:'Discard draft',exact:true}).isVisible())await page.getByRole('button',{name:'Discard draft',exact:true}).click();await page.locator('#new-general').click();
 assert.equal(await page.locator('#general-task-dialog').count(),0);assert.equal(await dialog.locator('#thread-conversation-start textarea').inputValue(),'');assert.equal(await dialog.locator('#thread-conversation-start input[type=file]').evaluate(n=>n.files.length),0);await title.fill('Platform subject');
 await dialog.getByLabel('Task account').selectOption('a1');
 const field=dialog.locator('#thread-conversation-start textarea');await field.fill('Please @ja');await dialog.getByRole('option').filter({hasText:'Jack Tester'}).waitFor({state:'visible'});await field.press('ArrowDown');await field.press('Enter');assert.equal(await field.inputValue(),'Please @jack ');await page.waitForFunction(()=>document.querySelector('[data-thread-assignees]').textContent==='Jack Tester');assert.match(await dialog.locator('[data-draft-audience]').textContent(),/will be notified/);
 await dialog.getByRole('button',{name:'Assign to me',exact:true}).click();await dialog.locator('[data-thread-due]').click();await dialog.getByLabel('Task due date').fill('2026-12-20');
 await dialog.getByRole('button',{name:'Create task',exact:true}).click();await dialog.getByText('Fixture retained draft').waitFor();assert.match(taskPost,/Platform subject/);assert.match(taskPost,/@jack/);assert.match(taskPost,/2026-12-20/);assert.match(taskPost,/name="people"\r\n\r\nme/);
 await dialog.locator('[data-thread-dialog-close]').first().click();if(await page.getByRole('button',{name:'Discard draft',exact:true}).isVisible())await page.getByRole('button',{name:'Discard draft',exact:true}).click();await page.locator('#existing').click();await dialog.getByRole('button',{name:'Edit subject',exact:true}).waitFor();
 assert.equal(await dialog.locator('#thread-record-title').textContent(),'Saved subject');await dialog.locator('#thread-conversation-reply textarea').fill('Unsent reply');await dialog.locator('#thread-conversation-reply input[type=file]').setInputFiles({name:'unsent.txt',mimeType:'text/plain',buffer:Buffer.from('Must not submit with subject')});await dialog.getByRole('button',{name:'Edit subject',exact:true}).click();await title.fill('Updated subject');await dialog.getByRole('button',{name:'Save',exact:true}).click();
 await page.waitForFunction(()=>document.querySelector('#thread-record-title').textContent==='Updated subject');assert.match(subjectPost,/Updated subject/);assert.doesNotMatch(subjectPost,/Unsent reply|unsent.txt|Must not submit/);assert.equal(review.subjectLabel,'Original product');
 const reply=dialog.locator('#thread-conversation-reply textarea');await dialog.locator('#thread-conversation-reply input[type=file]').setInputFiles([]);await reply.fill('Enter sends once');
 const before=await dialog.boundingBox();await reply.press('Enter');await page.waitForFunction(()=>document.querySelector('#thread-conversation-reply').dataset.saving==='true');
 const during=await dialog.boundingBox();assert.equal(await reply.isVisible(),true);assert.equal(await dialog.locator('.conversation-loading').count(),0);assert.equal(Math.round(during.width),Math.round(before.width));assert.equal(Math.round(during.height),Math.round(before.height));assert.equal(replySent,true);await reply.fill("Newer unsent draft");releaseReply();await page.waitForFunction(()=>!document.querySelector('#thread-conversation-reply').dataset.saving);assert.equal(await reply.inputValue(),'Newer unsent draft');
 showThreads=true;await dialog.locator('[data-thread-dialog-close]').first().click();if(await page.getByRole('button',{name:'Discard draft',exact:true}).isVisible())await page.getByRole('button',{name:'Discard draft',exact:true}).click();await page.locator('#initial').click();await dialog.locator('[data-conversation-id=review1]').click();await dialog.locator('[data-conversation-back]').waitFor();
 await dialog.locator('#thread-conversation-reply textarea').fill('Unsent conversation A');await dialog.locator('#thread-conversation-reply input[type=file]').setInputFiles({name:'conversation-a.txt',mimeType:'text/plain',buffer:Buffer.from('A only')});
 await dialog.locator('[data-conversation-back]').click();await dialog.locator('[data-conversation-id=review2]').click();await page.waitForFunction(()=>document.querySelector('#thread-record-title').textContent==='Second conversation');
 assert.equal(await dialog.locator('#thread-conversation-reply textarea').inputValue(),'');assert.equal(await dialog.locator('#thread-conversation-reply input[type=file]').evaluate(n=>n.files.length),0);

 }finally{await browser.close();}
});
test('late members from a previous account never enter the new task draft',async()=>{
 const browser=await chromium.launch({channel:'chrome',headless:true});try{
 const page=await browser.newPage();page.setDefaultTimeout(5000);let releaseA;
 const pendingA=new Promise(resolve=>releaseA=resolve);
 await page.route('https://race.test/**',async route=>{
  const url=new URL(route.request().url()),path=url.pathname;
  if(path.startsWith('/images/'))return route.abort();
  if(path.startsWith('/js/')||path.startsWith('/css/'))return route.fulfill({path:'src/main/resources/static'+path,contentType:path.startsWith('/js/')?'application/javascript':'text/css'});
  if(path.endsWith('/personal/data'))return route.fulfill({json:{rows:[],windows:[]}});
  if(path.endsWith('/members')){const a=url.searchParams.get('account');if(a==='a')await pendingA;return route.fulfill({json:[{id:a,email:a+'@example.test',name:'Member '+a,handle:a}]});}
  let html=fs.readFileSync('.local/browser-fixtures/personal-collaboration.html','utf8').replace(/<aside[\s\S]*?<\/aside>/,'');
  html=html.replace('</main>','<button data-collaboration-account="a" data-name="Account A">A</button><button data-collaboration-account="b" data-name="Account B">B</button></main>').replace('</body>','<button data-new-task>Create task</button><script src="/js/general-task.js"></script></body>');
  return route.fulfill({contentType:'text/html',body:html});
 });
 await page.goto('https://race.test/');await page.locator('[data-new-task]').first().click();
 const picker=page.getByLabel('Task account');await picker.selectOption('a');await picker.selectOption('b');
 await page.locator('[data-thread-assignment-options]').getByText('Member b',{exact:true}).waitFor({state:'attached'});const settledA=page.waitForResponse(r=>r.url().includes('account=a'));releaseA();
 await settledA;await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
 assert.equal(await page.locator('[data-thread-assignment-options]').textContent(),'Member b');
 assert.equal(await page.locator('#thread-record-type').textContent(),'Account B');
 }finally{await browser.close();}
});

test('only Older pages25 and zero date groups hide',async()=>{
 const browser=await chromium.launch({channel:'chrome',headless:true});try{
 const context=await browser.newContext();const page=await context.newPage();page.setDefaultTimeout(5000);
 await context.route('https://uat.test/**',async route=>{
  const url=new URL(route.request().url()),path=url.pathname;
  if(path.endsWith('/personal/data'))return route.fulfill({json:{rows:Array.from({length:70},(_,i)=>({position:i+1,card:{key:{type:'REVIEW',id:'r'+i},title:'Task '+i,context:'Account',account:'account',state:'OPEN'}})),reviews:Object.fromEntries(Array.from({length:70},(_,i)=>['r'+i,{id:'r'+i,status:'ACTIVE',title:'Task '+i,subjectLabel:'Product '+i,assigneeEmail:i===0?'jack@example.test':'me@example.test',messageCount:2}])),dates:Object.fromEntries(Array.from({length:70},(_,i)=>['REVIEWr'+i,i<12?'2026-11-03T00:00:00Z':'2026-09-01T00:00:00Z'])),windows:[{key:'THIS_WEEK',from:'2026-11-02T00:00:00Z',until:'2026-11-09T00:00:00Z'},{key:'LAST_WEEK',from:'2026-10-26T00:00:00Z',until:'2026-11-02T00:00:00Z'},{key:'OLDER',from:null,until:'2026-10-01T00:00:00Z'}],revision:1}});
  if(path.startsWith('/images/'))return route.abort();
  if(path.startsWith('/js/')||path.startsWith('/css/'))return route.fulfill({path:'src/main/resources/static'+path,contentType:path.startsWith('/js/')?'application/javascript':'text/css'});
  let html=fs.readFileSync('src/main/resources/templates/personal-collaboration.html','utf8').replace(/<aside[\s\S]*?<\/aside>/,'').replace('th:attr="data-user=${personalUser}"','data-user="me@example.test"');return route.fulfill({contentType:'text/html',body:html});
 });
 await page.goto('https://uat.test/');await page.locator('tr[data-card-id]').first().waitFor();await page.evaluate(()=>sessionStorage.setItem('collaboration.personal.me@example.test',JSON.stringify({extent:{'ASSIGNED|||||Older':5}})));await page.reload();await page.locator('tr[data-card-id]').first().waitFor();
 assert.equal(await page.getByRole('button',{name:/Last week/}).count(),0);
 await page.getByRole('button',{name:/This week/}).click();assert.equal(await page.locator('tr[data-card-id]').count(),12);assert.equal(await page.getByRole('button',{name:'Load more',exact:true}).count(),0);
 await page.getByRole('button',{name:/Older/}).click();assert.equal(await page.locator('tr[data-card-id]').count(),37);
 await page.getByRole('button',{name:'Load more',exact:true}).click();assert.equal(await page.locator('tr[data-card-id]').count(),62);
 await page.getByRole('button',{name:'Load more',exact:true}).click();assert.equal(await page.locator('tr[data-card-id]').count(),70);assert.equal(await page.getByRole('button',{name:'Load more',exact:true}).count(),0);
 }finally{await browser.close();}
});
