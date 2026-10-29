// Entry-point adapter only. Markup, movement loading and drawer styling are shared with Available Inventory.
(() => {
 let request,positions=[],source,components=[],designPreview=false;
 const picker=document.getElementById('history-position-picker'),select=document.getElementById('history-position-select');
 const drawer=document.getElementById('inventory-history-dialog');
 if(!drawer)return;
 function chooseItem(items,sku,active,origin){
  return new Promise(resolve=>{
   const modal=document.createElement('dialog');modal.className='invite-dialog bundle-ledger-choice';modal.setAttribute('aria-labelledby','bundle-ledger-title');
   modal.innerHTML='<div class="dialog-shell"><header class="dialog-header"><div><h2 id="bundle-ledger-title">Choose an inventory item</h2><p class="bundle-ledger-context"></p></div><button type="button" class="dialog-close" aria-label="Close item selection">×</button></header><div class="dialog-body"><p>This bundle contains multiple items. Which inventory ledger would you like to open?</p><div class="bundle-ledger-options"></div></div><footer class="dialog-actions"><button type="button" class="secondary-button">Cancel</button></footer></div>';
   modal.querySelector('.bundle-ledger-context').textContent=sku;
   let chosen=null;
   items.forEach(item=>{const button=document.createElement('button');button.type='button';button.className='bundle-ledger-option';const name=document.createElement('strong'),code=document.createElement('span');name.textContent=item.productName||'Inventory item';code.textContent=(item.vendorItemCode||item.accountSku||'')+(item.quantity!=null?' × '+item.quantity:'');button.append(name,code);button.onclick=()=>{chosen=item.itemId;modal.close();};modal.querySelector('.bundle-ledger-options').append(button);});
   modal.querySelectorAll('.dialog-close,.dialog-actions button').forEach(button=>button.onclick=()=>modal.close());
   modal.addEventListener('click',event=>{if(event.target===modal)modal.close();});
   const abort=()=>{chosen=null;modal.close();};active.signal.addEventListener('abort',abort,{once:true});
   modal.addEventListener('close',()=>{active.signal.removeEventListener('abort',abort);modal.remove();if(!chosen&&origin?.isConnected)origin.focus();resolve(chosen);},{once:true});
   document.body.append(modal);modal.showModal();
  });
 }
 const itemPicker=document.createElement('label'),itemSelect=document.createElement('select');
 itemPicker.className='history-item-picker';itemPicker.hidden=true;itemPicker.append('Mapped item ',itemSelect);picker.before(itemPicker);
 itemSelect.setAttribute('aria-label','Mapped inventory item');itemSelect.dataset.standardChoice='';
 async function loadItem(id,active){
  window.setManagedLocationLoadState?.('loading');
  const response=await fetch('/app/inventory/'+encodeURIComponent(id)+'/history-panel',{signal:active.signal,headers:{Accept:'application/json'}});
  if(!response.ok)throw new Error();const context=await response.json();if(active.signal.aborted||request!==active)return;
  if(!Array.isArray(context.locations))throw Error('Account locations unavailable');window.hydrateManagedLocations?.(context.locations);positions=context.positions;if(!positions.length)throw new Error();
  const destination=document.getElementById('location-destination');destination.replaceChildren();
  context.locations.filter(location=>location.status==='ACTIVE').forEach(location=>destination.add(new Option(location.code+' · '+location.name,location.id)));
  select.replaceChildren();positions.forEach((position,index)=>select.add(new Option((position.locationCode||'No location')+' · '+(position.expiration?window.NextAiShortDates.display(position.expiration):'No expiration'),index)));
  select.value='0';openPosition(0);window.NextAiLedgerDesignPreview?.positions(positions);itemPicker.hidden=components.length<2;window.NextAiPlatformControls?.enhance(itemPicker);itemSelect.dispatchEvent(new Event('platform-choice-sync'));
 }
 itemSelect.addEventListener('change',async()=>{
  request?.abort();const active=request=new AbortController();const previous=document.getElementById('location-item').value;
  const sidebar=drawer.querySelector('.position-sidebar');if(sidebar)sidebar.inert=true;
  itemSelect.setAttribute('aria-busy','true');
  try{await loadItem(itemSelect.value,active);}catch(error){if(error.name!=='AbortError'){window.setManagedLocationLoadState?.('error',()=>itemSelect.dispatchEvent(new Event('change')));itemSelect.value=previous;itemSelect.dispatchEvent(new Event('platform-choice-sync'));const message=document.createElement('p');message.className='history-error';message.setAttribute('role','alert');message.textContent='Could not load the selected item. Previous inventory remains displayed.';itemPicker.after(message);}}
  finally{if(request===active){if(sidebar)sidebar.inert=false;itemSelect.removeAttribute('aria-busy');}}
 });
 function openPosition(index){
  const sidebar=drawer.querySelector('.position-sidebar');if(sidebar)sidebar.inert=false;
  drawer.querySelectorAll('.history-error').forEach(message=>message.remove());
  const row=document.createElement('div');
  Object.assign(row.dataset,positions[index]);row.dataset.ledgerDesignPreview=String(designPreview);
  const itemId=source?new URL(source.href).searchParams.get('itemId'):null;
  const parent=source?.closest('[data-replenishment-row]')||[...document.querySelectorAll('[data-replenishment-row]')].find(row=>row.dataset.item===itemId);
  const pictureForm=parent?.querySelector('.product-image-upload');
  if(pictureForm)row.append(pictureForm.cloneNode(true));
  window.openInventoryHistory(row);
  picker.hidden=positions.length<2;
 }
 select.addEventListener('change',()=>openPosition(Number(select.value)));
 window.refreshInventoryHistoryContext=async()=>{const id=document.getElementById('location-item').value;if(!id)throw Error('Inventory context unavailable');if(!source){source=document.createElement('a');source.href='/app/inventory/ledger?itemId='+encodeURIComponent(id);}request?.abort();const active=request=new AbortController();try{await loadItem(id,active);}catch(error){window.setManagedLocationLoadState?.('error',()=>window.refreshInventoryHistoryContext());throw error;}};
 async function openLedger(link,choose=false,origin=link,renderedItems){
  request?.abort();request=new AbortController();const active=request;
  source=link;designPreview=true;link.setAttribute('aria-busy','true');
  document.querySelector('[data-history-entry-error]')?.remove();
  try{
   const url=new URL(link.href);let id=url.searchParams.get('itemId');components=[];
   if(choose&&renderedItems?.length){components=renderedItems;id=components.length===1?components[0].itemId:await chooseItem(components,origin.textContent.trim(),active,origin);if(active.signal.aborted||!id)return;}
   else if(!id){const response=await fetch('/app/marketplace-skus/mappings/components?sku='+encodeURIComponent(url.searchParams.get('sku')||''),{signal:active.signal,headers:{Accept:'application/json'}});if(!response.ok)throw new Error();components=await response.json();if(active.signal.aborted)return;const code=choose?null:url.searchParams.get('code');const matches=code?components.filter(item=>item.vendorItemCode===code||item.accountSku===code):[];id=matches.length===1?matches[0].itemId:components.length===1?components[0].itemId:null;if(!id&&components.length>1)id=await chooseItem(components,url.searchParams.get('sku'),active,origin);if(active.signal.aborted||!id)return;}
   itemSelect.replaceChildren();components.forEach(item=>itemSelect.add(new Option((item.vendorItemCode||item.accountSku||'Item')+' · '+(item.productName||'Inventory item'),item.itemId)));itemSelect.value=id;
   await loadItem(id,active);
  }catch(error){
   if(error.name==='AbortError')return;
   window.setManagedLocationLoadState?.('error',()=>openLedger(link,choose,origin,renderedItems));
   const message=document.createElement('span');message.dataset.historyEntryError='';message.className='history-error';message.setAttribute('role','alert');
   message.textContent='Inventory history could not be opened. Please try again.';link.after(message);
  }finally{link.removeAttribute('aria-busy');}
 }
 window.openMappedInventoryLedger=openLedger;
 document.addEventListener('click',event=>{
  const link=event.target.closest('a[href*="/app/inventory/ledger?itemId="],a[href*="/app/marketplace-skus/mappings/ledger?"]');
  if(!link||event.ctrlKey||event.metaKey||event.shiftKey||event.altKey||new URL(link.href).origin!==location.origin)return;
  event.preventDefault();openLedger(link);
 });
 drawer.addEventListener('click',event=>{if(event.target===drawer)drawer.close();});
 drawer.addEventListener('close',()=>request?.abort());
})();
