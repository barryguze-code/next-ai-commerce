const {test}=require('node:test');
const assert=require('node:assert/strict');
const path=require('node:path');
const {chromium}=require('playwright');
test('room teammate list refreshes after presence changes and opens directly',async()=>{
 const browser=await chromium.launch({headless:true,channel:'chrome'});
 try{
 const page=await browser.newPage();
 await page.setContent('<header class="workspace-header"></header>');
 await page.addStyleTag({path:path.resolve('src/main/resources/static/css/huddle-floating.css')});
 await page.addStyleTag({path:path.resolve('src/main/resources/static/css/chat-composer.css')});
 await page.addScriptTag({path:path.resolve('src/main/resources/static/js/huddle-floating.js')});
 await page.evaluate(()=>{
 window.sent=[];window.sender=e=>{sent.push(e);return true;};
 LiveHuddleUI.event({type:'WELCOME',self:{id:'me'},canHuddle:true,online:[],huddles:[]},sender);
 LiveHuddleUI.event({type:'HUDDLE_STARTED',huddle:{id:'room',status:'ACTIVE',participants:[{id:'me',name:'Me'},{id:'other',name:'Other'}],messages:[]}},sender);
 LiveHuddleUI.event({type:'ROOM_PEOPLE',huddleId:'room',online:[]},sender);
 sent.length=0;
 LiveHuddleUI.event({type:'PRESENCE',online:[{id:'new',name:'New teammate'}]},sender);
 });
 assert.ok(await page.evaluate(()=>sent.some(e=>e.type==='PEOPLE'&&e.huddleId==='room')),'refresh scoped roster on presence change');
 await page.evaluate(()=>LiveHuddleUI.event({type:'ROOM_PEOPLE',huddleId:'room',online:[{id:'new',name:'New teammate'}]},sender));
 await page.getByRole('button',{name:'Add teammate',exact:true}).click();
 assert.equal(await page.locator('.floating-huddle select').count(),0);
 const firstRequests=await page.evaluate(()=>sent.filter(e=>e.type==='PEOPLE').length);
 await page.getByRole('button',{name:'Add teammate',exact:true}).click();
 assert.ok(await page.evaluate(()=>sent.filter(e=>e.type==='PEOPLE').length)>firstRequests);
 assert.equal(await page.locator('.huddle-mention-options').isVisible(),true);

 await page.getByRole('button',{name:/New teammate/}).focus();
 await page.evaluate(()=>LiveHuddleUI.event({type:'ROOM_PEOPLE',huddleId:'room',online:[{id:'new',name:'New teammate'}]},sender));
 assert.equal(await page.getByRole('button',{name:/New teammate/}).evaluate(n=>n===document.activeElement),true);
 await page.locator('.floating-huddle').screenshot({path:path.resolve('../teammate-list.png')});
 await page.getByRole('button',{name:/New teammate/}).click();
 assert.ok(await page.evaluate(()=>sent.some(e=>e.type==='INVITE'&&e.participantIds[0]==='new')));
 await page.evaluate(()=>LiveHuddleUI.event({type:'HUDDLE_UPDATED',huddle:{id:'room',status:'ACTIVE',participants:[{id:'me',name:'Me'},{id:'other',name:'Other'},{id:'three',name:'Third'},{id:'four',name:'Fourth'},{id:'five',name:'Fifth'}],messages:[]}},sender));
 await page.getByRole('button',{name:'Add teammate',exact:true}).click();
 assert.equal(await page.getByRole('button',{name:/New teammate/}).isDisabled(),true);
 await page.getByRole('textbox',{name:'Huddle message'}).press('Escape');
 const field=page.getByRole('textbox',{name:'Huddle message'});
 await field.fill('@Other');
 assert.equal(await page.locator('.huddle-mention-options').getByRole('button',{name:/Other/}).isEnabled(),true);
 await field.press('Escape');
 assert.equal(await page.locator('.huddle-mention-options').isVisible(),false);

 }finally{await browser.close();}
});
