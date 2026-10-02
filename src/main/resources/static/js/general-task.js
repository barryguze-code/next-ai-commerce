(()=>{
  function boot(){
    const dialog=document.getElementById('general-task-dialog');if(!dialog)return;
    // Keep the modal outside the narrow sidebar's layout and overflow rules.
    document.body.append(dialog);const form=dialog.querySelector('form'),error=dialog.querySelector('[data-task-error]'),list=dialog.querySelector('[data-task-members]'),submit=form.querySelector('[type=submit]');
    let loaded=false,busy=false;
    async function members(){
      if(loaded)return;list.textContent='Loading teammates…';
      try{const response=await fetch('/app/collaboration/members',{headers:{Accept:'application/json'}});if(!response.ok)throw new Error();const people=await response.json();list.replaceChildren();
        for(const person of people){const label=document.createElement('label'),input=document.createElement('input'),name=document.createElement('span');input.type='checkbox';input.name='people';input.value=person.id;name.textContent=person.name||person.email;label.append(input,name);list.append(label);}
        loaded=true;
      }catch(_){list.textContent='Teammates could not be loaded. You can create an unassigned task and assign it later.';}
    }
    document.querySelectorAll('[data-new-task]').forEach(shortcut=>shortcut.onclick=()=>{dialog.showModal();form.elements.title.focus();members();});
    dialog.querySelectorAll('[data-task-cancel]').forEach(button=>button.onclick=()=>{if(!busy)dialog.close();});
    dialog.addEventListener('cancel',event=>{if(busy)event.preventDefault();});
    dialog.addEventListener('click',event=>{const picker=dialog.querySelector('details');if(!picker.contains(event.target))picker.open=false;});
    list.addEventListener('change',()=>{const names=[...list.querySelectorAll('input:checked')].map(input=>input.nextElementSibling.textContent),summary=dialog.querySelector('[data-task-assignment-label]');summary.textContent=names.length?names.join(', '):'Unassigned';summary.classList.toggle('assigned',names.length>0);});
    form.elements.timeZone.value=Intl.DateTimeFormat().resolvedOptions().timeZone||'America/Los_Angeles';
    form.addEventListener('submit',async event=>{
      event.preventDefault();if(busy||!form.reportValidity())return;busy=true;submit.disabled=true;submit.textContent='Creating…';error.hidden=true;
      try{const response=await fetch(form.action,{method:'POST',body:new FormData(form),headers:{Accept:'application/json'}});const result=await response.json().catch(()=>({}));if(!response.ok)throw new Error(response.status<500&&result.error?result.error:(response.status===403?'Your session expired or your role cannot create tasks. Reload and try again.':'The task could not be saved. Your draft is still here.'));
        if(typeof result.url!=='string'||!result.url.startsWith('/app/collaboration?threadId='))throw new Error('Task saved. Open Collaboration to find it.');location.assign(result.url);
      }catch(failure){error.textContent=failure.message;error.hidden=false;busy=false;submit.disabled=false;submit.textContent='Create task';}
    });
  }
  document.readyState==='loading'?document.addEventListener('DOMContentLoaded',boot):boot();
})();
