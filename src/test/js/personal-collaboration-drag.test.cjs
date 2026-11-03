const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const {chromium}=require('playwright');

test('foundation retains one table across tabs with five initial rows and disjoint time groups',async()=>{
    const browser=await chromium.launch({channel:'chrome',headless:true});
    try {
        const page=await browser.newPage();
        const all=Array.from({length:14},(_,i)=>({position:i<13?i+1:null,card:{
            title:'Conversation '+i,key:{type:'TASK',id:'item'+i},state:i<13?'OPEN':'CLOSED',context:'Shared'}}));
        let writes=0,nativeDialogs=0,moves=0;
        page.on('dialog',async dialog=>{nativeDialogs++;await dialog.dismiss();});
        await page.route('https://personal.test/**',async route=>{
            const url=new URL(route.request().url()),path=url.pathname;
            if(path==='/app/collaboration/personal/position'){
                const data=new URLSearchParams(route.request().postData());
                assert.equal(data.get('revision'),'5');assert.equal(data.has('owner'),false);
                const from=all.findIndex(r=>r.card.key.id===data.get('id'));
                const [row]=all.splice(from,1),target=all.findIndex(r=>r.card.key.id===data.get('target'));
                all.splice(target+(data.get('placement')==='AFTER'?1:0),0,row);
                all.slice(0,13).forEach((r,i)=>r.position=i+1);moves++;
                return route.fulfill({json:{}});
            }
            if(path==='/app/collaboration/personal/data'){
                const scope=url.searchParams.get('scope');
                let rows=scope==='CLOSED'?all.slice(13):scope==='PRIVATE'?[]:all.slice(0,13);
                if(url.searchParams.get('q')==='rank gaps')rows=rows.filter(r=>['item0','item2'].includes(r.card.key.id));
                return route.fulfill({json:{rows,revision:5,reviews:Object.fromEntries(rows.map(r=>[r.card.key.id,{id:r.card.key.id,subjectLabel:r.card.title,status:r.card.state==='CLOSED'?'CLOSED':'ACTIVE',participants:'Barry Guze, Jack A',assigneeName:'Barry Guze',messageCount:2}])),dates:Object.fromEntries(rows.map(r=>['TASK'+r.card.key.id,'2026-10-15T00:00:00Z'])),
                    windows:[{key:'THIS_WEEK',from:'2026-11-02T08:00:00Z',until:'2026-11-03T00:00:00Z'},{key:'LAST_WEEK',from:'2026-10-26T07:00:00Z',until:'2026-11-02T08:00:00Z'},{key:'LAST_MONTH',from:'2026-10-01T00:00:00Z',until:'2026-11-01T00:00:00Z'}]}});
            }
            if(path==='/app/collaboration/personal/state'){writes++;return route.fulfill({status:204});}
            if(path.startsWith('/images/'))return route.abort();
            const file=/^\/(js|css)\//.test(path)?'src/main/resources/static'+path:'src/main/resources/templates/personal-collaboration.html';
            const body=fs.readFileSync(file,'utf8')
                .replace('th:action="@{/app/collaboration/personal/position}"','action="/app/collaboration/personal/position"')
                .replace('th:attr="data-user=${personalUser}"','data-user="test"')
                .replace(/<aside[\s\S]*?<\/aside>/,'').replace('<body ','<body style="display:block" ')
                .replace('<main ','<main style="margin:0;width:100%" ');
            return route.fulfill({contentType:path.startsWith('/js/')?'application/javascript':path.startsWith('/css/')?'text/css':'text/html',body});
        });
        await page.goto('https://personal.test/app/collaboration');
        await page.locator('tr[data-card-id]').first().waitFor();
        assert.equal(await page.locator('tr[data-card-id]').count(),5);
        assert.deepEqual(await page.locator('.personal-group-toggle').allTextContents(),['Last month · 8']);
        assert.equal(await page.getByRole('button',{name:'This week · 0',exact:true}).count(),0);
        assert.equal(await page.getByRole('button',{name:'Last week · 0',exact:true}).count(),0);
        assert.equal(await page.locator('#personal-status').textContent(),'13 assigned conversations across all accessible accounts · Showing 5');
        assert.equal(await page.locator('tr[draggable=true]').count(),5);
        const lefts=await page.locator('.collaboration-record-copy>strong').evaluateAll(nodes=>nodes.map(n=>n.getBoundingClientRect().left));
        assert.ok(Math.max(...lefts)-Math.min(...lefts)<1);
        assert.deepEqual(await page.locator('thead th').allTextContents().then(v=>v.slice(0,2)),['Record','Priority']);
        const grip=await page.locator('#TASKitem1 [data-column=priority] .personal-priority-handle').boundingBox();
        const target=await page.locator('#TASKitem0').boundingBox();
        await page.mouse.move(grip.x+grip.width/2,grip.y+grip.height/2);await page.mouse.down();
        await page.mouse.move(grip.x+grip.width/2+8,grip.y+grip.height/2,{steps:3});
        await page.mouse.move(target.x+300,target.y+15,{steps:8});
        await page.mouse.move(target.x+301,target.y+16);
        await page.screenshot({path:'.local/priority-drag-debug.png'});
        await page.locator('#TASKitem0.personal-drop-before').waitFor({timeout:5000});await page.mouse.up();
        await page.waitForFunction(()=>document.querySelector('tr[data-card-id]').id==='TASKitem1');
        assert.equal(moves,1);
        assert.equal(await page.locator('#TASKitem1 [data-priority-number]').textContent(),'1');
        await page.locator('#TASKitem1 [data-column=priority] .personal-priority-handle').press('Alt+ArrowDown');
        await page.waitForFunction(()=>document.querySelector('tr[data-card-id]').id==='TASKitem0');
        assert.equal(moves,2);
        const subjectGrip=await page.locator('#TASKitem1 .personal-subject-handle').boundingBox();
        const unchangedTarget=await page.locator('#TASKitem2').boundingBox();
        await page.mouse.move(subjectGrip.x+subjectGrip.width/2,subjectGrip.y+subjectGrip.height/2);await page.mouse.down();
        await page.mouse.move(subjectGrip.x+subjectGrip.width/2+8,subjectGrip.y+subjectGrip.height/2,{steps:3});
        await page.mouse.move(unchangedTarget.x+300,unchangedTarget.y+15,{steps:8});await page.mouse.move(unchangedTarget.x+301,unchangedTarget.y+16);
        await page.locator('#TASKitem2.personal-drop-unchanged').waitFor({timeout:5000});await page.mouse.up();assert.equal(moves,2);
        await page.locator('#TASKitem1 .personal-subject-handle').press('Alt+ArrowUp');
        await page.waitForFunction(()=>document.querySelector('tr[data-card-id]').id==='TASKitem1');assert.equal(moves,3);
        await page.locator('#TASKitem1 .personal-subject-handle').press('Alt+ArrowDown');
        await page.waitForFunction(()=>document.querySelector('tr[data-card-id]').id==='TASKitem0');assert.equal(moves,4);
        await page.reload();await page.locator('tr[data-card-id]').first().waitFor();
        assert.equal(await page.locator('tr[data-card-id]').first().getAttribute('id'),'TASKitem0');
        await page.evaluate(()=>window.originalHeader=document.querySelector('thead'));
        await page.getByRole('button',{name:/Last month/i}).click();
        assert.equal(await page.locator('tr[data-card-id]').count(),13);
        assert.equal(await page.locator('#personal-status').textContent(),'13 assigned conversations across all accessible accounts · Showing 13');
        const heading=page.getByRole('button',{name:/Last month/i});
        assert.match(await heading.textContent(),/Last month · 8/);
        assert.equal(await heading.evaluate(n=>n.closest('tbody').firstElementChild===n.closest('tr')),true);
        const more=page.getByRole('button',{name:'Load more',exact:true});
        assert.equal(await more.count(),0);
        assert.equal(await page.locator('tr[data-card-id]').count(),13);
        assert.doesNotMatch(await page.locator('.collaboration-participants').first().textContent(),/Barry|Jack/);
        assert.match(await page.locator('.collaboration-participants [role=img]').first().getAttribute('aria-label'),/Barry Guze, Jack A/);
        assert.doesNotMatch(await page.locator('.assignment-trigger').first().textContent(),/Barry/);
        assert.match(await page.locator('.assignment-trigger').first().getAttribute('aria-label'),/Barry Guze/);
        assert.equal(await page.locator('.table-pagination').count(),0);
        assert.equal(await page.locator('#personal-account').count(),0);
        for(const scope of ['ACTIVE','PRIVATE','CLOSED','ASSIGNED']){
            await page.locator('[data-personal-scope="'+scope+'"]').click();
            await page.waitForFunction(value=>document.querySelector('#personal-queue').dataset.loadedScope===value,scope);
            assert.equal(await page.evaluate(()=>window.originalHeader===document.querySelector('thead')),true);
            assert.equal(await page.locator('#personal-search').count(),1);
            assert.equal(await page.locator('thead [data-column=priority]').count(),scope==='ASSIGNED'?1:0);
            assert.equal(await page.locator('tr[draggable=true]').count(),scope==='ASSIGNED'?13:0);
        }
        const ids=await page.locator('tr[data-card-id]').evaluateAll(rows=>rows.map(r=>r.id));
        assert.equal(ids.length,new Set(ids).size);
        await page.locator('tr[data-card-id] .table-row-warning summary').first().click();
        await page.locator('tr[data-card-id]').first().locator('.table-action-label strong').filter({hasText:/^Close$/}).click();
        await page.locator('#platform-confirm-dialog').waitFor({state:'visible'});
        await page.locator('[data-confirm-cancel]').click();
        assert.equal(writes,0);
        assert.equal(nativeDialogs,0);
        await page.locator('#personal-search').fill('rank gaps');
        await page.waitForFunction(()=>document.querySelectorAll('tr[data-card-id]').length===2);
        const gapGrip=await page.locator('#TASKitem0 [data-column=priority] .personal-priority-handle').boundingBox();
        const gapTarget=await page.locator('#TASKitem2').boundingBox();
        await page.mouse.move(gapGrip.x+gapGrip.width/2,gapGrip.y+gapGrip.height/2);await page.mouse.down();
        await page.mouse.move(gapGrip.x+gapGrip.width/2+8,gapGrip.y+gapGrip.height/2,{steps:3});
        await page.mouse.move(gapTarget.x+300,gapTarget.y+15,{steps:8});await page.mouse.move(gapTarget.x+301,gapTarget.y+16);
        await page.locator('#TASKitem2.personal-drop-before:not(.personal-drop-unchanged)').waitFor({timeout:5000});await page.mouse.up();
        await page.waitForFunction(()=>document.querySelector('#TASKitem0 [data-priority-number]').textContent==='2');
        assert.equal(moves,5);assert.equal(all.find(r=>r.card.key.id==='item1').position,1);

    } finally {await browser.close();}
});

