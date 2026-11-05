const {test}=require('node:test'),assert=require('node:assert/strict'),{chromium}=require('playwright');
for(const empty of [true,false])test(empty?'empty collaboration closes directly':'leaving permits restarting the existing collaboration',async()=>{
 const b=await chromium.launch({headless:true,channel:'chrome'});try{const p=await b.newPage();
 await p.route('http://close.test/**',r=>r.fulfill({contentType:'text/html',body:'<body class="app-page"><header class="workspace-header"></header></body>'}));await p.goto('http://close.test/');
 await p.addStyleTag({path:'src/main/resources/static/css/huddle-floating.css'});await p.addScriptTag({path:'src/main/resources/static/js/huddle-floating.js'});
 await p.evaluate(empty=>{window.sent=[];window.sender=x=>{sent.push(x);return true};window.me={id:'me',name:'Me'};window.other={id:'other',name:'Other'};window.room={id:'room',status:'ACTIVE',startedBy:me,participants:[me,other],joinedParticipantIds:['me','other'],messages:empty?[]:[{senderId:'other',senderName:'Other',body:'Hello'}]};window.openHuddleTask=o=>window.choice=o;LiveHuddleUI.event({type:'WELCOME',self:me,canHuddle:true,online:[me,other],huddles:[room]},sender)},empty);
 await p.getByRole('button',{name:'Close collaboration',exact:true}).click();
 if(empty){assert.equal(await p.evaluate(()=>!!window.choice),false);assert.deepEqual(await p.evaluate(()=>sent.filter(x=>x.type==='LEAVE')),[{type:'LEAVE',huddleId:'room'}]);}
 else await p.evaluate(()=>{window.leaving=choice.leave();});
 await p.evaluate(()=>LiveHuddleUI.event({type:'HUDDLE_UPDATED',huddle:{...room,joinedParticipantIds:['other']}},sender));
 assert.equal(await p.locator('.floating-huddle').count(),0);
 await p.evaluate(()=>{LiveHuddleUI.start(other);LiveHuddleUI.event({type:'HUDDLE_UPDATED',huddle:room},sender)});
 await p.getByRole('button',{name:'Close collaboration',exact:true}).click();if(!empty)await p.evaluate(()=>{window.leaving=choice.leave();});
 await p.evaluate(()=>LiveHuddleUI.event({type:'HUDDLE_UPDATED',huddle:{...room,joinedParticipantIds:['other']}},sender));
 await p.evaluate(()=>LiveHuddleUI.showPeople());await p.getByRole('button',{name:'Other · Online'}).click();
 assert.equal(await p.getByRole('button',{name:'Start Collaboration',exact:true}).isEnabled(),true);
 }finally{await b.close()}
});
