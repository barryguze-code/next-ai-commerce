const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
function fixture(){
 const events={},requests=[];let callback,focused=true,ok=true;
 const icon={src:'collaboration-blue',alt:'Active conversation'};
 const visible={dataset:{},getBoundingClientRect:()=>({top:10,bottom:80,height:70,width:200})};
 const offscreen={dataset:{},getBoundingClientRect:()=>({top:600,bottom:680,height:80,width:200})};
 const list={querySelectorAll:()=>[visible,offscreen],getBoundingClientRect:()=>({top:0,bottom:300})};
 const dialog={open:true,dataset:{visibility:'TEAM_CHAT'},querySelector:s=>s==='#thread-status-icon'?icon:list,querySelectorAll:()=>[{name:'_csrf',value:'test-token'}]};
 const document={visibilityState:'visible',hasFocus:()=>focused,querySelector:()=>dialog,addEventListener:(name,fn)=>events[name]=fn,dispatchEvent:()=>{}};
 vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/collaboration-read-receipts.js','utf8'),{
  document,window:{addEventListener(){},NextAiIcons:{source:s=>s}},innerHeight:720,Event,Set,
  setTimeout:fn=>callback=fn,clearTimeout(){},IntersectionObserver:class{observe(){}disconnect(){}},
  FormData:class{constructor(){this.fields=[];}set(k,v){this.fields.push([k,v]);}append(k,v){this.fields.push([k,v]);}},
  fetch:async(url,options)=>{requests.push({url,fields:options.body.fields});return {ok};}
 });
 return {events,document,dialog,requests,icon,setOk:v=>ok=v,setFocus:v=>focused=v,tick:()=>callback?.(),start:type=>events['collaboration-thread-rendered']({detail:{reviewId:'thread-one',messageType:type||'TEAM_CHAT',messageIds:['visible-one','offscreen-two'],unreadMentionMessageIds:['visible-one']}})};
}
test('only visible messages are acknowledged with CSRF and exact IDs, once',async()=>{
 const f=fixture();f.start();await f.tick();await f.tick();assert.equal(f.requests.length,1);
 assert.deepEqual(f.requests[0].fields,[['_csrf','test-token'],['messageId','visible-one']]);
});
test('background, private-note and closed dialogs do not clear unread messages',async()=>{
 const f=fixture();f.start();f.setFocus(false);await f.tick();assert.equal(f.requests.length,0);
 f.setFocus(true);f.document.visibilityState='hidden';await f.tick();assert.equal(f.requests.length,0);
 f.document.visibilityState='visible';f.dialog.open=false;await f.tick();assert.equal(f.requests.length,0);
 f.dialog.open=true;f.start('PRIVATE_NOTE');await f.tick();assert.equal(f.requests.length,0);
});
test('switching threads invalidates pending read acknowledgement',async()=>{
 const f=fixture();f.start();f.events['collaboration-thread-loading']();await f.tick();assert.equal(f.requests.length,0);
});
test('acknowledging mentions clears red only when not assigned',async()=>{
 const f=fixture();f.start();assert.equal(f.icon.src,'collaboration-assigned-red');await f.tick();assert.equal(f.icon.src,'collaboration-blue');
 const assigned=fixture();assigned.icon.src='collaboration-assigned-red';assigned.icon.alt='Assigned to you';assigned.start();await assigned.tick();assert.equal(assigned.icon.src,'collaboration-assigned-red');assert.equal(assigned.icon.alt,'Assigned to you');
});
test('failed saves leave mentions unread and allow retry',async()=>{
 const f=fixture();f.setOk(false);f.start();await f.tick();assert.equal(f.icon.src,'collaboration-assigned-red');f.setOk(true);f.events.scroll();await f.tick();assert.equal(f.requests.length,2);assert.equal(f.icon.src,'collaboration-blue');
});