test('task creation mirrors selected account and uses the shared dialog',async()=>{
 const browser=await chromium.launch({channel:'chrome',headless:true});try{
  const page=await browser.newPage();page.setDefaultTimeout(5000);
  await page.route('https://scope.test/**',async route=>{
   const url=new URL(route.request().url()),path=url.pathname;
   if(path.startsWith('/images/'))return route.abort();
   if(path.startsWith('/js/')||path.startsWith('/css/'))return route.fulfill({path:'src/main/resources/static'+path,contentType:path.startsWith('/js/')?'application/javascript':'text/css'});
   if(path.endsWith('/members'))return route.fulfill({json:[]});
   if(path.endsWith('/personal/data'))return route.fulfill({json:{rows:[],windows:[]}});
   let html=fs.readFileSync('.local/browser-fixtures/personal-collaboration.html','utf8').replace(/<aside[\s\S]*?<\/aside>/,'');
   html=html.replace('</main>','<details open><summary>Choose fixture account</summary><button data-collaboration-account="one" data-name="Account one" data-logo="/images/one.png">Account one</button></details></main>').replace('</body>','<button data-new-task>Create task</button><script src="/js/general-task.js"></script></body>');
   return route.fulfill({contentType:'text/html',body:html});
  });
  await page.goto('https://scope.test/');await page.locator('[data-new-task]').first().click();const dialog=page.locator('#contextual-thread-dialog');
  assert.equal(await page.locator('#general-task-dialog').count(),0);assert.equal(await dialog.locator('#thread-record-type').textContent(),'All accounts');
  await dialog.getByLabel('Task account').selectOption('one');assert.equal(await dialog.locator('#thread-record-type').textContent(),'Account one');assert.match(await dialog.locator('#thread-account-logo').getAttribute('src'),/one.png/);
  await dialog.locator('[data-thread-dialog-close]').first().click();await page.locator('[data-collaboration-account=one]').click();await page.waitForFunction(()=>document.querySelector('#personal-queue').dataset.filterAccount==='one');await page.locator('[data-new-task]').first().click();
  assert.equal(await dialog.getByLabel('Task account').isVisible(),false);assert.equal(await dialog.locator('#thread-record-type').textContent(),'Account one');
 }finally{await browser.close();}
});

