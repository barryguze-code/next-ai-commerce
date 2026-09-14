(()=>{
  function orderShortcutStyle(){
    if(document.getElementById('order-packing-shortcuts-style'))return;
    const style=document.createElement('style');style.id='order-packing-shortcuts-style';style.textContent='.order-reference-shortcuts{display:grid;grid-template-columns:repeat(2,minmax(0,max-content));gap:5px;max-width:100%;margin-top:6px}.order-reference-group,.order-reference-options{display:contents}.order-reference-group legend{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0 0 0 0);white-space:nowrap}.order-reference-options button{display:inline-flex;align-items:center;min-width:0;max-width:170px;overflow:hidden;padding:5px 9px;border:1px solid #cfdcea;border-radius:7px;background:#fff;color:#60758d;font:750 .66rem/1.1 inherit;letter-spacing:.012em;text-align:left;text-overflow:ellipsis;white-space:nowrap;cursor:pointer}.order-reference-options button:hover{position:relative;z-index:1;border-color:#8eb2e7;color:#2867bd;background:#f4f8ff}.order-reference-options button .reference-value{min-width:0;overflow:hidden;color:inherit;text-overflow:ellipsis}.order-reference-options button.active{position:relative;z-index:2;border-color:#2d6cdf;background:#2d6cdf;color:#fff;box-shadow:0 0 0 2px #2d6cdf1f}.order-icon-action.packing-slip{color:#715bd2!important;border-color:#cfc5f0!important;background:linear-gradient(180deg,#fff,#f3f0ff)!important}.order-icon-action.packing-slip:hover{border-color:#9f8ee4!important;background:#eeeaff!important}';document.head.append(style);
  }
  function applyOrderQuery(value){
    const url=new URL(window.location.href),active=url.searchParams.get('q')===value;
    if(active)url.searchParams.delete('q');else url.searchParams.set('q',value);
    url.searchParams.delete('page');url.searchParams.delete('goToPage');window.location.assign(url);
  }
  function addOrderSearchShortcuts(){
    orderShortcutStyle();document.querySelectorAll('.order-item').forEach(item=>{
      const copy=item.querySelector('.item-product-copy'),sku=copy?.querySelector('code')?.textContent?.trim();
      const amazon=item.querySelector('a[title="Open the Amazon product page"]'),asin=amazon?.getAttribute('aria-label')?.replace(/^Open\s+|\s+on Amazon$/g,'').trim();
      const itemCodes=[...new Set((item.querySelector('.mapping-codes')?.textContent||'').split(/\n+/).map(value=>value.split(/\s*[×x]\s*/)[0].trim()).filter(value=>value&&value!==sku&&value!=='Needs mapping'&&value!=='Choose catalogue item'))];
      if(!copy||copy.querySelector('.order-reference-shortcuts'))return;
      const shortcuts=document.createElement('div');shortcuts.className='order-reference-shortcuts';
      [[asin?[asin]:[],'ASIN'],[itemCodes,'Item code']].forEach(([values,label])=>{if(!values.length)return;const group=document.createElement('fieldset'),legend=document.createElement('legend'),options=document.createElement('div');group.className='order-reference-group';options.className='order-reference-options';legend.textContent=label;group.append(legend,options);values.forEach(value=>{const button=document.createElement('button');button.type='button';button.innerHTML='<span class="reference-value"></span>';button.querySelector('.reference-value').textContent=value;button.title=label+' '+value+' — click to filter; click again to clear';button.setAttribute('aria-label',label+' '+value+' — select to show matching orders; select again to clear');button.setAttribute('aria-pressed',String(new URL(window.location.href).searchParams.get('q')===value));button.classList.toggle('active',new URL(window.location.href).searchParams.get('q')===value);button.addEventListener('click',()=>applyOrderQuery(value));options.append(button);});shortcuts.append(group);});
      if(shortcuts.childElementCount)copy.append(shortcuts);
    });
  }
  function addPackingSlipActions(){
    document.querySelectorAll('.order-icon-action.shipping[data-order-id]').forEach(shipping=>{
      if(shipping.closest('[data-tooltip]'))shipping.removeAttribute('title');
      if(shipping.parentElement?.parentElement?.querySelector('.order-icon-action.packing-slip'))return;
      const orderId=shipping.dataset.orderId,orderRow=shipping.closest('.order-row');
      if(!orderId||!orderRow)return;
      const tip=document.createElement('span');tip.className='order-action-tip';tip.dataset.tooltip='Open packing slip';
      const button=document.createElement('button');button.type='button';button.className='order-icon-action shipping packing-slip';
      button.setAttribute('aria-label','Open packing slip and Seller Central order');
      button.innerHTML='<span class="action-icon" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M9 4H5v17h10M15 4h3v7M9 2h5v4H9zM8 10l1.5 1.5L12 9M8 15h3"/><path d="m15 14 3-1.5 3 1.5v5l-3 1.5-3-1.5zM15 14l3 1.5 3-1.5M18 15.5v5"/></svg></span>';
      button.addEventListener('click',()=>{
        const labelUrl='/app/orders/'+encodeURIComponent(orderId)+'/packing-slip';
        const sellerUrl=orderRow.querySelector('.order-number')?.href;
        const label=window.open('about:blank','_blank');
        if(label){label.opener=null;label.location.href=labelUrl;}
        const seller=sellerUrl?window.open('about:blank','_blank'):null;
        if(seller){seller.opener=null;seller.location.href=sellerUrl;seller.focus();}
        if(!label||!seller){
          let notice=document.querySelector('[data-packing-tabs-notice]');
          if(!notice){notice=document.createElement('div');notice.className='stream-toast show';notice.dataset.packingTabsNotice='';notice.setAttribute('role','status');document.body.append(notice);}
          notice.replaceChildren(document.createTextNode('Your browser blocked a tab. '));
          const addLink=(url,text)=>{const link=document.createElement('a');link.href=url;link.target='_blank';link.rel='noopener noreferrer';link.textContent=text;link.style.cssText='color:inherit;text-decoration:underline;margin-left:10px';notice.append(link);};
          if(!label)addLink(labelUrl,'Open packing slip');if(!seller&&sellerUrl)addLink(sellerUrl,'Open Seller Central');
          const close=document.createElement('button');close.type='button';close.textContent='×';close.setAttribute('aria-label','Dismiss');close.onclick=()=>notice.remove();notice.append(close);
        }
      });
      tip.append(button);shipping.parentElement.parentElement.append(tip);
    });
  }
  addPackingSlipActions();addOrderSearchShortcuts();
  new MutationObserver(()=>queueMicrotask(()=>{addPackingSlipActions();addOrderSearchShortcuts();})).observe(document.body,{childList:true,subtree:true});
  const dialog=document.getElementById('buy-shipping-drawer');
  if(!dialog)return;
  const template=document.getElementById('shipping-package-template');
  const csrf=document.getElementById('buy-shipping-csrf');
  let state=null,pollTimer=null;
  const $=(selector,root=dialog)=>root.querySelector(selector);
  const $$=(selector,root=dialog)=>[...root.querySelectorAll(selector)];
  const headers=(json=false)=>{const value={Accept:'application/json'};if(json)value['Content-Type']='application/json';if(csrf)value[csrf.dataset.header||'X-CSRF-TOKEN']=csrf.value;return value;};
  async function request(url,options={},timeoutMs=25000,timeoutMessage='This is taking longer than expected. Please try again.'){
    const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),timeoutMs);
    try{
      const response=await fetch(url,{cache:'no-store',...options,signal:controller.signal,headers:{...headers(options.body?.startsWith?.('{')),...options.headers}});
      const type=response.headers.get('content-type')||'';const data=type.includes('json')?await response.json():await response.text();
      if(!response.ok)throw new Error(response.status===403?'Operator access is required for Buy Shipping.':(data?.message||(typeof data==='string'&&data.length<240?data:null)||'The request could not be completed.'));
      return data;
    }catch(error){
      if(error.name==='AbortError')throw new Error(timeoutMessage);
      throw error;
    }finally{clearTimeout(timer);}
  }
  function message(text,good=false,action=null){
    const box=$('[data-shipping-message]');box.replaceChildren();box.classList.toggle('good',good);box.classList.toggle('working',action==='working');box.hidden=!text;
    if(!text)return;
    const copy=document.createElement('span');copy.textContent=text;box.append(copy);
    if(action&&action!=='working'){
      const retry=document.createElement('button');retry.type='button';retry.className='secondary-button';retry.textContent=action.label||'Try again';retry.addEventListener('click',action.run);box.append(retry);
    }
  }
  function busy(value,text='Loading order shipping details…',detail='Reading the order, saved packages, and purchased labels.'){
    const box=$('[data-shipping-loading]');box.hidden=!value;if(value){$('strong',box).textContent=text;$('small',box).textContent=detail;}$('[data-shipping-content]').hidden=value;
  }
  function date(value){if(!value)return 'Not supplied by Amazon';return new Intl.DateTimeFormat(undefined,{month:'short',day:'numeric',year:'numeric',hour:'numeric',minute:'2-digit'}).format(new Date(value));}
  function money(amount,currency='USD'){return new Intl.NumberFormat(undefined,{style:'currency',currency:currency||'USD'}).format(Number(amount||0));}
  function sellerCentral(orderId,marketplace){const domain=marketplace==='A1F83G8C2ARO7P'?'sellercentral.amazon.co.uk':'sellercentral.amazon.com';return `https://${domain}/orders-v3/order/${encodeURIComponent(orderId)}`;}

  window.openBuyShipping=async button=>{
    state={orderId:button.dataset.orderId,data:null};message('');busy(true);if(!dialog.open)dialog.showModal();
    $('[data-shipping-order]').textContent=`Order ${state.orderId}`;
    try{
      state.data=await request(`/app/orders/${encodeURIComponent(state.orderId)}/buy-shipping`,{},25000,'Loading this order took too long. No label was purchased; please try again.');
      render();busy(false);$('[data-shipping-order]').textContent=`Order ${state.orderId} · ${state.data.order.status}`;
    }catch(error){busy(false);$('[data-shipping-content]').hidden=true;message(error.message,false,{label:'Try loading again',run:()=>window.openBuyShipping(button)});}
  };
  function render(){
    const data=state.data,order=data.order;
    $('[data-cancel-order]').href=sellerCentral(state.orderId,order.marketplaceId);
    $('[data-shipping-deadlines]').innerHTML=`<article><small>Ship no later than</small><strong>${date(order.latestShip)}</strong></article><article><small>Deliver no later than</small><strong>${date(order.latestDelivery)}</strong></article>`;
    renderMode();renderAddresses();$('[data-packing-slip]').checked=data.settings.printPackingSlip;
    $('[data-package-list]').innerHTML='';addPackage(true);renderHistory(data.shipments||[]);
    const unlabelled=(data.items||[]).reduce((sum,item)=>sum+Math.max(0,item.quantityOrdered-item.quantityShipped-(data.committedQuantities?.[item.orderItemId]||0)),0),rateButton=$('[data-get-rates]');
    rateButton.disabled=unlabelled===0||data.settings.mode==='DISABLED';rateButton.textContent=unlabelled===0?'All unshipped units already have labels':'Compare live rates';
  }
  function renderMode(){
    const mode=state.data.settings.mode,box=$('[data-shipping-mode]'),manager=dialog.dataset.canManage==='true';
    box.classList.toggle('live',mode==='PURCHASE_ENABLED');
    if(mode==='PURCHASE_ENABLED')box.innerHTML=`<span><strong>UAT purchase mode is on.</strong> A final confirmation is required before Amazon is charged.</span>${manager?'<button type="button" class="secondary-button" data-preview-mode>Return to preview</button>':''}`;
    else if(mode==='DISABLED')box.innerHTML='<span><strong>Buy Shipping is disabled.</strong> An administrator can enable rate comparison in store settings.</span>';
    else box.innerHTML=`<span><strong>Safe preview mode.</strong> You can compare real Amazon rates, but labels cannot be purchased.</span>${manager?'<button type="button" class="secondary-button" data-enable-purchase>Enable UAT purchasing</button>':''}`;
    $('[data-enable-purchase]',box)?.addEventListener('click',enablePurchase);
    $('[data-preview-mode]',box)?.addEventListener('click',()=>setShippingMode('RATES_ONLY'));
  }
  async function enablePurchase(){
    if(!confirm('Enable real Buy Shipping purchases for this Amazon store? Amazon will charge the selected postage after the final confirmation.'))return;
    await setShippingMode('PURCHASE_ENABLED');
  }
  async function setShippingMode(mode){
    const body=new URLSearchParams({mode,printPackingSlip:String($('[data-packing-slip]').checked)});
    try{await request(`/app/connections/${encodeURIComponent(dialog.dataset.storeId)}/buy-shipping`,{method:'POST',body,headers:{'Content-Type':'application/x-www-form-urlencoded'}});state.data.settings.mode=mode;renderMode();message(mode==='PURCHASE_ENABLED'?'UAT purchase mode enabled for this store.':'Store returned to safe rate-preview mode.',true);}
    catch(error){message(error.message);}
  }
  function renderAddresses(){
    const select=$('[data-ship-from]'),addresses=state.data.addresses||[];select.innerHTML='';
    if(!addresses.length)select.innerHTML='<option value="">Add a ship-from address below</option>';
    addresses.forEach(address=>{const option=new Option(`${address.isDefault?'Default · ':''}${address.label} · ${address.city}, ${address.state||address.countryCode}`,address.id,address.isDefault,address.isDefault);select.add(option);});
    if(addresses.length)select.add(new Option('＋ Add another ship-from address','__new__'));
    $('[data-address-form]').hidden=addresses.length>0;
    $('[data-address-toggle]').textContent=addresses.length?'Edit selected':'Add address';
  }
  function addPackage(initial=false){
    if(!initial)markRatesStale();
    const fragment=template.content.cloneNode(true),card=fragment.querySelector('.shipping-package');
    const list=$('[data-package-list]');list.append(card);renumberPackages();
    const saveSection=$('.package-save',card),temperature=document.createElement('label');
    temperature.innerHTML='<span>Storage type</span><select data-temperature-class><option value="AMBIENT">Ambient</option><option value="REFRIGERATED">Refrigerated</option><option value="FROZEN">Frozen</option></select>';
    saveSection.insertBefore(temperature,$('.shipping-check',saveSection));
    const profileSelect=$('[data-package-profile]',card);
    for(const profile of state.data.profiles||[])profileSelect.add(new Option(profile.name,profile.id));
    const suggested=initial?state.data.suggestedPackage:null;if(suggested){profileSelect.value=suggested.id;fillProfile(card,suggested);}
    const items=$('[data-package-items]',card);
    for(const item of state.data.items||[]){
      const remaining=Math.max(0,item.quantityOrdered-item.quantityShipped-(state.data.committedQuantities?.[item.orderItemId]||0)),row=document.createElement('div');row.className='package-item';
      row.innerHTML=`<div><strong></strong><code></code></div><label><span>Qty</span><input type="number" min="0" step="1" value="${initial?remaining:0}" data-order-item-id="${item.orderItemId}"></label>`;
      $('strong',row).textContent=item.title||item.sellerSku||'Order item';$('code',row).textContent=item.sellerSku||item.orderItemId;items.append(row);
    }
    profileSelect.addEventListener('change',()=>{const profile=(state.data.profiles||[]).find(value=>value.id===profileSelect.value);if(profile)fillProfile(card,profile);});
    $('[data-remove-package]',card).addEventListener('click',()=>{if($$('.shipping-package').length===1)return message('Keep at least one package.');card.remove();renumberPackages();markRatesStale();});
  }
  function fillProfile(card,profile){
    $('[data-length]',card).value=profile.length;$('[data-width]',card).value=profile.width;$('[data-height]',card).value=profile.height;
    $('[data-dimension-unit]',card).value=profile.dimensionUnit;$('[data-weight]',card).value=profile.weight;$('[data-weight-unit]',card).value=profile.weightUnit;
    $('[data-package-name]',card).value=profile.name||'';$('[data-container-code]',card).value=profile.containerCode||'';$('[data-preferred-carrier]',card).value=profile.preferredCarrier||'';
    $('[data-temperature-class]',card).value=profile.temperatureClass||'AMBIENT';
  }
  function renumberPackages(){$$('.shipping-package').forEach((card,index)=>{$('.package-number',card).textContent=index+1;$('[data-remove-package]',card).hidden=index===0&&$$('.shipping-package').length===1;});}
  function packagePayload(card){
    return {profileId:$('[data-package-profile]',card).value||null,name:$('[data-package-name]',card).value||null,
      containerCode:$('[data-container-code]',card).value||null,length:Number($('[data-length]',card).value),width:Number($('[data-width]',card).value),height:Number($('[data-height]',card).value),
      dimensionUnit:$('[data-dimension-unit]',card).value,weight:Number($('[data-weight]',card).value),weightUnit:$('[data-weight-unit]',card).value,
      preferredCarrier:$('[data-preferred-carrier]',card).value||null,temperatureClass:$('[data-temperature-class]',card).value,saveForItems:$('[data-save-package]',card).checked,
      items:$$('[data-order-item-id]',card).map(input=>({orderItemId:input.dataset.orderItemId,quantity:Number(input.value)})).filter(item=>item.quantity>0)};
  }
  async function getRates(){
    message('Sending the package details to Amazon. Eligible services usually return within a few seconds.',false,'working');const button=$('[data-get-rates]');button.disabled=true;button.setAttribute('aria-busy','true');button.textContent='Checking Amazon rates…';
    try{
      const payload={shipFromAddressId:$('[data-ship-from]').value||null,packingSlip:$('[data-packing-slip]').checked,packages:$$('.shipping-package').map(packagePayload)};
      const result=await request(`/app/orders/${encodeURIComponent(state.orderId)}/buy-shipping/rates`,{method:'POST',body:JSON.stringify(payload)},60000,'Amazon did not return rates within one minute. No label was purchased; check the package details and try again.');
      renderRates(result.packages||[]);message('Live rates received from Amazon.',true);
    }catch(error){message(error.message,false,{label:'Try rates again',run:getRates});}finally{button.disabled=false;button.removeAttribute('aria-busy');button.textContent='Compare live rates';}
  }
  function renderRates(packages){
    const section=$('[data-shipping-results]'),list=$('[data-rate-list]');section.hidden=false;list.innerHTML='';list.className='shipping-rate-list';
    for(const pkg of packages){
      const group=document.createElement('div'),packageCard=$$('.shipping-package')[pkg.packageSequence-1],preferred=packageCard?$('[data-preferred-carrier]',packageCard).value.trim():'';group.className='rate-package';group.innerHTML=`<h4>Package ${pkg.packageSequence}</h4>`;
      if(!(pkg.offers||[]).length)group.insertAdjacentHTML('beforeend','<p class="shipping-empty">Amazon returned no eligible service for this package.</p>');
      const offers=pkg.offers||[];
      offers.forEach((offer,index)=>{const card=rateCard(pkg,offer,preferred);if(index>=2){card.hidden=true;card.dataset.additionalRate='true';}group.append(card);});
      if(offers.length>2){
        const toggle=document.createElement('button');toggle.type='button';toggle.className='shipping-text-button rate-see-all';toggle.textContent=`See all ${offers.length} rates`;
        toggle.addEventListener('click',()=>{const reveal=toggle.dataset.open!=='true';toggle.dataset.open=String(reveal);$$('[data-additional-rate]',group).forEach(card=>card.hidden=!reveal);toggle.textContent=reveal?'Show recommended rates':`See all ${offers.length} rates`;});
        group.append(toggle);
      }
      for(const notice of pkg.notices||[]){const note=document.createElement('small');note.className='rate-notice';note.textContent=notice;group.append(note);}list.append(group);
    }
    section.scrollIntoView({behavior:'smooth',block:'start'});
  }
  function rateCard(pkg,offer,preferred){
    const card=document.createElement('article');card.className=`rate-card${offer.cheapest||offer.fastest?' recommended':''}`;
    const arrival=offer.latestDelivery?`Arrives by ${date(offer.latestDelivery)}`:'Delivery estimate unavailable';
    const pdf=(offer.labelFormats||[]).includes('PDF'),blocked=offer.requiresSellerInput||!pdf;
    card.innerHTML=`<div class="rate-name"><strong></strong><small></small><div class="rate-badges"></div></div><div class="rate-price"><strong>${money(offer.amount,offer.currency)}</strong><small>postage</small></div><button type="button" class="secondary-button" ${blocked?'disabled':''}>${offer.requiresSellerInput?'Needs carrier info':(!pdf?'No PDF label':'Review')}</button>`;
    $('.rate-name strong',card).textContent=`${offer.carrierName} · ${offer.serviceName}`;$('.rate-name small',card).textContent=arrival;
    const badges=$('.rate-badges',card);if(offer.cheapest)badges.insertAdjacentHTML('beforeend','<span class="rate-badge">Cheapest</span>');if(offer.fastest)badges.insertAdjacentHTML('beforeend','<span class="rate-badge fast">Fastest</span>');
    if(preferred&&String(offer.carrierName||'').toLowerCase().includes(preferred.toLowerCase()))badges.insertAdjacentHTML('beforeend','<span class="rate-badge fast">Preferred</span>');
    if(offer.requiresSellerInput)$('.rate-name small',card).textContent='This carrier requires extra information; complete this label in Seller Central.';
    else if(!pdf)$('.rate-name small',card).textContent='This service did not offer the required PDF label format.';
    else $('button',card).addEventListener('click',()=>reviewPurchase(card,pkg,offer));return card;
  }
  function reviewPurchase(card,pkg,offer){
    $$('.rate-confirm').forEach(value=>value.remove());
    const panel=document.createElement('div');panel.className='rate-confirm';
    const enabled=state.data.settings.mode==='PURCHASE_ENABLED';
    panel.innerHTML=`<label><input type="checkbox"><span>${enabled?`I confirm the ${money(offer.amount,offer.currency)} Amazon postage purchase for package ${pkg.packageSequence}.`:'Purchasing is locked while this store is in safe preview mode.'}</span></label><button type="button" class="primary-button" disabled>${enabled?'Purchase label':'Preview only'}</button>`;
    card.append(panel);const check=$('input',panel),button=$('button',panel);check.disabled=!enabled;check.addEventListener('change',()=>button.disabled=!check.checked);button.addEventListener('click',()=>purchase(pkg.shipmentId,offer.id,button));
  }
  async function purchase(shipmentId,offerId,button){
    button.disabled=true;button.textContent='Queued safely…';
    try{
      const body=new URLSearchParams({shipmentId,offerId});await request(`/app/orders/${encodeURIComponent(state.orderId)}/buy-shipping/purchase`,{method:'POST',body,headers:{'Content-Type':'application/x-www-form-urlencoded'}});
      $('[data-shipping-results]').hidden=true;message('Amazon is creating the label. You can keep this drawer open while it finishes.',true);startPolling();
    }catch(error){message(error.message);button.disabled=false;button.textContent='Purchase label';}
  }
  function startPolling(){clearInterval(pollTimer);pollTimer=setInterval(refreshStatus,1500);refreshStatus();}
  async function refreshStatus(){
    try{const shipments=await request(`/app/orders/${encodeURIComponent(state.orderId)}/buy-shipping/status`);renderHistory(shipments);if(!shipments.some(item=>['PURCHASE_QUEUED','PURCHASE_IN_PROGRESS','REFUND_QUEUED','REFUND_PENDING'].includes(item.state)))clearInterval(pollTimer);}
    catch(_){clearInterval(pollTimer);}
  }
  function renderHistory(shipments){
    const section=$('[data-shipping-history]'),list=$('[data-shipment-list]');section.hidden=!shipments.length;list.innerHTML='';
    for(const shipment of shipments){
      const card=document.createElement('article');card.className='shipment-card';const problem=['FAILED','PURCHASE_UNKNOWN','REFUND_REJECTED'].includes(shipment.state);
      card.innerHTML=`<div><strong></strong><small></small><span class="shipment-state${problem?' problem':''}"></span></div><div class="shipment-actions"></div>`;
      $('strong',card).textContent=shipment.serviceName?`${shipment.carrierName||'Carrier'} · ${shipment.serviceName}`:`Package ${shipment.packageSequence}`;
      $('small',card).textContent=[shipment.trackingId?`Tracking ${shipment.trackingId}`:null,shipment.price!=null?money(shipment.price,shipment.currency):null].filter(Boolean).join(' · ');
      $('.shipment-state',card).textContent=shipment.lastError||shipment.state.replaceAll('_',' ').toLowerCase();const actions=$('.shipment-actions',card);
      if(shipment.hasLabel){const link=document.createElement('a');link.className='secondary-button';link.target='_blank';link.textContent=shipment.state==='PURCHASED'?'Print again':'View archived label';link.href=`/app/orders/buy-shipping/shipments/${shipment.id}/label`;actions.append(link);}
      if(shipment.state==='PURCHASED'){
        const refund=document.createElement('button');refund.type='button';refund.className='secondary-button';refund.textContent='Refund label';refund.addEventListener('click',()=>refundLabel(shipment.id,refund));actions.append(refund);
      }
      list.append(card);
    }
  }
  async function refundLabel(id,button){
    if(!confirm('Request an Amazon refund for this unused label? This does not cancel the customer order.'))return;button.disabled=true;
    try{await request(`/app/orders/buy-shipping/shipments/${id}/refund`,{method:'POST',body:new URLSearchParams()});message('Label refund requested. The customer order remains open.',true);startPolling();}
    catch(error){message(error.message);button.disabled=false;}
  }
  $$('[data-shipping-close]').forEach(button=>button.addEventListener('click',()=>dialog.close()));
  function openAddressForm(address){
    const form=$('[data-address-form]');form.hidden=false;form.dataset.addressId=address?.id||'';
    for(const name of ['label','contactName','companyName','line1','line2','city','state','postalCode','countryCode','phone','email'])form.elements[name].value=address?.[name]||(name==='countryCode'?'US':'');
    form.elements.isDefault.checked=address?address.isDefault:true;
  }
  $('[data-address-toggle]').addEventListener('click',()=>{const form=$('[data-address-form]');if(!form.hidden){form.hidden=true;return;}const id=$('[data-ship-from]').value;openAddressForm((state.data.addresses||[]).find(item=>item.id===id)||null);});
  $('[data-ship-from]').addEventListener('change',event=>{if(event.target.value==='__new__')openAddressForm(null);});
  $('[data-address-form]').addEventListener('submit',async event=>{
    event.preventDefault();const form=new FormData(event.currentTarget);const input={id:event.currentTarget.dataset.addressId||null,label:form.get('label'),contactName:form.get('contactName'),companyName:form.get('companyName'),line1:form.get('line1'),line2:form.get('line2'),line3:null,city:form.get('city'),state:form.get('state'),postalCode:form.get('postalCode'),countryCode:form.get('countryCode'),phone:form.get('phone'),email:form.get('email'),isDefault:form.get('isDefault')==='on'};
    try{const saved=await request(`/app/orders/${encodeURIComponent(state.orderId)}/buy-shipping/ship-from`,{method:'POST',body:JSON.stringify(input)});state.data.addresses=(state.data.addresses||[]).filter(item=>item.id!==saved.id);state.data.addresses.unshift(saved);renderAddresses();$('[data-ship-from]').value=saved.id;markRatesStale();message('Ship-from address saved and selected.',true);}
    catch(error){message(error.message);}
  });
  $('[data-add-package]').addEventListener('click',()=>addPackage(false));$('[data-get-rates]').addEventListener('click',getRates);
  function markRatesStale(){const results=$('[data-shipping-results]');if(!results.hidden){results.hidden=true;$('[data-rate-list]').innerHTML='';message('Package details changed. Compare rates again before purchasing.');}}
  function invalidateRates(event){if(event.target.closest('.rate-confirm')||event.target.closest('[data-shipping-results]'))return;markRatesStale();}
  dialog.addEventListener('input',invalidateRates);dialog.addEventListener('change',invalidateRates);
  dialog.addEventListener('click',event=>{if(event.target===dialog&&event.clientX<dialog.getBoundingClientRect().left)dialog.close();});
  dialog.addEventListener('close',()=>{clearInterval(pollTimer);state=null;});
})();
