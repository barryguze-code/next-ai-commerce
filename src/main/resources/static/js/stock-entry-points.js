(() => {
  if(!document.getElementById('stock-editor'))return;
  const action=(context)=>{
    const button=document.createElement('button');button.type='button';button.textContent='Adjust Inventory';
    button.dataset.actionIcon='±';button.dataset.actionDescription='Update individual units, location, and expiration date';
    Object.assign(button.dataset,context);button.onclick=event=>{event.stopPropagation();window.openSharedInventoryAdjustment(button)};return button;
  };
  const menuFor=context=>{
    const trigger=document.createElement('button');trigger.type='button';trigger.className='plan-action-button inventory-stage-menu';trigger.textContent='⋯';trigger.setAttribute('aria-label','Inventory actions');
    const panel=document.createElement('div');panel.className='stock-action-popover';panel.setAttribute('popover','auto');const button=action(context);button.className='secondary-button compact-button';panel.append(button);document.body.append(panel);
    trigger.popoverTargetElement=panel;button.addEventListener('click',()=>panel.hidePopover());
    panel.addEventListener('toggle',()=>{if(!panel.matches(':popover-open'))return;const box=trigger.getBoundingClientRect();panel.style.left=Math.max(12,Math.min(box.left,innerWidth-panel.offsetWidth-12))+'px';panel.style.top=Math.max(12,Math.min(box.bottom+6,innerHeight-panel.offsetHeight-12))+'px'});
    return trigger;
  };
  // Install before the deferred table widget moves context actions into its standard … menu.
  document.querySelectorAll('.catalog-row').forEach(row=>{
    const source=row.querySelector('[data-item-id]');if(!source)return;
    const menu=document.createElement('div');menu.dataset.contextMenu='';menu.dataset.title=source.dataset.itemName;
    menu.append(action({item:source.dataset.itemId}));source.parentElement.append(menu);
  });
  document.querySelectorAll('.sku-table tbody tr').forEach(row=>{
    const source=row.querySelector('[name="sellerSku"]');if(!source||row.querySelector('.sku-open-actions'))return;
    const cell=row.querySelector('td:last-child'),menu=document.createElement('div');menu.dataset.contextMenu='';menu.dataset.title='Marketplace SKU';
    menu.append(action({sellerSku:source.value}));cell.append(menu);
  });
  document.querySelectorAll('.inventory-row').forEach(row=>{
    if(row.querySelector('[data-context-action]'))return;
    const holder=row.querySelector('.inventory-stage-actions');if(!holder)return;
    holder.querySelector('.action-unavailable')?.remove();
    const button=menuFor({...row.dataset});button.dataset.contextAction='';holder.append(button);
  });
  const legacy=document.querySelector('#inventory-action-menu [onclick^="openInventoryAdjustment"]');
  if(legacy)legacy.onclick=()=>{const item=document.getElementById('quick-action-item').value,location=document.getElementById('quick-action-location').value,expiration=document.getElementById('quick-action-expiration').value;window.openSharedInventoryAdjustment({dataset:{item,location,expiration}})};
  const toolbar=[...document.querySelectorAll('.card-toolbar')].find(t=>t.querySelector('h2')?.textContent==='Invoices & packing lists');
  if(toolbar){const button=document.createElement('button');button.type='button';button.className='secondary-button compact-button';button.textContent='＋ Receive item';button.onclick=()=>window.openSharedInventoryReceipt();toolbar.append(button)}
  const input=document.getElementById('inventory-search'),table=document.querySelector('.available-inventory-table');
  if(!input||!table)return;
  let timer,controller;
  input.addEventListener('input',()=>{
    clearTimeout(timer);controller?.abort();table.querySelectorAll('[data-stock-zero]').forEach(row=>row.remove());const query=input.value.trim();if(query.length<2)return;
    timer=setTimeout(async()=>{
      controller=new AbortController();
      try{
        const response=await fetch('/app/catalog/product-search?q='+encodeURIComponent(query),{signal:controller.signal,headers:{Accept:'application/json'}});if(!response.ok)return;
        const products=await response.json();if(input.value.trim()!==query)return;
        const existing=new Set([...table.querySelectorAll('.inventory-row')].map(row=>row.dataset.item));
        products.filter(p=>!existing.has(p.id)).forEach(product=>{
          const row=document.createElement('tr');row.className='inventory-row inventory-stockout';row.dataset.stockZero='';row.dataset.item=product.id;row.dataset.product=product.name;row.dataset.image=product.imageUrl||'';row.dataset.sku=product.accountSku||product.vendorItemCode||'';row.dataset.location='';row.dataset.locationCode='No current location';row.dataset.expiration='';row.dataset.onHand='0';row.dataset.reserved='0';row.dataset.available='0';row.dataset.received='0';row.dataset.firstReceived='Not recorded';row.dataset.lastMovement='See movement history';row.dataset.status='UNDATED';row.dataset.search=[product.name,product.brand,product.vendorItemCode,product.accountSku,product.identifier,query].filter(Boolean).join(' ').toLowerCase();
          const cell=document.createElement('td'),stage=document.createElement('div');stage.className='stock-zero-result';cell.append(stage);row.append(cell);
          if(product.imageUrl){const image=document.createElement('img');image.src=product.imageUrl;image.alt='';image.onerror=()=>image.hidden=true;stage.append(image)}
          const copy=document.createElement('div'),name=document.createElement('strong'),detail=document.createElement('small');name.textContent=product.name;name.className='inventory-product-detail-link';name.tabIndex=0;name.setAttribute('role','button');name.setAttribute('aria-label','Open inventory details for '+product.name);const openHistory=event=>{event.preventDefault();event.stopPropagation();window.openInventoryHistory(row)};name.addEventListener('click',openHistory);name.addEventListener('keydown',event=>{if(event.key==='Enter'||event.key===' '){openHistory(event)}});detail.textContent=[product.vendorItemCode||product.accountSku,'No inventory recorded'].filter(Boolean).join(' · ');copy.append(name,detail);stage.append(copy);
          stage.prepend(menuFor({item:product.id}));
          ['—','0 each','0 each','0 each','—','No stock','Not valued'].forEach(value=>{const td=document.createElement('td');td.textContent=value;row.append(td)});
          [...table.querySelectorAll('thead th')].forEach((heading,i)=>{const td=row.cells[i];if(td){td.dataset.column=heading.dataset.column||'';td.style.display=heading.style.display;td.style.order=heading.style.order}});
          table.tBodies[0].append(row);
        });window.applyInventoryFilters?.();table.dispatchEvent(new Event('table:filter'));
      }catch(error){if(error.name!=='AbortError')console.warn('Zero-stock catalogue lookup unavailable');}
    },180);
  });
})();
