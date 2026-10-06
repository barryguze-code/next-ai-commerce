/* Explicit confirmation only; local mode never publishes to Amazon. */
(()=>{
 let modal,revision=0;
 const money=v=>new Intl.NumberFormat('en-US',{style:'currency',currency:'USD'}).format(v);
 const api='/app/inventory/profit';
 async function json(path,body){
  const token=document.querySelector('[data-profit-csrf]');
  const r=await fetch(api+path,body?{method:'POST',headers:{'Content-Type':'application/json',...(token?{[token.dataset.header]:token.value}:{})},body:JSON.stringify(body)}:undefined);
  const result=await r.json();if(!r.ok)throw Error(result.message||'Could not prepare this price.');return result;
 }
 window.openSkuPricing=async(stage,sale)=>{
  const seq=++revision,sku=stage.dataset.sellerSku;
  if(!modal){
   modal=document.createElement('dialog');modal.className='invite-dialog profit-dialog sku-pricing-dialog';
   modal.innerHTML='<form class="dialog-shell"><header class="dialog-header"><img class="profit-calculator-art" src="/images/platform/table/profit-calculator.png" alt=""><div><h2></h2><p data-sku></p></div><button type="button" class="dialog-close" aria-label="Close pricing">×</button></header><div class="dialog-body"><p data-current></p><div class="sku-pricing-fields"><label>New price<input name="amount" type="number" min="0.01" max="100000" step="0.01" required></label><label data-sale>Discount %<input name="discount" type="number" min="0" max="99.99" step="0.01"></label><label data-sale>Start date<input name="start" type="date"></label><label data-sale>End date<input name="end" type="date"></label></div><p data-estimate></p><p data-feedback role="status"></p><div data-confirm></div></div><footer class="dialog-actions"><small>Changes future SKU sales only, never historical orders. Sale dates use Amazon marketplace dates.</small><button type="submit" class="primary-button">Review price</button></footer></form>';
   modal.querySelector('.dialog-close').onclick=()=>modal.close();modal.addEventListener('close',()=>revision++);document.body.append(modal);
  }
  const form=modal.querySelector('form'),feedback=modal.querySelector('[data-feedback]'),confirm=modal.querySelector('[data-confirm]'),submit=form.querySelector('[type=submit]');
  form.reset();form.onsubmit=e=>e.preventDefault();form.oninput=null;confirm.replaceChildren();submit.disabled=true;feedback.textContent='Loading current SKU price…';modal.querySelector('h2').textContent=sale?'Set sale price':'Change SKU price';modal.querySelector('[data-sku]').textContent=sku;
  modal.querySelectorAll('[data-sale]').forEach(n=>n.hidden=!sale);form.elements.start.required=form.elements.end.required=sale;
  modal.showModal();
  try{
   const [data,support]=await Promise.all([json('?'+new URLSearchParams({kind:'SKU',key:sku})),json('/price-support')]);
   if(seq!==revision)return;const view=data[sku],line=view?.lines?.[0];if(!line||view.currency!=='USD'||line.unitPrice==null)throw Error('A current USD listing price is required.');
   const base=Number(line.unitPrice);modal.querySelector('[data-current]').textContent='Current price: '+money(base);form.elements.amount.value=base.toFixed(2);feedback.textContent=support.enabled?'Review first, then explicitly confirm sending to Amazon.':'Local preview — Amazon publishing is disabled.';
   submit.disabled=false;
   const update=e=>{
    confirm.replaceChildren();if(e?.target===form.elements.discount)form.elements.amount.value=(base*(1-Number(form.elements.discount.value)/100)).toFixed(2);
    else if(sale)form.elements.discount.value=(100*(base-Number(form.elements.amount.value))/base).toFixed(2);
    const price=Number(form.elements.amount.value),shipping=view.totals?.shippingCost;
    const profit=line.productCost==null||shipping==null?null:price-Math.round(price*(price<=15?.08:.15)*100)/100-line.productCost-line.otherCost-shipping;
    modal.querySelector('[data-estimate]').textContent='Estimated profit / SKU: '+(profit==null?'Costs pending':money(profit));
   };
   form.oninput=update;update();
   form.onsubmit=async e=>{
    e.preventDefault();confirm.replaceChildren();
    const amount=Number(form.elements.amount.value),start=form.elements.start.value,end=form.elements.end.value;
    if(sale&&(amount>=base||!start||!end||end<start)){feedback.textContent='Enter a price below the current price and an end date on or after the start date.';return;}
    if(!support.enabled){feedback.textContent='Preview only: '+money(amount)+(sale?' from '+start+' to '+end:'')+'. No Amazon price was changed.';return;}
    submit.disabled=true;
    try{
     const submitted=JSON.stringify([form.elements.amount.value,start,end]);
     const quote=await json(sale?'/sale-preview':'/price-preview',{sku,amount,...(sale?{start,end}:{})});if(seq!==revision||submitted!==JSON.stringify([form.elements.amount.value,form.elements.start.value,form.elements.end.value]))return;
     const copy=document.createElement('p');copy.textContent='Confirm '+sku+' at '+money(quote.newPrice)+(sale?' from '+quote.saleStart+' to '+quote.saleEnd:'')+'?';
     const send=document.createElement('button');send.type='button';send.className='primary-button';send.textContent='Confirm & send to Amazon';
     const snapshot=JSON.stringify([form.elements.amount.value,start,end]);
     send.onclick=async()=>{if(snapshot!==JSON.stringify([form.elements.amount.value,form.elements.start.value,form.elements.end.value])){confirm.replaceChildren();return;}send.disabled=true;try{const result=await json('/price',{confirmation:quote.id});feedback.textContent=result.message;}catch(error){feedback.textContent=error.message;}finally{confirm.replaceChildren();}};
     confirm.append(copy,send);
    }catch(error){feedback.textContent=error.message;}finally{submit.disabled=false;}
   };
  }catch(error){if(seq===revision)feedback.textContent=error.message;}
 };
})();
