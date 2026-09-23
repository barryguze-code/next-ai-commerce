(()=>{
 const form=document.querySelector('#shelf-policy-dialog form');if(!form)return;
 let approved=false,loading=false;
 form.addEventListener('submit',async event=>{
  if(approved)return;event.preventDefault();if(loading)return;loading=true;
  const save=form.querySelector('[type=submit]');save.disabled=true;
  let error=form.querySelector('[data-impact-error]');if(error)error.remove();
  try{
   const response=await fetch('/app/inventory/policy/impact',{headers:{Accept:'application/json'},cache:'no-store'});
   if(!response.ok)throw Error();const data=await response.json();
   if(!Number.isInteger(data.skuCount)||data.skuCount<0)throw Error();
   const accepted=await window.NextAiConfirm({title:'Apply shelf-life rules to this account?',
    message:data.skuCount+' mapped SKUs will be re-evaluated. Matching stock quantities and managed sale prices will update under the new rules; not every SKU necessarily changes. Product and SKU sale exceptions remain in place. Sale end dates cannot exceed the stop-selling cutoff. Only approved production publishing connections send changes to Amazon.',accept:'Save rules'});
   if(accepted){approved=true;save.disabled=false;form.requestSubmit();}
  }catch(_){error=document.createElement('p');error.dataset.impactError='true';error.className='notice error';error.setAttribute('role','alert');error.textContent='Could not check affected SKUs. Nothing was saved. Please try again.';form.querySelector('.dialog-body').append(error);}
  finally{loading=false;save.disabled=false;}
 });
})();
