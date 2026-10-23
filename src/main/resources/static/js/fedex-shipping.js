(()=>{'use strict';
 const dialog=document.getElementById('fedex-shipping');if(!dialog)return;
 const form=document.getElementById('fedex-form'),csrf=document.getElementById('buy-shipping-csrf'),message=dialog.querySelector('[data-fedex-message]'),rates=dialog.querySelector('[data-fedex-rates]'),history=dialog.querySelector('[data-fedex-history]');let order='',generation=0,working=false,packagingOptions=[];
 const node=(tag,text,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;if(cls)n.className=cls;return n;};
 const base=()=>'/app/orders/'+encodeURIComponent(order)+'/buy-shipping/fedex';
 async function api(path,body){const headers={Accept:'application/json'};if(body!==undefined){headers['Content-Type']='application/json';if(csrf)headers[csrf.dataset.header]=csrf.value;}const r=await fetch(base()+path,{method:body===undefined?'GET':'POST',headers,body:body===undefined?undefined:JSON.stringify(body),cache:'no-store'});if(!(r.headers.get('content-type')||'').includes('json'))throw Error('Session expired. Please reload and sign in.');const data=await r.json();if(!r.ok)throw Error(data.message||'Request failed. Refresh shipment history before trying again.');return data;}
 const lock=value=>{working=value;form.querySelectorAll('button,input,select').forEach(n=>n.disabled=value);};
 function applyPackaging(){const selected=packagingOptions.find(o=>o.id===form.elements.packagingId.value);for(const k of ['length','width','height']){form.elements.namedItem(k).readOnly=!!selected?.length;if(selected?.length)form.elements.namedItem(k).value=selected[k];}}
 form.elements.packagingId.onchange=()=>{applyPackaging();rates.replaceChildren();};
 const option=(select,id,label)=>{const o=node('option',label);o.value=id;select.append(o);};
 function renderHistory(shipments){history.replaceChildren(node('h3','Shipment history'));for(const s of shipments){const row=node('div',s.state+(s.tracking?' · '+s.tracking:''),'fedex-history-row');if(s.error)row.append(node('span',s.error));if(s.state==='PURCHASING'||s.state==='UNKNOWN')row.append(node('span','Do not generate another label; this shipment needs review.'));if(s.state==='PURCHASED'){const a=node('a','Open label','secondary-button');a.href=base()+'/labels/'+encodeURIComponent(s.id) ;a.target='_blank';a.rel='noopener';row.append(a);}history.append(row);}}
 async function load(){const data=await api('');renderHistory(data.shipments);return data;}
 function renderRecipient(view){
  const recipient=view?.recipient,status=dialog.querySelector('[data-recipient-status]');
  const suppressed=view?.status==='INCOMPLETE'||view?.status==='DENIED';
  // Keep test input ephemeral and separate from every imported address, even partial ones.
  for(const k of ['name','phone','street','street2','street3','city','state','postalCode','country'])form.elements.namedItem(k).value=suppressed?'':recipient?.[k]||'';
  form.elements.residential.checked=!suppressed&&recipient?.addressType==='RESIDENTIAL';
  form.elements.testRecipient.checked=suppressed;
  status.classList.toggle('fedex-recipient-warning',suppressed);
  status.textContent=suppressed?'Restricted customer PII suppressed by Amazon. Please enter shipping recipient details manually for sandbox testing.':recipient?'Amazon recipient loaded. Review all fields; sandbox testing requires a separate test address.':'Amazon recipient: '+(view?.status||'unavailable')+'. Enter a sandbox test recipient to continue.';
 }
 window.openFedExShipping=async button=>{if(working)return;order=button.dataset.orderId;const current=++generation;form.reset();rates.replaceChildren();history.replaceChildren();message.textContent='Loading saved package and connections…';dialog.querySelector('[data-fedex-order]').textContent='Order '+order;dialog.showModal();lock(true);try{const data=await api('');if(current!==generation)return;for(const name of ['carrierId','senderId'])form.elements.namedItem(name).replaceChildren();option(form.elements.carrierId,'','Choose sandbox connection');for(const c of data.carriers.filter(c=>c.environment==='SANDBOX'))option(form.elements.carrierId,c.id,c.name+' · Sandbox');option(form.elements.senderId,'','Choose sender');for(const a of data.senders)option(form.elements.senderId,a.id,a.label+' · '+a.postalCode);const carriers=data.carriers.filter(c=>c.environment==='SANDBOX');if(carriers.length===1)form.elements.carrierId.value=carriers[0].id;const sender=data.senders.find(a=>a.isDefault)|| (data.senders.length===1?data.senders[0]:null);if(sender)form.elements.senderId.value=sender.id;packagingOptions=data.packagingOptions||[];form.elements.packagingId.replaceChildren();option(form.elements.packagingId,'','Choose packaging');for(const p of packagingOptions)option(form.elements.packagingId,p.id,p.label+(p.length?' · '+p.length+' × '+p.width+' × '+p.height+' in':''));form.elements.packagingId.value=data.dimensions?.packagingId||(data.dimensions?'YOUR_PACKAGING':'');dialog.querySelector('[data-packaging-notice]').textContent=data.packagingNotice||'';applyPackaging();for(const name of ['length','width','height','weight'])if(data.dimensions?.[name]!=null)form.elements.namedItem(name).value=data.dimensions[name];renderHistory(data.shipments);renderRecipient(data.recipient);message.textContent=data.carriers.some(c=>c.environment==='SANDBOX')?'Enter a test recipient and review package details.':'Add a FedEx sandbox connection under Carrier Integrations first.';}catch(e){message.textContent=e.message;}finally{lock(false);for(const n of ['carrierId','senderId','packagingId'])form.elements.namedItem(n).dispatchEvent(new Event('platform-choice-sync'));}};
 form.onsubmit=async e=>{e.preventDefault();if(working)return;const v=Object.fromEntries(new FormData(form)),parcel=Object.fromEntries(['length','width','height','weight'].map(k=>[k,Number(v[k])])),recipient={country:v.country,residential:form.elements.residential.checked};for(const k of ['name','phone','street','street2','street3','city','state','postalCode'])recipient[k]=v[k];parcel.packagingId=v.packagingId;const input={carrierId:v.carrierId,senderId:v.senderId,parcel,recipient,saveDimensions:form.elements.saveDimensions.checked,testRecipient:form.elements.testRecipient.checked};lock(true);rates.replaceChildren();message.textContent='Requesting FedEx account rates…';try{const quote=await api('/rates',input);for(const rate of quote.rates){const row=node('div',null,'fedex-rate');row.append(node('strong',rate.serviceName),node('span',new Intl.NumberFormat(undefined,{style:'currency',currency:rate.currency}).format(rate.amount)));const buy=node('button','Generate & open label','primary-button');buy.type='button';buy.onclick=()=>purchase(quote.shipmentId,rate);row.append(buy);rates.append(row);}message.textContent='Estimated account rates · review before generating a test label.';}catch(error){message.textContent=error.message;}finally{lock(false);}};
 async function purchase(quoteId,rate){
  if(working)return;
  // Open during the click gesture so asynchronous carrier responses do not trigger popup blocking.
  const preview=window.open('about:blank','_blank');if(preview)preview.opener=null;
  lock(true);message.textContent='Generating sandbox label. Please wait; do not submit again.';
  try{const shipment=await api('/purchase',{quoteId,serviceType:rate.serviceType,confirmed:true});
   rates.replaceChildren();message.textContent='Sandbox label saved. Print from the PDF viewer; Amazon was not updated.';
   const labelUrl=base()+'/labels/'+encodeURIComponent(shipment.id);
   if(preview&&!preview.closed)preview.location.replace(labelUrl);
   else {const link=node('a','Open label to print','primary-button');link.href=labelUrl;link.target='_blank';link.rel='noopener';rates.append(link);}
   await load();
  }catch(error){if(preview&&!preview.closed&&preview.location.href==='about:blank')preview.close();message.textContent=error.message;rates.replaceChildren();try{await load();}catch(_){}}
  finally{lock(false);}
 }
 let rateTimer;
 const scheduleRates=()=>{clearTimeout(rateTimer);rates.replaceChildren();rateTimer=setTimeout(()=>{
  // Never submit imported customer PII automatically to a sandbox, or request incomplete packages.
  if(dialog.open&&!working&&form.elements.testRecipient.checked&&form.matches(':valid'))form.requestSubmit();
 },800);};
 form.addEventListener('input',scheduleRates);
 form.addEventListener('change',scheduleRates);
 const savePackage=node('button','Save package','secondary-button');savePackage.type='button';dialog.querySelector('.dialog-actions').prepend(savePackage);
 savePackage.onclick=async()=>{if(working)return;const parcel=Object.fromEntries(['length','width','height','weight'].map(k=>[k,Number(form.elements.namedItem(k).value)]));parcel.packagingId=form.elements.packagingId.value;lock(true);try{await api('/package?reuse='+form.elements.saveDimensions.checked,parcel);message.textContent='Package saved for this order. Compare rates whenever you are ready.';}catch(e){message.textContent=e.message;}finally{lock(false);}};
 form.elements.testRecipient.onchange=()=>{for(const k of ['name','phone','street','street2','street3','city','state','postalCode','country'])form.elements.namedItem(k).value='';form.elements.residential.checked=false;rates.replaceChildren();};
 dialog.addEventListener('cancel',e=>{if(working)e.preventDefault();});
 dialog.addEventListener('close',()=>{clearTimeout(rateTimer);++generation;form.reset();});
 dialog.querySelectorAll('[data-fedex-close]').forEach(b=>b.onclick=()=>{if(!working)dialog.close();});
})();
