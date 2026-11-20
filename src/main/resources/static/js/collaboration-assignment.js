(()=>{
  'use strict';
  const choices=document.getElementById('collaboration-assignment-choices');if(!choices)return;
  const form=choices.querySelector('form'),search=choices.querySelector('input[type=search]'),error=choices.querySelector('[data-assignment-error]');
  let trigger,busy=false;
  const close=()=>{choices.hidePopover();trigger?.setAttribute('aria-expanded','false');};
  document.addEventListener('click',async event=>{
    const target=event.target.closest('[data-assign-review]');if(!target)return;event.stopPropagation();
    if(trigger===target&&choices.matches(':popover-open')){close();return;}
    if(busy)return;
    trigger?.setAttribute('aria-expanded','false');trigger=target;
    form.action=trigger.dataset.assignmentUrl||'/app/collaboration/reviews/'+encodeURIComponent(trigger.dataset.reviewId)+'/assignees';
    if(trigger.dataset.accountId){
      try {
        const response=await fetch('/app/collaboration/personal/members?'+new URLSearchParams({account:trigger.dataset.accountId}));
        if(!response.ok)throw Error();
        const people=await response.json(),list=choices.querySelector('.assignment-people');list.replaceChildren();
        for(const person of people){
          const label=document.createElement('label'),input=document.createElement('input'),name=document.createElement('span');
          input.type='checkbox';input.name='people';input.value=person.id;input.dataset.email=person.email;name.textContent=person.name;label.append(input,name);list.append(label);
        }
      }catch{error.textContent='Teammates could not be loaded. Refresh and try again.';error.hidden=false;return;}
    }
    const emails=new Set((trigger.dataset.assignedEmails||'').toLowerCase().split(',').map(x=>x.trim()));
    form.querySelectorAll('[name=people]').forEach(input=>{input.checked=emails.has(input.dataset.email.toLowerCase());input.closest('label').hidden=false;});
    search.value='';error.hidden=true;choices.showPopover();trigger.setAttribute('aria-expanded','true');
    const box=trigger.getBoundingClientRect();choices.style.left=Math.max(12,Math.min(box.left,innerWidth-choices.offsetWidth-12))+'px';choices.style.top=Math.max(12,Math.min(box.bottom+5,innerHeight-choices.offsetHeight-12))+'px';search.focus();
  });
  choices.addEventListener('toggle',()=>{if(!choices.matches(':popover-open'))trigger?.setAttribute('aria-expanded','false');});
  choices.querySelector('[data-close-assignment]').onclick=close;
  choices.querySelector('[data-unassign-all]').onclick=()=>form.querySelectorAll('[name=people]').forEach(input=>input.checked=false);
  search.oninput=()=>choices.querySelectorAll('.assignment-people label').forEach(label=>label.hidden=!label.textContent.toLowerCase().includes(search.value.toLowerCase()));
  form.onsubmit=async event=>{
    event.preventDefault();if(busy)return;busy=true;const submit=form.querySelector('[type=submit]');submit.disabled=true;error.hidden=true;
    try{
      const response=await fetch(form.action,{method:'POST',body:new FormData(form),headers:{Accept:'application/json'}});
      if(!response.ok)throw new Error(response.status===403?'Your permission changed. Refresh this page.':'Assignment could not be saved. Please try again.');
      const review=await response.json(),row=trigger.closest('tr');
      trigger.dataset.assignedEmails=review.assigneeEmail||'';
      if(trigger.dataset.assignmentUrl){close();trigger.focus();window.dispatchEvent(new Event('personal-collaboration-updated'));return;}
      trigger.querySelector('span').textContent=review.assigneeName||'Unassigned';
      const icon=row.querySelector('[data-conversation-count]');icon.dataset.conversationCount=String(review.messageCount);icon.dataset.assignedToMe=String((review.assigneeEmail||'').toLowerCase().split(',').map(x=>x.trim()).includes(choices.dataset.currentEmail.toLowerCase())&&review.status==='ACTIVE');
      window.prepareRecordCollaboration?.(icon);
      row.querySelector('[data-message-total]')?.replaceChildren(document.createTextNode(review.messageCount+' messages'));
      close();trigger.focus();if(trigger.dataset.assignmentUrl)window.dispatchEvent(new Event('personal-collaboration-updated'));
    }catch(ex){error.textContent=ex.message;error.hidden=false;}
    finally{busy=false;submit.disabled=false;}
  };
})();