test('task artwork and the shared detail popup work in every tab without navigating or losing filters',async()=>{
    const browser=await chromium.launch({channel:'chrome',headless:true});
    try{
        const page=await browser.newPage();page.setDefaultTimeout(5000);let reads=0,withoutPicture=false,restricted=false,nativeReads=0;
        const detail={id:'review1',subjectType:'CATALOG',subjectKey:'product1',subjectLabel:'A long product name that must remain readable',title:'Product task',status:'ACTIVE',messageCount:1,privateNoteCount:0,participants:'Fixture',contextSnapshot:'{}',parentUrl:'/app/catalog?q=product1'};
        await page.route('https://details.test/**',async route=>{
            const url=new URL(route.request().url()),path=url.pathname;
            if(path.startsWith('/images/'))return route.abort();
            if(path.startsWith('/js/')||path.startsWith('/css/'))return route.fulfill({path:'src/main/resources/static'+path,contentType:path.startsWith('/js/')?'application/javascript':'text/css'});
            if(path.endsWith('/personal/data')){
                const closed=url.searchParams.get('scope')==='CLOSED';
                return route.fulfill({json:{rows:[{position:1,card:{key:{type:'TASK',id:'task1'},parent:'parent1',account:restricted?null:'account1',contextAccount:'account1',sourceRestricted:restricted,title:'Product task',context:'Account',state:closed?'DONE':'OPEN'}}],revision:1,
                    reviews:restricted?{}:{task1:{...detail,status:closed?'CLOSED':'ACTIVE'}},pictures:withoutPicture?{}:{task1:'https://details.test/images/product.png'},dates:{TASKtask1:'2026-10-15T00:00:00Z'},editableAccounts:['account1'],windows:[]}});
            }
            if(path==='/app/collaboration/personal/context/review1/reviews/review1'){
                reads++;return route.fulfill({json:{review:detail,messages:[],messageType:'TEAM_CHAT'}});
            }
            if(path.endsWith('/members'))return route.fulfill({json:[]});
            if(path==='/app/collaboration/board/tasks/task1/messages'){nativeReads++;return route.fulfill({json:[]});}
            if(path.startsWith('/app/collaboration/')&&path!=='/app/collaboration/')return route.fulfill({json:{}});
            let html=fs.readFileSync('.local/browser-fixtures/personal-collaboration.html','utf8').replace(/<aside[\s\S]*?<\/aside>/,'').replace('<body ','<body style="display:block" ').replace('<main ','<main style="margin:0;width:100%" ');
            html=html.replace('</main>','<details open><summary>Fixture account</summary><button type="button" data-collaboration-account="account1" data-name="Account" data-logo="/images/account.png">Account</button><button type="button" data-collaboration-account="account1" data-collaboration-store="other-store" data-name="Other store" data-logo="/images/account.png">Other store</button></details></main>');
            return route.fulfill({contentType:'text/html',body:html});
        });
        await page.goto('https://details.test/');
        await page.locator('tr[data-card-id]').first().waitFor();
        for(const scope of ['ASSIGNED','ACTIVE','CLOSED']){
            if(await page.locator('#personal-queue').getAttribute('data-loaded-scope')!==scope){
                await page.locator('[data-personal-scope="'+scope+'"]').click();
                await page.waitForFunction(scope=>document.querySelector('#personal-queue').dataset.loadedScope===scope,scope);
            }
            assert.equal(await page.locator('tr[data-card-id]').first().getAttribute('data-image'),'https://details.test/images/product.png');
            assert.equal(await page.locator('.collaboration-open-thread').first().evaluate(n=>getComputedStyle(n).borderTopWidth),'0px');
            await page.locator('tr[data-card-id] .collaboration-row-button').click();
            await page.locator('#contextual-thread-dialog[open]').waitFor();
            await page.waitForFunction(()=>document.querySelector('#thread-parent-link')?.getAttribute('href')==='/app/catalog?q=product1');
            assert.equal(page.url(),'https://details.test/');
            assert.equal(await page.locator('#contextual-thread-dialog [data-thread-tab=PRIVATE_NOTE]').isVisible(),false);
            await page.waitForFunction(title=>document.querySelector('#thread-record-title').textContent===title,detail.title);
            assert.equal(await page.locator('#thread-record-title').textContent(),detail.title);
            assert.equal(await page.locator('#contextual-thread-dialog').getAttribute('data-personal'),'true');
            await page.locator('[data-thread-dialog-close]').first().click();
        }
        assert.ok(reads>=3);
        withoutPicture=true;await page.reload();await page.locator('tr[data-card-id]').first().waitFor();
        assert.equal(await page.locator('tr[data-card-id]').first().getAttribute('data-image'),'/images/account.png');
        await page.locator('[data-collaboration-store="other-store"]').click();
        assert.equal(await page.locator('tr[data-card-id]').count(),1);
        assert.equal(await page.locator('tr[data-card-id]').first().getAttribute('data-image'),'/images/account.png');
        const sourceReads=reads;restricted=true;await page.reload();await page.locator('tr[data-card-id]').first().waitFor();
        await page.locator('tr[data-card-id] .collaboration-row-button').click();
        await page.getByText('Source access required. Ask an account administrator for access to the original record.',{exact:true}).waitFor();
        await page.getByText('No messages yet.',{exact:true}).waitFor();assert.equal(reads,sourceReads);assert.equal(nativeReads,1);
        assert.equal(await page.locator('dialog[open] #thread-parent-link').count(),0);
    }finally{await browser.close();}
});
