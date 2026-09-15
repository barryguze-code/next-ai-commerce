(()=>{const root=document.querySelector('.packing-workspace');if(!root)return;const select=document.querySelector('#packing-package'),customWrap=document.querySelector('#custom-package-field'),custom=document.querySelector('#custom-package'),printed=document.querySelector('#printed-package'),status=document.querySelector('#packing-status'),csrf=document.querySelector('#packing-slip-csrf');const current=printed.textContent.trim();if([...select.options].some(option=>option.value===current))select.value=current;else{select.value='__custom';customWrap.hidden=false;custom.value=current;}const packageName=()=>select.value==='__custom'?custom.value.trim():select.value;const updateVeeqo=()=>{const name=packageName().toLowerCase().replace(/\s+/g,'');const link=document.querySelector('#veeqo-shortcut');if(link){link.hidden=!(name.includes('ups')||name==='medium'||name==='ombox');link.style.display=link.hidden?'none':'';}};const showPackage=()=>{updateVeeqo();customWrap.hidden=select.value!=='__custom';if(packageName())printed.textContent=packageName();};updateVeeqo();select.addEventListener('change',showPackage);custom.addEventListener('input',showPackage);document.querySelector('#print-slip').addEventListener('click',()=>window.print());document.querySelector('#save-package').addEventListener('click',async()=>{const value=packageName();if(!value){status.textContent='Write a package name first.';custom.focus();return;}status.textContent='Saving package choice…';try{const form=new URLSearchParams({packageName:value}),headers={'Content-Type':'application/x-www-form-urlencoded',Accept:'application/json'};if(csrf)headers[csrf.dataset.header||'X-CSRF-TOKEN']=csrf.value;const response=await fetch('/app/orders/'+encodeURIComponent(root.dataset.orderId)+'/packing-slip/package',{method:'POST',headers,body:form});const data=await response.json();if(!response.ok)throw new Error(data.message||'Could not save package choice.');printed.textContent=data.packageName;status.textContent='Package saved for this order’s ASIN and quantity.';}catch(error){status.textContent=error.message;}});})();
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
