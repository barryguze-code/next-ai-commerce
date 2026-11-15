const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const source=fs.readFileSync('src/main/resources/static/js/collaboration.js','utf8');
const body=source.slice(source.indexOf('  async function sendGlobal('),source.indexOf('  function subjectText('));
for(const scenario of ['success','failure','draft'])test('direct completion: '+scenario,async()=>{
 const field={value:''},file={files:[],value:''},feedback={textContent:''},send={disabled:false},error={scrollIntoView(){}};
 const form={dataset:{},action:'/close',querySelector:()=>feedback};let requests=0,closed=0,updated=0;
 const state={currentReviewId:'one',messageType:'TEAM_CHAT'};
 const submitter={name:'status',value:'CLOSED',disabled:false};
 const sendGlobal=vm.runInNewContext(body+';sendGlobal',{
 state,hasUnsentDraft:()=>scenario==='draft',
 $:selector=>selector==='textarea'?field:selector==='input[type=file]'?file:selector==='[data-thread-management-error]'?error:send,
 FormData:class {set(){}},fetch:async()=>{requests++;return {ok:scenario!=='failure',json:async()=>({reviewId:'one',error:'Could not save'})};},
 dialog:()=>({close(){closed++;}}),window:{dispatchEvent(){updated++;}},Event:class{},
 globalScope:()=>false,loadConversation:()=>{throw Error('Completion must not reopen the dialog');}
 });
 await sendGlobal(form,submitter);
 assert.equal(requests,scenario==='draft'?0:1);assert.equal(closed,scenario==='success'?1:0);
 assert.equal(submitter.disabled,false);assert.equal(form.dataset.saving,undefined);
 if(scenario==='failure')assert.equal(feedback.textContent,'Could not save');
 if(scenario==='success')assert.equal(updated,1);
 if(scenario==='draft')assert.match(error.textContent,/unsent/);
});
