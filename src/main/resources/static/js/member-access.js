(()=>{
  'use strict';
  window.filterUserAccess=value=>{const query=value.trim().toLowerCase();document.querySelectorAll('.access-row').forEach(row=>row.hidden=!row.dataset.search.includes(query));};
  const dialog=document.getElementById('member-access-dialog'),form=document.getElementById('member-access-form'),revoke=document.getElementById('revoke-member-dialog');
  const filter=document.getElementById('users-account-filter');
  if(filter){
    const key='nextai.users.account:'+filter.dataset.user+':'+filter.dataset.workspace;
    const url=new URL(location.href);
    try{
      const last=localStorage.getItem(key);
      if(!url.searchParams.has('account') && last && last!==filter.value && [...filter.options].some(o=>o.value===last)){
        url.searchParams.set('account',last);location.replace(url);return;
      }
      localStorage.setItem(key,filter.value);
    }catch{}
    filter.onchange=()=>{url.searchParams.set('account',filter.value);url.searchParams.delete('invite');location.assign(url);};
    if(filter.dataset.invite==='true')document.getElementById('invite-dialog').showModal();
  }
  const invite=document.querySelector('#invite-dialog form');
  if(invite){
    const email=invite.elements.email,submit=invite.querySelector('[type=submit]'),boxes=[...invite.querySelectorAll('[name=storeIds]')];
    const hint=document.createElement('small');hint.setAttribute('role','status');email.parentElement.append(hint);let generation=0,timer,ready=false;
    const update=()=>{submit.disabled=!ready||(boxes.length>0&&!boxes.some(b=>!b.disabled&&b.checked));};
    async function lookup(){
      const current=++generation;ready=false;submit.disabled=true;boxes.forEach(b=>{b.disabled=false;b.checked=true;b.closest('label')?.querySelector('[data-existing-access]')?.remove();});
      if(!email.value.trim()||!email.checkValidity()){hint.textContent='Enter an email address to check existing access.';return;}
      hint.textContent='Checking existing access…';
      try{const response=await fetch('/app/users/invitations/existing-access?'+new URLSearchParams({account:invite.elements.account.value,email:email.value.trim()}),{headers:{Accept:'application/json'},cache:'no-store'});if(!response.ok)throw Error();const data=await response.json();if(current!==generation)return;if(!Array.isArray(data.storeIds)||data.storeIds.some(id=>typeof id!=='string'))throw Error();const granted=new Set(data.storeIds);boxes.forEach(b=>{if(!granted.has(b.value))return;b.disabled=true;const label=document.createElement('small');label.dataset.existingAccess='';label.textContent='Already has access';b.closest('label').append(label);});ready=true;hint.textContent=boxes.length&&boxes.every(b=>b.disabled)?'Already has access to all stores.':granted.size?'Select additional stores to invite this person.':'';update();}
      catch(_){if(current===generation){hint.textContent='Existing access could not be checked. Check the email or try again.';ready=false;update();}}
    }
    email.addEventListener('input',()=>{generation++;ready=false;submit.disabled=true;clearTimeout(timer);timer=setTimeout(lookup,250);});email.addEventListener('change',()=>{clearTimeout(timer);lookup();});boxes.forEach(b=>b.addEventListener('change',update));
    invite.addEventListener('submit',e=>{if(!ready||submit.disabled){e.preventDefault();lookup();}});lookup();
  }
  if(!dialog||!form||!revoke)return;
  let trigger;
  document.querySelectorAll('[data-revoke-store]').forEach(storeForm=>storeForm.addEventListener('submit',event=>{
    event.preventDefault();
    const confirmation=document.createElement('dialog');confirmation.className='invite-dialog';
    confirmation.innerHTML='<div class="dialog-shell"><div class="dialog-header"><div><span class="eyebrow dark">Users &amp; Access</span><h2>Revoke store access?</h2></div></div><div class="dialog-body"><p></p></div><div class="dialog-actions"><button type="button" class="secondary-button">Cancel</button><button type="button" class="primary-button member-danger-button">Revoke</button></div></div>';
    confirmation.querySelector('p').textContent='Remove '+storeForm.dataset.memberName+' from '+storeForm.dataset.storeName+'? Other stores and accounts stay unchanged. If this is their last store, access to this account is removed.';
    confirmation.querySelector('.secondary-button').onclick=()=>confirmation.close();
    confirmation.querySelector('.primary-button').onclick=()=>storeForm.submit();
    confirmation.onclose=()=>confirmation.remove();document.body.append(confirmation);confirmation.showModal();
  }));
  document.addEventListener('click',event=>{
    const button=event.target.closest('[data-manage-member]');if(!button)return;
    trigger=button;form.action='/app/users/'+encodeURIComponent(button.dataset.userId)+'/access';
    form.querySelector('[data-member-name]').textContent=button.dataset.name;
    form.elements.role.value=button.dataset.role;
    const stores=new Set((button.dataset.storeIds||'').split(','));
    form.querySelectorAll('[name=storeIds]').forEach(input=>{input.checked=stores.has(input.value);input.setCustomValidity('');});
    dialog.showModal();
  });
  dialog.querySelectorAll('[data-close-access]').forEach(button=>button.onclick=()=>dialog.close());
  dialog.addEventListener('close',()=>{if(!revoke.open)trigger?.focus();});
  form.addEventListener('submit',event=>{
    if(!form.querySelector('[name=storeIds]:checked')){
      event.preventDefault();const input=form.querySelector('[name=storeIds]');
      if(input){input.setCustomValidity('Select a store, or use Revoke account access to remove all access.');input.reportValidity();}
    }
  });
  form.addEventListener('change',()=>form.querySelectorAll('[name=storeIds]').forEach(input=>input.setCustomValidity('')));
  form.querySelector('[data-revoke-member]').onclick=()=>{
    revoke.querySelector('form').action=form.action;
    revoke.querySelector('[data-revoke-name]').textContent=form.querySelector('[data-member-name]').textContent;
    dialog.close();revoke.showModal();
  };
  revoke.querySelectorAll('[data-cancel-revoke]').forEach(button=>button.onclick=()=>{revoke.close();dialog.showModal();});
})();
