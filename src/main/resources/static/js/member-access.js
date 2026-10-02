(()=>{
  'use strict';
  window.filterUserAccess=value=>{const query=value.trim().toLowerCase();document.querySelectorAll('.access-row').forEach(row=>row.hidden=!row.dataset.search.includes(query));};
  const dialog=document.getElementById('member-access-dialog'),form=document.getElementById('member-access-form'),revoke=document.getElementById('revoke-member-dialog');
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
