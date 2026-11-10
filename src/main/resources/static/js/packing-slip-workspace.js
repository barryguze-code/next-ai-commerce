(()=>{
 const root=document.querySelector('.packing-workspace');if(!root)return;
 const select=document.querySelector('#packing-package'),customWrap=document.querySelector('#custom-package-field'),custom=document.querySelector('#custom-package'),status=document.querySelector('#packing-status'),csrf=document.querySelector('#packing-slip-csrf');
 const printed=[...document.querySelectorAll('[data-printed-package]')],normal=name=>String(name).replace(/\s+/g,' ').trim(),key=name=>normal(name).toLowerCase();
 const seen=new Set();for(const option of [...select.options]){if(option.value==='__custom')continue;const value=normal(option.value);if(!value||seen.has(key(value))){option.remove();continue;}seen.add(key(value));option.value=value;option.textContent=value;}
 function remember(name){const value=normal(name);let option=[...select.options].find(o=>o.value!=='__custom'&&key(o.value)===key(value));if(!option){option=document.createElement('option');option.value=option.textContent=value;select.insertBefore(option,select.querySelector('[value="__custom"]'));}return option.value;}
 const current=normal(printed[0]?.textContent||'');const known=[...select.options].find(o=>o.value!=='__custom'&&key(o.value)===key(current));
 if(known)select.value=known.value;else{select.value='__custom';customWrap.hidden=false;custom.value=current;}
 const packageName=()=>select.value==='__custom'?normal(custom.value):select.value;
 const updatePrinted=name=>printed.forEach(node=>node.textContent=name);
 const locationRows=[...document.querySelectorAll('.packing-slip article')];
 const savedLocations=new Map(locationRows.map(row=>[row,row.querySelector('.packing-line-location')?.textContent||'—']));
 function previewLocations(){
  document.querySelectorAll('.packing-product-form').forEach(form=>{
   const choice=form.elements.locationId,code=choice.selectedOptions?.[0]?.dataset.locationCode;
   locationRows.forEach(row=>{
    if(row.dataset.accountItemId!==form.dataset.accountItemId||row.dataset.reservedLocation==='true')return;
    row.querySelector('.packing-line-location').textContent=code?code:savedLocations.get(row);
   });
  });
 }
 document.querySelectorAll('.packing-product-form select[name=locationId]').forEach(choice=>choice.addEventListener('change',previewLocations));
 const showPackage=()=>{const edit=document.querySelector('#rename-package');if(edit)edit.disabled=select.value==='__custom';customWrap.hidden=select.value!=='__custom';if(packageName())updatePrinted(packageName());};
 select.addEventListener('change',showPackage);custom.addEventListener('input',showPackage);showPackage();
 function preparePrint(){
  const value=packageName();
  if(!value){status.textContent='Write a package name first.';custom.focus();return false;}
  updatePrinted(value);previewLocations();return true;
 }
 document.querySelector('#print-slip').addEventListener('click',()=>{if(preparePrint())window.print();});
 window.addEventListener('beforeprint',preparePrint);
 window.NextAiPackageNameEditor({select,packageName,showPackage,status,csrf});
 document.querySelector('#save-package').addEventListener('click',async event=>{
  const value=packageName(),button=event.currentTarget;if(!value){status.textContent='Write a package name first.';custom.focus();return;}
  button.disabled=true;status.textContent='Saving package choice…';
  try{const form=new URLSearchParams({packageName:value}),headers={'Content-Type':'application/x-www-form-urlencoded',Accept:'application/json'};if(csrf)headers[csrf.dataset.header||'X-CSRF-TOKEN']=csrf.value;
   const response=await fetch('/app/orders/'+encodeURIComponent(root.dataset.orderId)+'/packing-slip/package',{method:'POST',headers,body:form});if(!response.headers.get('content-type')?.includes('application/json'))throw Error('Please sign in again. Your package choice is unchanged.');const data=await response.json();if(!response.ok)throw Error(data.message||data.detail||'Could not save package choice.');
   select.value=remember(data.packageName);showPackage();status.textContent='Package saved. It is now available for other orders in this account.';
  }catch(error){status.textContent=error.message;}finally{button.disabled=false;}
 });
 document.querySelectorAll('.packing-product-form').forEach(form=>form.addEventListener('submit',async event=>{
  event.preventDefault();const button=form.querySelector('[type=submit]'),feedback=form.querySelector('.packing-product-status');if(button.disabled)return;
  const data=new FormData(form),headers={Accept:'application/json'};if(csrf)headers[csrf.dataset.header||'X-CSRF-TOKEN']=csrf.value;button.disabled=true;feedback.textContent='Saving account details…';
  try{const response=await fetch(form.action,{method:'POST',headers,body:data});if(!response.headers.get('content-type')?.includes('application/json'))throw Error('Please sign in again. Your edits are unchanged.');const result=await response.json();if(!response.ok)throw Error(result.error||'The account details could not be saved.');
   for(const row of document.querySelectorAll('.packing-slip article'))if(row.dataset.accountItemId===result.itemId){row.querySelector('.packing-line-description').textContent=result.description;if(row.dataset.reservedLocation!=='true'){const location=result.locationCode||'—';savedLocations.set(row,location);row.querySelector('.packing-line-location').textContent=location;}}
   const changed=form.elements.description.value!==data.get('description')||form.elements.locationId.value!==data.get('locationId');feedback.textContent=changed?'Saved. Your newer edits still need Save.':'Account details saved. Existing stock and reserved locations are unchanged.';
  }catch(error){feedback.textContent=error.name==='TypeError'?'Could not reach the server. Your edits are unchanged; try again.':error.message;}finally{button.disabled=false;}
 }));
})();
try{document.documentElement.dataset.theme=localStorage.getItem('nextai-theme')||'light';}catch(_){}
if(new URLSearchParams(location.search).get('sellerCentralBlocked')==='true'){
  const help=document.querySelector('#packing-popup-help');
  if(help){
    help.hidden=false;
    help.querySelectorAll('[data-popup-origin]').forEach(node=>{node.textContent=location.origin;});
  }
}
document.querySelector('#packing-complete')?.addEventListener('click',async event=>{
  if(!confirm('Mark as shipped? Packed stock leaves the shelf. This updates our platform only.'))return;
  const button=event.currentTarget,status=document.querySelector('#packing-status'),csrf=document.querySelector('#packing-slip-csrf');button.disabled=true;
  try{
    const headers={'Content-Type':'application/x-www-form-urlencoded'};if(csrf)headers[csrf.dataset.header||'X-CSRF-TOKEN']=csrf.value;
    const response=await fetch('/app/orders/'+encodeURIComponent(document.querySelector('.packing-workspace').dataset.orderId)+'/pickup-override',{method:'POST',headers,body:new URLSearchParams({waiting:'true'})});
    if(!response.ok||new URL(response.url).pathname==='/login')throw new Error('Could not save. Please sign in and try again.');
    button.querySelector('span').textContent='Waiting for pickup';status.textContent='Marked shipped. Orders will refresh when you return.';
    try{localStorage.setItem('nextai-order-updated',String(Date.now()));}catch(_){}
    status.textContent='Marked shipped — waiting for pickup. You can close this tab; Orders will refresh when you return.';
    window.close();
  }catch(error){button.disabled=false;status.textContent=error.message;}
});
// Start clipboard access during the click gesture; keep the link's native new-tab behavior.
document.querySelector('#veeqo-shortcut')?.addEventListener('click',async()=>{
  const orderId=document.querySelector('.packing-workspace')?.dataset.orderId;
  const status=document.querySelector('#packing-status');
  if(!orderId)return;
  try{
    await navigator.clipboard.writeText(orderId);
    if(status)status.textContent='Order ID copied — ready to paste into Veeqo.';
  }catch(_){
    if(status)status.textContent='Clipboard access was blocked. Order ID: '+orderId;
  }
});
