(()=>{'use strict';
 const root=document.querySelector('[data-variation-trial]');if(!root)return;
 const url='/app/marketplace-skus/variation-trial',start=root.querySelector('[data-trial-start]'),cancel=root.querySelector('[data-trial-cancel]');
 const startButton=start.querySelector('button'),cancelButton=cancel.querySelector('button'),refresh=root.querySelector('[data-trial-refresh]');
 const title=root.querySelector('[data-trial-state]'),message=root.querySelector('[data-trial-message]'),error=root.querySelector('[data-trial-error]');
 let state=null,busy=false,startAttempted=false,cancelRequested=false,timer;
 const labels={NOT_STARTED:'Ready to start',RUNNING:'Trial running',COMPLETED:'Trial completed',CANCELLED:'Trial cancelled',DEADLINE:'Trial deadline reached',AUTHORIZATION_REQUIRED:'Stopped: Amazon authorization required',UNRESOLVED_EVIDENCE:'Finished with unresolved relationships',FAMILY_LIMIT:'Stopped: family exceeds trial limits',GET_LIMIT:'Stopped: read reservation limit',OAUTH_LIMIT:'Stopped: token-request reservation limit',RETRY_EXHAUSTED:'Stopped: retries exhausted',RETRY_OUTSIDE_WINDOW:'Stopped: retry exceeds trial window',REQUEST_OUTCOME_UNKNOWN:'Stopped: request outcome unknown',JOB_CLAIM_LIMIT:'Stopped: worker claim limit'};
 function controls(){startButton.disabled=busy||startAttempted||state!=='NOT_STARTED';cancelButton.disabled=busy||cancelRequested||state!=='RUNNING';refresh.disabled=busy;}
 function failure(text){error.textContent=text;error.hidden=false;clearTimeout(timer);state=null;title.textContent='Trial status unavailable';message.textContent='An existing trial may still be running within its fixed limits. Refresh status before continuing.';controls();error.focus();}
 async function request(path,form){
  const response=await fetch(path,{method:form?'POST':'GET',credentials:'same-origin',cache:'no-store',headers:form?{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8','Accept':'application/json'}:{'Accept':'application/json'},...(form?{body:new URLSearchParams(new FormData(form))}:{})});
  if(response.redirected||response.status===401)throw new Error('Sign in again, then return to this trial page. No automatic retry was made.');
  if(response.status===403)throw new Error('Access or session verification failed. Select the approved KARACA account and store, then reload this page.');
  if(response.status===409)throw new Error('A trial already exists or collection is unavailable. Refresh status; do not create another trial.');
  if(!response.ok||!response.headers.get('content-type')?.includes('application/json'))throw new Error('The trial response could not be verified. Refresh status before taking another action.');
  try{return await response.json();}catch{throw new Error('The trial response could not be verified. Refresh status before taking another action.');}
 }
 function render(data,focus=false){
  if(!data||typeof data.state!=='string'||!Object.hasOwn(labels,data.state))throw new Error('The trial status could not be verified. Refresh status before taking another action.');
  state=data.state;title.textContent=cancelRequested&&state==='RUNNING'?'Cancellation requested':labels[state];
  message.textContent=state==='NOT_STARTED'?'No trial has started. Start is a deliberate, one-time action.':state==='RUNNING'?(cancelRequested?'Waiting for server confirmation. An in-flight request may still finish.':'Collecting within the fixed trial limits. Inventory enforcement remains off.'):state==='COMPLETED'?'Collection finished. Review relationship evidence before any later activation. Inventory enforcement remains off.':state==='CANCELLED'?'Further scheduling has stopped. An in-flight request may still finish.':'No new trial will be created automatically. Review this outcome before any later action.';
  for(const [field,selector,cap] of [['get_reserved','[data-trial-reads]',10],['oauth_reserved','[data-trial-oauth]',2]])root.querySelector(selector).textContent=Number.isInteger(data[field])&&data[field]>=0&&data[field]<=cap?`${data[field]} of ${cap}`:'—';
  const deadline=root.querySelector('[data-trial-deadline]'),date=['string','number'].includes(typeof data.deadline)?new Date(data.deadline):null;
  if(date&&!Number.isNaN(date.valueOf())){deadline.dateTime=date.toISOString();deadline.textContent=new Intl.DateTimeFormat(undefined,{dateStyle:'medium',timeStyle:'long'}).format(date);}else{deadline.removeAttribute('datetime');deadline.textContent='—';}
  error.hidden=true;controls();clearTimeout(timer);
  if(state==='RUNNING')timer=setTimeout(()=>check(false),2000);
  if(focus)title.focus();
 }
 async function check(focus=false){if(busy)return;busy=true;controls();try{render(await request(url),focus);}catch(e){failure(e.message||'Status is unavailable. Refresh before continuing.');}finally{busy=false;controls();}}
 start.addEventListener('submit',async event=>{event.preventDefault();if(busy||startAttempted||state!=='NOT_STARTED')return;
  busy=true;startAttempted=true;clearTimeout(timer);controls();title.textContent='Starting trial…';message.textContent='Waiting for the server. Do not submit another trial.';
  try{const result=await request(url+'/start',start);if(typeof result?.jobId!=='string')throw new Error('Start outcome is unknown. Refresh status; no automatic retry was made.');render(await request(url),true);}catch(e){failure(e.message||'Start outcome is unknown. Refresh status before continuing.');}finally{busy=false;controls();}
 });
 cancel.addEventListener('submit',async event=>{event.preventDefault();if(busy||cancelRequested||state!=='RUNNING')return;
  busy=true;cancelRequested=true;clearTimeout(timer);controls();title.textContent='Cancellation requested';message.textContent='Waiting for server confirmation. An in-flight request may still finish.';
  try{render(await request(url+'/cancel',cancel),true);}catch(e){cancelRequested=false;failure(e.message||'Cancellation outcome is unknown. Refresh status before continuing.');}finally{busy=false;controls();}
 });
 refresh.addEventListener('click',()=>check(false));check(false);
})();
