(()=>{'use strict';
 document.querySelectorAll('[data-edit-vendor]').forEach(select=>{let sequence=0;select.addEventListener('change',async()=>{
  const form=select.form,code=form.elements.vendorItemCode,cost=form.elements.itemCost,status=form.querySelector('[data-edit-vendor-feedback]'),vendor=select.value,request=++sequence;
  code.disabled=!vendor;if(!vendor){status.textContent='Keeping the current buying vendor.';return;}code.value='';cost.value='';status.textContent='Loading this account’s buying terms…';
  try{const response=await fetch('/app/catalog/product-defaults?globalProductId='+encodeURIComponent(select.dataset.globalProduct)+'&vendorId='+encodeURIComponent(vendor),{headers:{Accept:'application/json'}});if(!response.ok)throw Error();const defaults=await response.json();if(request!==sequence||vendor!==select.value)return;if(!code.value)code.value=defaults.vendorItemCode||'';if(!cost.value&&defaults.unitCost!=null)cost.value=defaults.unitCost;if(!form.elements.accountSku.value)form.elements.accountSku.value=defaults.accountSku||defaults.vendorItemCode||'';status.textContent='Currency: '+defaults.currency+'. Review the code and net cost, then Save.';}
  catch{if(request===sequence)status.textContent='Buying terms could not be loaded. Enter the vendor code and cost manually; nothing was saved.';}
 });});
})();
