(()=>{
  function boot(){
    const dialog=document.getElementById('general-task-dialog');if(!dialog)return;
    // Keep the modal outside the narrow sidebar's layout and overflow rules.
    document.body.append(dialog);const form=dialog.querySelector('form'),error=dialog.querySelector('[data-task-error]'),list=dialog.querySelector('[data-task-members]'),submit=form.querySelector('[type=submit]');
    let loaded=false,busy=false,memberRequest=0;
    const personal=document.getElementById('personal-queue');
    let accountChoice,storeChoice;
    if(personal){
      const label=document.createElement('label');label.textContent='Account';
      accountChoice=document.createElement('select');accountChoice.name='account';accountChoice.required=true;accountChoice.dataset.standardChoice='';accountChoice.setAttribute('aria-label','Account');
      accountChoice.append(new Option('Choose an account',''));
      personal.querySelectorAll('[data-collaboration-account]:not([data-collaboration-store])').forEach(button=>{
        if(button.dataset.collaborationAccount)accountChoice.append(new Option(button.dataset.name,button.dataset.collaborationAccount));
      });
      label.append(accountChoice);form.querySelector('.general-task-body').prepend(label);
      storeChoice=document.createElement('input');storeChoice.type='hidden';storeChoice.name='store';form.append(storeChoice);
      accountChoice.onchange=()=>{loaded=false;storeChoice.value='';list.replaceChildren();updateTaskIdentity(false);members();};
      form.action='/app/collaboration/personal/create';
      dialog.querySelector('header small').textContent='All accounts';
      dialog.querySelector('header p').textContent='Choose the account for this task.';
    }
    function updateTaskIdentity(fromPage){
      if(!personal)return;
      const option=[...personal.querySelectorAll('[data-collaboration-account]:not([data-collaboration-store])')]
        .find(button=>button.dataset.collaborationAccount===accountChoice.value);
      const name=fromPage?personal.querySelector('#collaboration-scope-label').textContent:(option?.dataset.name||'All accounts');
      const image=fromPage?personal.querySelector('.context-switcher img')?.src:option?.dataset.logo;
      dialog.querySelector('header small').textContent=name;
      dialog.querySelector('.task-store-logo').src=image||'/images/next-ai-mark-transparent.png';
      dialog.querySelector('header p').textContent=accountChoice.value?'A general task for this account. No item or order link needed.':'Choose an account before saving this task.';
      dialog.querySelector('footer small').textContent=accountChoice.value?'Appears in Collaborate for this account.':'All accounts is the viewing context.';
    }
    async function members(){
      const request=++memberRequest,accountId=accountChoice?.value;
      if(loaded)return;if(personal&&!accountChoice.value){list.textContent='Choose an account to select teammates.';return;}list.textContent='Loading teammates…';
      try{const response=await fetch(personal?'/app/collaboration/personal/members?'+new URLSearchParams({account:accountChoice.value}):'/app/collaboration/members',{headers:{Accept:'application/json'}});if(!response.ok)throw new Error();const people=await response.json();if(request!==memberRequest||(personal&&accountId!==accountChoice.value))return;list.replaceChildren();
        for(const person of people){const label=document.createElement('label'),input=document.createElement('input'),name=document.createElement('span');input.type='checkbox';input.name='people';input.value=person.id;name.textContent=person.name||person.email;label.append(input,name);list.append(label);}
        loaded=true;
      }catch(_){if(request!==memberRequest)return;list.textContent='Teammates could not be loaded. You can create an unassigned task and assign it later.';}
    }
    document.querySelectorAll('[data-new-task]').forEach(shortcut=>shortcut.onclick=()=>{if(personal){accountChoice.value=personal.dataset.filterAccount||'';storeChoice.value=personal.dataset.filterStore||'';loaded=false;list.replaceChildren();updateTaskIdentity(true);window.NextAiPlatformControls?.enhance(dialog);accountChoice.dispatchEvent(new Event('platform-choice-sync'));}dialog.showModal();form.elements.title.focus();members();});
    dialog.querySelectorAll('[data-task-cancel]').forEach(button=>button.onclick=()=>{if(!busy)dialog.close();});
    dialog.addEventListener('cancel',event=>{if(busy)event.preventDefault();});
    dialog.addEventListener('click',event=>{const picker=dialog.querySelector('details');if(!picker.contains(event.target))picker.open=false;});
    list.addEventListener('change',()=>{const names=[...list.querySelectorAll('input:checked')].map(input=>input.nextElementSibling.textContent),summary=dialog.querySelector('[data-task-assignment-label]');summary.textContent=names.length?names.join(', '):'Unassigned';summary.classList.toggle('assigned',names.length>0);});
    form.elements.timeZone.value=Intl.DateTimeFormat().resolvedOptions().timeZone||'America/Los_Angeles';
    form.addEventListener('submit',async event=>{
      event.preventDefault();if(busy||!form.reportValidity())return;busy=true;submit.disabled=true;submit.textContent='Creating…';error.hidden=true;
      try{const response=await fetch(form.action,{method:'POST',body:new FormData(form),headers:{Accept:'application/json'}});const result=await response.json().catch(()=>({}));if(!response.ok)throw new Error(response.status<500&&result.error?result.error:(response.status===403?'Your session expired or your role cannot create tasks. Reload and try again.':'The task could not be saved. Your draft is still here.'));
        if(typeof result.url!=='string'||!(result.url.startsWith('/app/collaboration?threadId=')||(personal&&result.url==='/app/collaboration')))throw new Error('Task saved. Open Collaboration to find it.');location.assign(result.url);
      }catch(failure){error.textContent=failure.message;error.hidden=false;busy=false;submit.disabled=false;submit.textContent='Create task';}
    });
  }
  document.readyState==='loading'?document.addEventListener('DOMContentLoaded',boot):boot();
})();
