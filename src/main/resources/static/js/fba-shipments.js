// Preserve the Received column identity when its visible heading becomes Amazon received.
document.addEventListener('DOMContentLoaded',()=>{const prefs=window.NextAiTablePreferences,key=location.pathname.replace(/\W+/g,'-')+'-0',saved=prefs?.read(key);if(saved?.order?.includes('amazon-received')){const order=saved.order.filter(id=>id!=='amazon-received'&&id!=='received');order.splice(Math.max(0,order.indexOf('shipped')+1),0,'received');const visibility={...saved.visibility,received:saved.visibility['amazon-received']??true};delete visibility['amazon-received'];prefs.write(key,{...saved,order,visibility});}});
(()=>{
 const dialog=document.getElementById('fba-shipment-dialog');if(!dialog)return;
 const find=s=>dialog.querySelector(s),text=(tag,value)=>{const node=document.createElement(tag);node.textContent=value??'';return node;};
 let shipment='',page=0,opener,request,version=0;
 async function load(){
  request?.abort();request=new AbortController();const current=++version;
  find('[data-fba-item-status]').textContent='Loading shipment items…';find('[data-fba-items]').replaceChildren();
  find('[data-fba-shipment-cost]')?.remove();
  find('[data-fba-prev]').disabled=find('[data-fba-next]').disabled=true;
  try{
   const response=await fetch('/app/inventory/fba-shipments/items?'+new URLSearchParams({shipment,q:find('input[type=search]').value,page}),{signal:request.signal,headers:{Accept:'application/json'}});
   if(!response.ok)throw Error('Shipment items could not be loaded. Please retry.');
   const result=await response.json();if(current!==version)return;page=result.page;
   for(const item of result.rows){
    const row=text('tr',''),product=text('td',''),identity=text('div',''),picture=text('span',''),copy=text('div','');
    identity.className='fba-item-identity';picture.className='fba-item-picture';copy.className='fba-item-copy';
    if(item.imageUrl){try{const url=new URL(item.imageUrl,location.origin);if(['https:','http:'].includes(url.protocol)){const img=document.createElement('img');img.src=url.href;img.alt='';img.loading='lazy';img.onerror=()=>img.remove();picture.append(img);}}catch(_){}}
    picture.setAttribute('aria-hidden','true');copy.append(text('strong',item.title||'Product title not supplied'),text('small','SKU · '+item.sku),text('small','FNSKU · '+(item.fnsku||'Not supplied')));
    identity.append(picture,copy);product.append(identity);row.append(product,text('td',item.shipped),text('td',item.received));find('[data-fba-items]').append(row);
   }
   let costPanel=find('[data-fba-shipment-cost]');if(!costPanel){costPanel=text('p','');costPanel.dataset.fbaShipmentCost='';find('[data-fba-item-status]').after(costPanel);}
   const cost=result.costs,amount=value=>value==null||!cost?.currency?'Not imported':new Intl.NumberFormat('en-US',{style:'currency',currency:cost.currency,minimumFractionDigits:2,maximumFractionDigits:2}).format(value);
   costPanel.textContent='Shipping to FBA · '+(cost?.perUnit==null?'Charges incomplete':amount(cost.perUnit)+' / shipped unit');
   costPanel.dataset.tooltip=`Transportation: ${amount(cost?.transportation)} · Placement: ${amount(cost?.placement)} · ${cost?.shippedUnits??0} shipped units. Posted net charges; credits reduce costs. Missing charges are not zero.`;costPanel.tabIndex=0;
   const pages=Math.max(1,Math.ceil(result.total/result.size));find('[data-fba-page]').textContent=`Page ${page+1} of ${pages}`;
   find('[data-fba-item-status]').textContent=result.total?`${result.total} matching SKUs · shipped and Amazon-received units are shown separately.`:'No matching items imported yet.';
   find('[data-fba-prev]').disabled=page===0;find('[data-fba-next]').disabled=page+1>=pages;
  }catch(error){if(error.name!=='AbortError'&&current===version)find('[data-fba-item-status]').textContent=error.message;}
 }
 document.addEventListener('click',event=>{const button=event.target.closest('[data-fba-shipment]');if(!button)return;opener=button;shipment=button.dataset.fbaShipment;page=0;find('input[type=search]').value='';find('#fba-shipment-title').textContent=shipment;dialog.showModal();load();});
 find('[data-fba-item-search]').addEventListener('submit',event=>{event.preventDefault();page=0;load();});
 find('[data-fba-prev]').onclick=()=>{page--;load();};find('[data-fba-next]').onclick=()=>{page++;load();};
 dialog.querySelectorAll('[data-fba-close]').forEach(button=>button.onclick=()=>dialog.close());
 dialog.addEventListener('close',()=>{request?.abort();version++;opener?.focus();});
})();

(()=>{const form=document.querySelector('[data-fba-sync]');if(!form)return;const button=form.querySelector('button'),feedback=document.querySelector('[data-fba-sync-status]');let timer,polls=0,wasRunning=feedback.dataset.running==='true';async function refreshRows(){const y=window.scrollY;const response=await fetch(location.href,{headers:{Accept:'text/html'}});if(!response.ok)return;const doc=new DOMParser().parseFromString(await response.text(),'text/html');for(const selector of ['.fba-shipment-table','.table-pagination']){const fresh=doc.querySelector(selector),current=document.querySelector(selector);if(fresh&&current)current.replaceWith(fresh);}window.NextAiTableWidget?.refresh();window.scrollTo({top:y});}async function check(){try{const response=await fetch(form.action,{headers:{Accept:'application/json'}});if(!response.ok)throw Error();const state=await response.json();feedback.hidden=state.running||!state.message;feedback.textContent=state.running?'':state.message||'';const pill=document.querySelector('[data-fba-compact-status]');pill.textContent=state.running?'Syncing…':state.status==='COMPLETED'?'Synced':state.status==='NOT_STARTED'?'Not synced':'Needs attention';pill.classList.toggle('syncing',state.running);pill.classList.toggle('not-synced',!state.running&&state.status!=='COMPLETED');button.textContent=state.running&&!state.enabled?'Syncing…':'↻ Sync now';button.disabled=!state.enabled;document.querySelector('[data-fba-check]').textContent=state.checkedAt?'Last Amazon shipment check · '+new Date(state.checkedAt).toLocaleString():'Amazon shipment check · Not completed';if(state.running&&++polls<900)timer=setTimeout(check,2500);else if(!state.running){button.textContent='↻ Sync now';if(wasRunning)await refreshRows();}wasRunning=state.running;}catch(_){feedback.hidden=false;feedback.textContent='Status unavailable. Refresh to check the saved sync progress.';button.disabled=false;}}form.addEventListener('submit',async event=>{event.preventDefault();if(button.disabled)return;button.disabled=true;feedback.hidden=true;document.querySelector('[data-fba-compact-status]').textContent='Syncing…';try{const response=await fetch(form.action,{method:'POST',body:new FormData(form)}),value=await response.json();if(!response.ok)throw Error(value.error||'Sync could not start.');polls=0;wasRunning=true;clearTimeout(timer);check();}catch(error){feedback.hidden=false;feedback.textContent=error.message;button.disabled=false;}});if(feedback.dataset.running==='true')check();})();
