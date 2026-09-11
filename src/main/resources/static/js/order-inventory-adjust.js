(()=>{
  const dialog=()=>document.getElementById('order-inventory-adjust-dialog');
  const list=()=>document.querySelector('[data-inline-adjust-list]');
  const feedback=()=>document.querySelector('[data-inline-adjust-feedback]');
  const escape=value=>{const node=document.createElement('span');node.textContent=value??'';return node.innerHTML};
  const positionLabel=position=>[position.locationCode+' · '+position.locationName,
    position.expirationDate?'expires '+position.expirationDate:'FIFO'].join(' · ');
  const positionOption=position=>JSON.stringify({itemId:position.itemId,locationId:position.locationId,
    expirationDate:position.expirationDate||'',onHand:position.onHand,reserved:position.reserved,available:position.available});
  const showPosition=(card,position)=>{
    card.querySelector('[data-on-hand]').textContent=position.onHand+' each on hand';
    card.querySelector('[data-reserved]').textContent=position.reserved+' reserved';
    card.querySelector('[data-available]').textContent=position.available+' available';
    const expirationField=card.querySelector('[data-expiration-field]'),expirationInput=card.elements.expirationDate;
    expirationField.hidden=Boolean(position.expirationDate);expirationInput.required=!position.expirationDate;
    expirationInput.value=position.expirationDate||'';
  };
  const cardFor=(component,positions)=>{
    const card=document.createElement('form');card.className='order-inline-adjust-card';card.dataset.inlineAdjustCard='';
    const initial=(component.productName||'P').trim().charAt(0).toUpperCase();
    card.innerHTML='<span class="order-inline-adjust-picture"></span><div class="order-inline-adjust-main"><div class="order-inline-adjust-product"><div><strong></strong><small></small></div><span class="order-inline-pack"></span></div><div class="order-inline-adjust-fields"><label>Inventory position<select name="position" required></select></label><label data-expiration-field hidden>Expiration<input name="expirationDate" type="date"></label><label>Change<input name="quantityChange" type="number" step="1" inputmode="numeric" placeholder="+ / −" required></label><label>Reason<select name="reason"><option value="COUNT_CORRECTION">Count correction</option><option value="DAMAGE">Damaged</option><option value="LOSS">Lost or missing</option><option value="RETURN_TO_VENDOR">Returned to vendor</option><option value="OTHER">Other</option></select></label></div><div class="order-inline-adjust-position-note"><span data-on-hand></span><span data-reserved></span><b data-available></b></div><div class="order-inline-adjust-actions"><small>Saved to the permanent inventory ledger. Reserved stock cannot be removed.</small><button class="order-inline-adjust-save" type="submit">Save adjustment</button></div></div>';
    card.querySelector('strong').textContent=component.productName;
    card.querySelector('.order-inline-adjust-product small').textContent=[component.vendorItemCode,component.accountSku].filter(Boolean).join(' · ');
    card.querySelector('.order-inline-pack').textContent='× '+Number(component.quantity).toLocaleString()+' per SKU';
    const picture=card.querySelector('.order-inline-adjust-picture');picture.textContent=initial;
    const image=positions.find(position=>position.imageUrl)?.imageUrl;if(image){picture.style.backgroundImage='url("'+String(image).replaceAll('"','%22')+'")';picture.classList.add('has-image')}
    const select=card.elements.position;
    if(positions.length){positions.forEach(position=>select.add(new Option(positionLabel(position),positionOption(position))));showPosition(card,positions[0])}
    else{select.add(new Option('No inventory position found',''));select.disabled=true;card.elements.quantityChange.disabled=true;card.querySelector('button').disabled=true}
    select.addEventListener('change',()=>{if(select.value)showPosition(card,JSON.parse(select.value))});
    card.addEventListener('submit',saveAdjustment);
    return card;
  };
  const saveAdjustment=async event=>{
    event.preventDefault();const form=event.currentTarget,position=JSON.parse(form.elements.position.value),button=form.querySelector('button');
    const data=new URLSearchParams({itemId:position.itemId,locationId:position.locationId,
      quantityChange:form.elements.quantityChange.value,reason:form.elements.reason.value});
    const expirationDate=position.expirationDate||form.elements.expirationDate.value;if(expirationDate)data.set('expirationDate',expirationDate);
    const csrf=document.getElementById('buy-shipping-csrf'),headers={Accept:'application/json','Content-Type':'application/x-www-form-urlencoded'};
    if(csrf?.dataset.header)headers[csrf.dataset.header]=csrf.value;
    button.disabled=true;feedback().hidden=true;
    try{
      const response=await fetch('/app/inventory/adjustments/inline',{method:'POST',headers,body:data});
      const result=await response.json();if(!response.ok)throw new Error(result.message||'The adjustment could not be saved.');
      feedback().classList.remove('error');feedback().textContent=result.message;feedback().hidden=false;
      form.elements.quantityChange.value='';
      const page=await fetch(location.href,{headers:{'X-Order-Stream':'refresh'}}).then(value=>value.text());
      const incoming=new DOMParser().parseFromString(page,'text/html').querySelector('[data-order-stream]');
      const current=document.querySelector('[data-order-stream]');if(incoming&&current){current.replaceWith(incoming);window.NextAiTableWidget?.refresh()}
      setTimeout(()=>dialog()?.close(),650);
    }catch(error){feedback().classList.add('error');feedback().textContent=error.message;feedback().hidden=false}
    finally{button.disabled=false}
  };
  window.openOrderInventoryAdjustment=async button=>{
    const modal=dialog(),order=button.closest('.order-row'),skuButtons=[...order.querySelectorAll('.mapping-summary-button.is-mapped')];
    const skus=[...new Set(skuButtons.map(item=>item.dataset.sellerSku).filter(Boolean))];
    document.querySelector('[data-inline-adjust-order]').textContent=order.querySelector('.order-number span')?.textContent?.trim()||'Current order';
    feedback().hidden=true;list().hidden=true;document.querySelector('[data-inline-adjust-loading]').hidden=false;modal.showModal();
    try{
      const componentGroups=await Promise.all(skus.map(sku=>fetch('/app/marketplace-skus/mappings/components?sku='+encodeURIComponent(sku),{headers:{Accept:'application/json'}}).then(response=>response.ok?response.json():Promise.reject(new Error()))));
      const components=[...new Map(componentGroups.flat().map(component=>[component.itemId,component])).values()];
      if(!components.length)throw new Error('No mapped catalogue items were found for this order.');
      const params=new URLSearchParams();components.forEach(component=>params.append('itemId',component.itemId));
      const response=await fetch('/app/inventory/adjustment-positions?'+params,{headers:{Accept:'application/json'}});if(!response.ok)throw new Error();
      const positions=await response.json();list().replaceChildren(...components.map(component=>cardFor(component,positions.filter(position=>position.itemId===component.itemId))));
      list().hidden=false;
    }catch(_){feedback().classList.add('error');feedback().textContent='Inventory positions could not be loaded. Nothing was changed.';feedback().hidden=false}
    finally{document.querySelector('[data-inline-adjust-loading]').hidden=true}
  };
})();
