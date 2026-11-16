(()=>{'use strict';
 function enhance(root=document){
  if(location.pathname==='/app/catalog')root.querySelectorAll('.catalog-edit-dialog form:not([data-catalog-save-ready])').forEach(form=>{
   form.dataset.catalogSaveReady='true';form.addEventListener('submit',async event=>{
    event.preventDefault();const button=form.querySelector('[type=submit]');if(button.disabled)return;button.disabled=true;
    let feedback=form.querySelector('[data-catalog-save-feedback]');if(!feedback){feedback=document.createElement('p');feedback.dataset.catalogSaveFeedback='';feedback.setAttribute('role','status');form.querySelector('.dialog-body').append(feedback);}
    feedback.textContent='Saving…';
    try{const response=await fetch(form.action,{method:'POST',body:new FormData(form),headers:{Accept:'application/json'}});const result=await response.json();if(!response.ok)throw Error(result.message||'The product could not be saved. Nothing was changed.');
     try{sessionStorage.setItem('nextai.catalog.saved',JSON.stringify({view:location.href,account:document.querySelector('#general-task-context')?.dataset.account}));}catch{}form.closest('dialog').close();if(window.NextAiTableContinuity)window.NextAiTableContinuity.reload();else location.reload();
    }catch(error){feedback.textContent=error.message;feedback.setAttribute('role','alert');}finally{button.disabled=false;}
   });
  });
root.querySelectorAll('[data-edit-vendor]:not([data-vendor-ready])').forEach(select=>{select.dataset.vendorReady='true';let sequence=0;select.addEventListener('change',async()=>{
  const form=select.form,code=form.elements.vendorItemCode,cost=form.elements.itemCost,status=form.querySelector('[data-edit-vendor-feedback]'),vendor=select.value,request=++sequence;
  code.disabled=!vendor;if(!vendor){status.textContent='Keeping the current buying vendor.';return;}code.value='';cost.value='';status.textContent='Loading this account’s buying terms…';
  try{const response=await fetch('/app/catalog/product-defaults?globalProductId='+encodeURIComponent(select.dataset.globalProduct)+'&vendorId='+encodeURIComponent(vendor),{headers:{Accept:'application/json'}});if(!response.ok)throw Error();const defaults=await response.json();if(request!==sequence||vendor!==select.value)return;if(!code.value)code.value=defaults.vendorItemCode||'';if(!cost.value&&defaults.unitCost!=null)cost.value=defaults.unitCost;if(!form.elements.accountSku.value)form.elements.accountSku.value=defaults.accountSku||defaults.vendorItemCode||'';status.textContent='Currency: '+defaults.currency+'. Review the code and net cost, then Save.';}
  catch{if(request===sequence)status.textContent='Buying terms could not be loaded. Enter the vendor code and cost manually; nothing was saved.';}
 });});
 }
 try{const saved=JSON.parse(sessionStorage.getItem('nextai.catalog.saved')||'null');sessionStorage.removeItem('nextai.catalog.saved');if(saved&&saved.view===location.href&&saved.account===document.querySelector('#general-task-context')?.dataset.account){const notice=document.createElement('p');notice.className='notice';notice.setAttribute('role','status');notice.textContent='Account product details updated.';document.querySelector('main')?.prepend(notice);}}catch{}
 window.NextAiCatalogEditor={enhance};enhance();
})();
