const {test}=require('node:test'),assert=require('node:assert/strict'),{chromium}=require('playwright');
test('standard close stays visible; finalize cancel preserves room and saves explicit title assignment due date',async()=>{
 const b=await chromium.launch({headless:true});try{const p=await b.newPage({viewport:{width:1280,height:500}});p.setDefaultTimeout(2000);
 const fs=require('node:fs'),fragment=fs.readFileSync('src/main/resources/templates/fragments/collaboration-thread.html','utf8');const modal=fragment.slice(fragment.indexOf('<dialog id="contextual-thread-dialog"'),fragment.indexOf('</dialog>')+9);
 await p.route('http://finalize.test/**',r=>{const url=new URL(r.request().url());if(url.pathname.startsWith('/images/'))return r.fulfill({path:'src/main/resources/static'+url.pathname});return r.fulfill({contentType:'text/html; charset=utf-8',body:'<body class="app-page"><header class="workspace-header"></header>'+modal+'</body>'})});await p.goto('http://finalize.test/');
 for(const file of ['app.css','brand.css','platform-ui.css','ux-components.css','collaboration-thread.css','huddle-floating.css','chat-composer.css','collaboration-task-dates.css'])await p.addStyleTag({path:'src/main/resources/static/css/'+file});
 await p.evaluate(()=>{window.WebSocket=class{static OPEN=1;readyState=0;addEventListener(){}send(){}close(){}}});
 for(const file of ['huddle-floating.js','collaboration.js','ux-components.js','platform-tooltips.js'])await p.addScriptTag({path:'src/main/resources/static/js/'+file});
 await p.evaluate(()=>{window.sent=[];const me={id:'me',name:'Barry Guze'},other={id:'other',name:'Ecem B'};window.sender=x=>{sent.push(x);return true};LiveHuddleUI.event({type:'WELCOME',self:me,canHuddle:true,online:[me,other],huddles:[{id:'room',status:'ACTIVE',startedBy:me,participants:[me,other],messages:[{senderId:'other',senderName:'Ecem B',body:'History must remain visible',createdAt:'2026-11-04T20:00:00Z'}]}]},sender)});
 const close=p.getByRole('button',{name:'Close collaboration',exact:true});
 assert.equal(await close.evaluate(el=>getComputedStyle(el,'::before').content),'""');
 assert.equal(await close.getAttribute('data-tooltip'),null);
 await close.click();
 await p.locator('#contextual-thread-dialog').getByText('History must remain visible',{exact:true}).waitFor({state:'visible'});
 await p.getByRole('textbox',{name:'Subject',exact:true}).fill('Check stock');
 await p.getByRole('button',{name:'Cancel',exact:true}).click();assert.equal(await p.locator('.floating-huddle').count(),1);assert.equal(await p.evaluate(()=>sent.some(x=>['SAVE','LEAVE'].includes(x.type))),false);
 await close.click();assert.equal(await p.getByRole('textbox',{name:'Subject',exact:true}).inputValue(),'Check stock');
 await p.locator('#thread-management .thread-assignment summary').click();await p.getByRole('checkbox',{name:'Ecem B',exact:true}).check();
 const overlay=p.locator('.thread-assignment-popover');assert.equal(await overlay.evaluate(n=>n.matches(':popover-open')),true);
 const checkOverlay=async()=>{const box=await overlay.boundingBox();assert.ok(box.y>=0&&box.y+box.height<=await p.evaluate(()=>innerHeight));};await checkOverlay();
 await p.locator('.thread-assignment summary').evaluate(n=>n.style.transform='translateY(-1000px)');await p.evaluate(()=>dispatchEvent(new Event('resize')));await checkOverlay();
 await p.locator('.thread-assignment summary').evaluate(n=>n.style.transform='');await p.setViewportSize({width:1280,height:350});await checkOverlay();await p.setViewportSize({width:1280,height:500});

await p.getByRole('button',{name:'Apply assignment',exact:true}).click();
 await p.getByRole('button',{name:'Add due date',exact:true}).click();await p.locator('[data-thread-draft-date] input').fill('2026-11-12');
 if(process.env.HUDDLE_SCREENSHOT)await p.screenshot({path:process.env.HUDDLE_SCREENSHOT});
 await p.getByRole('button',{name:'Save as a task',exact:true}).click();const saved=await p.evaluate(()=>sent.find(x=>x.type==='SAVE'));assert.equal(saved.title,'Check stock');assert.deepEqual(saved.assigneeIds,['me','other']);assert.equal(saved.dueDate,'2026-11-12');assert.equal(await p.getByRole('button',{name:'Save as a task',exact:true}).isDisabled(),true);await p.evaluate(()=>LiveHuddleUI.event({type:'ERROR',action:'PEOPLE',huddleId:'elsewhere',message:'Unrelated'},sender));assert.equal(await p.getByRole('button',{name:'Save as a task',exact:true}).isDisabled(),true);await p.evaluate(()=>LiveHuddleUI.event({type:'ERROR',action:'SAVE',huddleId:'room',message:'Save failed'},sender));await p.locator('[data-thread-title-error]').waitFor();assert.equal(await p.getByRole('button',{name:'Save as a task',exact:true}).isEnabled(),true);await p.evaluate(()=>{const original=setTimeout;window.setTimeout=(fn,ms,...args)=>ms===20000?(window.saveTimeout=fn,999):original(fn,ms,...args)});await p.getByRole('button',{name:'Save as a task',exact:true}).click();await p.evaluate(()=>saveTimeout());await p.getByText('Save confirmation timed out. Your draft is still here; retry safely.',{exact:true}).waitFor();assert.equal(await p.getByRole('button',{name:'Save as a task',exact:true}).isEnabled(),true);await p.getByRole('button',{name:'Save as a task',exact:true}).click();await p.evaluate(()=>LiveHuddleUI.event({type:'HUDDLE_SAVED',huddle:{id:'room'}},sender));await p.locator('#contextual-thread-dialog').waitFor({state:'hidden'});assert.equal(await p.locator('.floating-huddle').count(),0);await p.evaluate(()=>{const me={id:'me',name:'Barry Guze'},other={id:'other',name:'Ecem B'};LiveHuddleUI.event({type:'HUDDLE_STARTED',huddle:{id:'ended-room',status:'ACTIVE',startedBy:me,participants:[me,other],messages:[]}},sender)});await p.getByRole('button',{name:'Close collaboration',exact:true}).click();await p.getByRole('textbox',{name:'Subject',exact:true}).fill('Ended room draft');await p.getByRole('button',{name:'Save as a task',exact:true}).click();await p.evaluate(()=>LiveHuddleUI.event({type:'HUDDLE_ENDED',huddle:{id:'ended-room'}},sender));await p.getByText('The collaboration ended before saving was confirmed. Your draft is still here.',{exact:true}).waitFor();assert.equal(await p.locator('#contextual-thread-dialog').isVisible(),true);
 await p.getByRole('button',{name:'Cancel',exact:true}).click();
 await p.evaluate(()=>{const me={id:'me',name:'Barry Guze'},other={id:'other',name:'Ecem B'};LiveHuddleUI.event({type:'HUDDLE_STARTED',huddle:{id:'leave-room',status:'ACTIVE',startedBy:me,participants:[me,other],joinedParticipantIds:['me','other'],messages:[]}},sender);sent.length=0;});
 await p.getByRole('button',{name:'Close collaboration',exact:true}).click();
 await p.getByRole('button',{name:'Leave without saving',exact:true}).click();
 assert.equal(await p.getByRole('button',{name:'Leave without saving',exact:true}).isDisabled(),true);
 assert.deepEqual(await p.evaluate(()=>sent),[{type:'LEAVE',huddleId:'leave-room'}]);
 await p.evaluate(()=>LiveHuddleUI.event({type:'ERROR',action:'LEAVE',huddleId:'leave-room',message:'Leave failed'},sender));
 await p.locator('#contextual-thread-dialog').getByText('Leave failed',{exact:true}).waitFor();assert.equal(await p.getByRole('button',{name:'Leave without saving',exact:true}).isEnabled(),true);
 await p.getByRole('button',{name:'Leave without saving',exact:true}).click();
 await p.evaluate(()=>LiveHuddleUI.event({type:'HUDDLE_UPDATED',huddle:{id:'leave-room',status:'ACTIVE',participants:[{id:'me'},{id:'other'}],joinedParticipantIds:['other'],messages:[]}},sender));
 await p.locator('#contextual-thread-dialog').waitFor({state:'hidden'});assert.equal(await p.locator('.floating-huddle').count(),0);
 assert.equal(await p.evaluate(()=>sent.some(e=>e.type==='SAVE'||e.type==='END')),false);

 }finally{await b.close()}
});
