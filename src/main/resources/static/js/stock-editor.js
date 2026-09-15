(() => {
  const dialog=document.getElementById('stock-editor');if(!dialog)return;
  const form=dialog.querySelector('form'),fields=form.querySelector('[data-stock-fields]'),save=form.querySelector('[data-stock-save]');
  const feedback=form.querySelector('[data-stock-feedback]'),loading=form.querySelector('[data-stock-loading]');
  const search=form.querySelector('[data-stock-search]'),results=form.querySelector('[data-stock-results]');
  let mode='adjust',items=[],positions=[],revision=0,changed=false,searchTimer,searchController;
  const get=async(url,signal)=>{const response=await fetch(url,{signal,headers:{Accept:'application/json'}});if(!response.ok)throw new Error('Inventory could not be loaded. Please try again.');return response.json()};
  const balance=()=>{
    const p=positions.find(p=>p.itemId===form.elements.itemId.value&&p.locationId===form.elements.locationId.value&&(p.expirationDate||'')===form.elements.expirationDate.value);
    form.querySelector('[data-stock-balance]').textContent=`${p?.onHand||0} on hand · ${p?.reserved||0} reserved · ${p?.available||0} available at this location and date`;
  };
  const selectItem=()=>{
    const item=items.find(p=>p.id===form.elements.itemId.value);if(!item)return;
    const options=[...form.elements.locationId.options];form.elements.locationId.value=options.some(o=>o.value===item.defaultLocationId)?item.defaultLocationId:options[0]?.value||'';
    form.elements.expirationDate.required=item.expirationRequired;form.elements.expirationDate.value='';
    form.querySelector('[data-stock-expiration-help]').textContent=item.expirationRequired?'Required for this item.':'Optional for FIFO inventory.';
    const picture=form.querySelector('[data-stock-picture]');picture.hidden=!item.imageUrl;picture.src=item.imageUrl||'';picture.onerror=()=>picture.hidden=true;
    balance();
  };
  const load=async(ids,context={})=>{
    const request=++revision;fields.disabled=true;save.disabled=true;loading.hidden=false;feedback.hidden=true;
    try{
      const params=new URLSearchParams();ids.forEach(id=>params.append('itemId',id));
      const [options,stock]=await Promise.all([get('/app/inventory/adjustment-options?'+params),get('/app/inventory/adjustment-positions?'+params)]);
      if(request!==revision)return;items=options.items;positions=stock;
      if(!items.length)throw new Error('No active mapped catalogue item was found. Map the SKU first.');
      form.elements.itemId.replaceChildren(...items.map(p=>new Option([p.name,p.itemCode].filter(Boolean).join(' · '),p.id)));
      form.elements.locationId.replaceChildren(...options.locations.filter(p=>p.status==='ACTIVE').map(p=>new Option(p.code+' · '+p.name,p.id)));
      if(!form.elements.locationId.options.length)throw new Error('Add an active storage location before adjusting inventory.');
      selectItem();if(context.location)form.elements.locationId.value=context.location;if(context.expiration)form.elements.expirationDate.value=context.expiration;
      balance();fields.disabled=false;save.disabled=false;
    }catch(error){if(request===revision){feedback.textContent=error.message;feedback.className='error';feedback.hidden=false}}
    finally{if(request===revision)loading.hidden=true}
  };
  const open=receive=>{
    revision++;form.reset();search.disabled=false;mode=receive?'receive':'adjust';fields.disabled=true;save.disabled=true;feedback.hidden=true;loading.hidden=true;results.replaceChildren();
    form.querySelector('[data-stock-search-label]').hidden=!receive;
    form.querySelector('[data-stock-direction-label]').hidden=receive;form.querySelector('[data-stock-reason-label]').hidden=receive;
    form.elements.notes.required=receive;
    document.getElementById('stock-editor-title').textContent=receive?'Receive item':'Adjust Inventory';
    save.textContent=receive?'Receive item':'Save adjustment';
    form.querySelector('[data-stock-note]').textContent=receive?'Received without an invoice at zero cost. The explanation and receipt remain in the ledger.':'Changes are recorded in the inventory ledger. Reserved stock cannot be removed.';
    document.querySelectorAll('details.table-row-warning[open]').forEach(d=>d.open=false);window.closeInventoryActionMenu?.();
    dialog.showModal();if(receive)search.focus();
  };
  window.openSharedInventoryAdjustment=async button=>{
    const context=button.dataset;open(false);loading.hidden=false;
    try{
      let ids=context.item?[context.item]:[];
      if(context.sellerSku){const components=await get('/app/marketplace-skus/mappings/components?sku='+encodeURIComponent(context.sellerSku));ids=[...new Set(components.map(c=>c.itemId))]}
      if(!ids.length)throw new Error('Map this SKU to a catalogue item first, then adjust its inventory.');
      await load(ids,context);
    }catch(error){loading.hidden=true;feedback.textContent=error.message;feedback.className='error';feedback.hidden=false}
  };
  window.openSharedInventoryReceipt=()=>open(true);
  form.elements.itemId.addEventListener('change',selectItem);
  form.elements.locationId.addEventListener('change',balance);form.elements.expirationDate.addEventListener('change',balance);
  search.addEventListener('input',()=>{
    clearTimeout(searchTimer);searchController?.abort();revision++;fields.disabled=true;save.disabled=true;results.replaceChildren();const query=search.value.trim();if(query.length<2)return;
    searchTimer=setTimeout(async()=>{
      const controller=new AbortController();searchController=controller;
      try{const products=await get('/app/catalog/product-search?q='+encodeURIComponent(query),controller.signal);if(search.value.trim()!==query)return;
        products.forEach(product=>{
          const button=document.createElement('button');button.type='button';
          if(product.imageUrl){const img=document.createElement('img');img.src=product.imageUrl;img.alt='';img.loading='lazy';img.onerror=()=>img.hidden=true;button.append(img)}
          const copy=document.createElement('span'),label=document.createElement('strong'),detail=document.createElement('small');label.textContent=product.name;detail.textContent=[product.vendorItemCode,product.brand,product.identifier].filter(Boolean).join(' · ');copy.append(label,detail);button.append(copy);
          button.onclick=()=>{search.value=product.name;results.replaceChildren();load([product.id])};results.append(button);
        });if(!products.length)results.textContent='No matching active catalogue items.';
      }catch(error){if(error.name!=='AbortError')results.textContent=error.message}
    },150);
  });
  form.addEventListener('submit',async event=>{
    event.preventDefault();if(save.disabled)return;
    const data=new URLSearchParams(new FormData(form));const amount=Number(form.elements.amount.value);
    data.set(mode==='receive'?'quantity':'quantityChange',String(amount*(mode==='receive'?1:Number(form.elements.direction.value))));
    if(!data.get('expirationDate'))data.delete('expirationDate');save.disabled=true;feedback.hidden=true;
    try{
      const response=await fetch(mode==='receive'?'/app/inventory/receipts/inline':'/app/inventory/adjustments/inline',{method:'POST',headers:{Accept:'application/json','Content-Type':'application/x-www-form-urlencoded'},body:data});
      const result=await response.json();if(!response.ok)throw new Error(result.message||'Nothing could be saved. Please try again.');
      changed=true;fields.disabled=true;search.disabled=true;feedback.className='';feedback.textContent=result.message;feedback.hidden=false;
      form.querySelectorAll('[data-stock-close]').forEach(b=>{if(!b.classList.contains('dialog-close'))b.textContent='Done'});
    }catch(error){feedback.className='error';feedback.textContent=error.message;feedback.hidden=false;save.disabled=false}
  });
  form.querySelectorAll('[data-stock-close]').forEach(b=>b.onclick=()=>dialog.close());
  dialog.addEventListener('close',()=>{revision++;searchController?.abort();if(changed)location.reload()});
})();
