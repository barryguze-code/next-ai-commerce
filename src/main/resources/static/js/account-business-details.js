(()=>{
 const dialog=document.getElementById('business-details'),form=dialog.querySelector('form');
 const edit=form.querySelector('[data-edit]'),save=form.querySelector('[type=submit]'),status=form.querySelector('[role=status]');
 let opener,endpoint,request;
 function editing(value){form.querySelector('[data-logo-field]').hidden=!value;form.elements.displayName.readOnly=form.elements.name.readOnly=form.elements.address.readOnly=!value;edit.hidden=value;save.hidden=!value;}
 document.addEventListener('click',async event=>{
  const trigger=event.target.closest('[data-business-details]');if(!trigger)return;
  opener=trigger.closest('details')?.querySelector('summary')||trigger;endpoint='/app/platform/accounts/'+encodeURIComponent(trigger.dataset.account)+'/business-details';
  const current=request=new AbortController();
  form.reset();editing(false);edit.disabled=true;status.textContent='Loading…';
  form.querySelector('[data-account-name]').textContent=trigger.closest('tr')?.dataset.title||'';
  const menu=trigger.closest('details');if(menu)menu.open=false;dialog.showModal();
  try{const result=await fetch(endpoint,{signal:current.signal,headers:{Accept:'application/json'},cache:'no-store'});
   if(!result.ok)throw Error();const details=await result.json();if(current!==request)return;
   form.elements.displayName.value=details.displayName;form.elements.name.value=details.name||'';form.elements.address.value=details.address||'';edit.disabled=false;editing(true);status.textContent='';
  }catch(e){if(!current.signal.aborted)status.textContent='Could not load business details.';}
 },true);
 edit.onclick=()=>{editing(true);form.elements.name.focus();};
 form.onsubmit=async event=>{event.preventDefault();if(save.hidden||save.disabled)return;save.disabled=true;
  try{const result=await fetch(endpoint,{method:'POST',body:new FormData(form),headers:{Accept:'application/json'}});
   if(!result.ok||result.redirected)throw Error((await result.json().catch(()=>({}))).error||'Could not save business details.');const row=document.querySelector('tr [data-business-details][data-account="'+CSS.escape(endpoint.split('/').at(-2))+'"]')?.closest('tr');
   if(row){row.dataset.title=form.elements.displayName.value;row.dataset.search=row.dataset.title.toLowerCase();const title=row.querySelector('[data-account-title]');if(title)title.textContent=row.dataset.title;
    if(form.elements.logo.files.length){const slot=row.querySelector('.table-overview-picture');if(slot){let img=slot.querySelector('img')||document.createElement('img');img.alt='';img.src=endpoint.replace('/business-details','/logo')+'?v='+Date.now();slot.replaceChildren(img);}}}
   editing(false);status.textContent='Business details saved.';
  }catch(error){status.textContent=error.message||'Could not save business details.';}finally{save.disabled=false;}
 };
 form.querySelectorAll('[data-close]').forEach(b=>b.onclick=()=>dialog.close());
 dialog.onclose=()=>{request?.abort();request=null;opener?.focus();};
 const notice=document.getElementById('account-invite-status');
 document.addEventListener('click',async event=>{
  const button=event.target.closest('[data-account-invite]');if(!button || button.disabled)return;
  const menu=button.closest('details'),restore=menu?.querySelector('summary')||button;
  if(menu)menu.open=false;button.disabled=true;
  try{
   const result=await fetch('/app/users?account='+encodeURIComponent(button.dataset.account),{cache:'no-store'});
   const source=new DOMParser().parseFromString(await result.text(),'text/html').getElementById('invite-dialog');
   if(!result.ok||!source||source.querySelector('[name=account]').value!==button.dataset.account)throw Error();
   const invite=document.importNode(source,true);document.body.append(invite);
   const form=invite.querySelector('form'),send=form.querySelector('[type=submit]');
   const feedback=document.createElement('p');feedback.setAttribute('role','status');form.querySelector('.dialog-body').append(feedback);
   form.onsubmit=async e=>{e.preventDefault();if(send.disabled)return;send.disabled=true;
    try{const reply=await fetch(form.action,{method:'POST',body:new FormData(form)});
     const doc=new DOMParser().parseFromString(await reply.text(),'text/html'),message=doc.querySelector('main > .notice');
     if(!reply.ok||!message)throw Error();
     if(message.classList.contains('error'))feedback.textContent=message.textContent;
     else{notice.textContent=message.textContent;invite.close();}
    }catch{feedback.textContent='Could not confirm delivery. Check Users & Access before retrying.';}finally{send.disabled=false;}
   };
   invite.onclose=()=>{invite.remove();restore.focus();};invite.showModal();notice.textContent='';
  }catch{notice.textContent='Could not open the invitation for this account.';}finally{button.disabled=false;}
 },true);
})();
