// Entry-point adapter only. Markup, movement loading and drawer styling are shared with Available Inventory.
(() => {
 let request,positions=[],source;
 const picker=document.getElementById('history-position-picker'),select=document.getElementById('history-position-select');
 const drawer=document.getElementById('inventory-history-dialog');
 function openPosition(index){
  const row=document.createElement('div');
  Object.assign(row.dataset,positions[index]);
  const itemId=source?new URL(source.href).searchParams.get('itemId'):null;
  const parent=source?.closest('[data-replenishment-row]')||[...document.querySelectorAll('[data-replenishment-row]')].find(row=>row.dataset.item===itemId);
  const pictureForm=parent?.querySelector('.product-image-upload');
  if(pictureForm)row.append(pictureForm.cloneNode(true));
  window.openInventoryHistory(row);
  picker.hidden=positions.length<2;
 }
 select.addEventListener('change',()=>openPosition(Number(select.value)));
 document.addEventListener('click',async event=>{
  const link=event.target.closest('a[href^="/app/inventory/ledger?itemId="]');
  if(!link||event.ctrlKey||event.metaKey||event.shiftKey||event.altKey)return;
  event.preventDefault();request?.abort();request=new AbortController();const active=request;
  source=link;link.setAttribute('aria-busy','true');
  document.querySelector('[data-history-entry-error]')?.remove();
  try{
   const id=new URL(link.href).searchParams.get('itemId');
   const response=await fetch('/app/inventory/'+encodeURIComponent(id)+'/history-panel',{signal:active.signal,headers:{Accept:'application/json'}});
   if(!response.ok)throw new Error();
   const context=await response.json();if(active.signal.aborted)return;
   positions=context.positions;if(!positions.length)throw new Error();
   const destination=document.getElementById('location-destination');destination.replaceChildren();
   context.locations.filter(location=>location.status==='ACTIVE').forEach(location=>destination.add(new Option(location.code+' · '+location.name,location.id)));
   select.replaceChildren();positions.forEach((position,index)=>select.add(new Option(position.locationCode+' · '+(position.expiration?formatCalendarDate(position.expiration):'No expiration'),index)));
   select.value='0';openPosition(0);
  }catch(error){
   if(error.name==='AbortError')return;
   const message=document.createElement('span');message.dataset.historyEntryError='';message.className='history-error';message.setAttribute('role','alert');
   message.textContent='Inventory history could not be opened. Please try again.';link.after(message);
  }finally{link.removeAttribute('aria-busy');}
 });
 drawer.addEventListener('click',event=>{if(event.target===drawer)drawer.close();});
 drawer.addEventListener('close',()=>request?.abort());
})();
