(()=>{
'use strict';
const state=window.receivingWork;if(!state)return;
const drawer=document.getElementById('receiving-drawer'),body=document.getElementById('rw-drawer-body');
const canEdit=document.querySelector('.receive-work').dataset.canEdit==='true';
const escape=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const number=value=>Number(value||0).toLocaleString(undefined,{maximumFractionDigits:4});
const doc=id=>state.documents.find(d=>d.id===id),line=id=>state.lines.find(l=>l.id===id);
const label=d=>(d.type==='INVOICE'?'Invoice':'Packing list')+' · '+(d.number||d.filename);
const progress=d=>d.closed?(d.partial?'Closed · Partial':'Closed · Received'):Number(d.outstanding)===0?(Number(d.received)<Number(d.expected)?'Resolved · exceptions':'Fully received'):Number(d.received)>0||Number(d.outstanding)<Number(d.expected)?'Partially received':'Not received';
const detail=id=>(state.details||[]).find(d=>d.id===id)||{};
const conditionNames={SELLABLE:'Received',SOON_EXPIRED:'Short shelf life',EXPIRED:'Expired',SHORT_SHIPPED:'Short shipped',DAMAGED:'Damaged',MISPICKED:'Mispick',OVER_SHIPPED:'Overage'};
const date=value=>value?value.split('-').slice(1).concat(value.slice(2,4)).join('/'):'No expiration';
const shelf=value=>{if(!value||!state.policy)return 'neutral';const days=Math.round((new Date(value+'T00:00:00')-new Date(new Date().toDateString()))/86400000);return days<=Number(state.policy.minimumSellableDays)?'danger':days<=Number(state.policy.warningDays)?'warning':'success'};
function renderLine(row,l){
  const d=detail(l.id);let batches=[];try{batches=JSON.parse(d.batches||'[]')}catch(_){}
  const quantities={};batches.forEach(b=>quantities[b.condition]=(quantities[b.condition]||0)+Number(b.quantity));
  row.querySelector('[data-column="received"]').textContent=(Number(l.remaining)===0?'✓ ':'◔ ')+number(l.received)+' each';
  const dates=[...new Set(batches.filter(b=>['SELLABLE','SOON_EXPIRED','EXPIRED','OVER_SHIPPED'].includes(b.condition)).map(b=>b.expiration))];
  row.querySelector('[data-column="expiration"]').innerHTML=dates.length?dates.map(v=>'<span class="rw-status-badge '+shelf(v)+'">'+escape(date(v))+'</span>').join(' '):'—';
  const conditions=[...new Set(batches.map(b=>b.condition))];if(Number(d.shortage)>0&&!conditions.includes('SHORT_SHIPPED'))conditions.push('SHORT_SHIPPED');
  const tone=conditions.some(c=>['EXPIRED','DAMAGED','MISPICKED'].includes(c))||dates.some(v=>shelf(v)==='danger')?'danger':conditions.some(c=>['SHORT_SHIPPED','SOON_EXPIRED'].includes(c))||dates.some(v=>shelf(v)==='warning')?'warning':Number(l.received)>0?'success':'open';
  row.dataset.receivingStatus=l.closed?'closed':tone;
  row.querySelector('[data-column="condition"]').innerHTML=(conditions.length?conditions.map(c=>'<span class="rw-status-badge '+(['EXPIRED','DAMAGED','MISPICKED'].includes(c)?'danger':['SHORT_SHIPPED','SOON_EXPIRED'].includes(c)?'warning':'success')+'">'+escape(conditionNames[c]||c)+'</span>').join(' '):'<span class="rw-status-badge open">Not received</span>')+(l.closed?'<small>Closed · Read only</small>':'');
  row.querySelector('[data-column="remaining"]').innerHTML=(l.closed?'Closed':number(l.remaining)+' each')+(Number(d.shortage)>0?'<small>'+number(d.shortage)+' short shipped</small>':'');
  row.querySelector('[data-column="remaining"]').classList.toggle('rw-remaining-complete',!l.closed&&Number(l.remaining)===0);
  const receivedCell=row.querySelector('[data-column="received"]');
  receivedCell.dataset.receiptProgress=conditions.some(c=>['DAMAGED','MISPICKED','SHORT_SHIPPED'].includes(c))||Number(d.shortage)>0?'invalid':conditions.includes('OVER_SHIPPED')?'partial':Number(l.remaining)===0?'complete':Number(l.received)>0?'partial':'open';
  receivedCell.title=Number(l.remaining)===0?'Complete · all expected units accounted for':'In progress';
  row.querySelector('[data-column="remaining"]').removeAttribute('data-receipt-progress');
  delete row.dataset.receiptPreview;
}
const time=value=>value?new Date(value).toLocaleString('en-US',{month:'2-digit',day:'2-digit',year:'2-digit',hour:'numeric',minute:'2-digit'}):'—';
const money=(amount,currency)=>new Intl.NumberFormat('en-US',{style:'currency',currency:currency||'USD'}).format(Number(amount||0));
const message=(text,error=false,panel=false)=>{const el=document.getElementById(panel?'rw-panel-feedback':'rw-feedback');el.textContent=text;el.classList.toggle('error',error);el.hidden=false;};
let viewSequence=0,busy=false,returnToReceipt=null,receiptDirty=false;
const receiptDrafts=new Map();
function open(title,subtitle,context){
  returnToReceipt=null;receiptDirty=false;
  drawer.querySelectorAll('[data-rw-close]').forEach(b=>{if(b.textContent.includes('Back to'))b.textContent='Back to checklist';});
  viewSequence++;document.getElementById('rw-drawer-title').textContent=title;
  document.getElementById('rw-drawer-subtitle').textContent=subtitle||'';
  document.getElementById('rw-drawer-subtitle').className='';
  document.getElementById('rw-drawer-context').textContent=context||'Receiving';
  document.getElementById('rw-panel-feedback').hidden=true;body.replaceChildren();body.scrollTop=0;
  if(!drawer.open)drawer.showModal();
}
function confirmReceiving(title,description,action){
  const modal=document.getElementById('rw-warning-dialog');
  if(modal.open)return Promise.resolve(false);
  const previous=document.activeElement;
  document.getElementById('rw-warning-title').textContent=title;
  document.getElementById('rw-warning-description').textContent=description;
  modal.querySelector('[data-warning-confirm]').textContent=action;
  return new Promise(resolve=>{
    let accepted=false;
    modal.querySelectorAll('[data-warning-cancel]').forEach(b=>b.onclick=()=>modal.close());
    modal.querySelector('[data-warning-confirm]').onclick=()=>{accepted=true;modal.close();};
    modal.addEventListener('close',()=>{if(previous?.isConnected)previous.focus();resolve(accepted);},{once:true});
    modal.showModal();modal.querySelector('[data-warning-cancel][autofocus]').focus();
  });
}
async function closeReceipt(){if(busy)return;if(returnToReceipt){returnToReceipt();return;}if(receiptDirty&&!await confirmReceiving('Close without saving?','Your receipt changes have not been saved. No inventory will change. Your draft will remain available while this page stays open.','Close without saving'))return;drawer.close();}
document.querySelectorAll('[data-rw-close]').forEach(button=>button.onclick=closeReceipt);
drawer.addEventListener('cancel',event=>{event.preventDefault();closeReceipt();});
drawer.addEventListener('close',()=>document.querySelectorAll('[data-receipt-preview]').forEach(row=>{const l=line(row.dataset.line);if(l)renderLine(row,l);}));
window.addEventListener('beforeunload',event=>{if(receiptDirty){event.preventDefault();event.returnValue='';}});
function button(text,action,cls='secondary-button compact-button'){
  const el=document.createElement('button');el.type='button';el.className=cls;el.textContent=text;el.onclick=action;return el;
}
function form(markup,submit,requestId=crypto.randomUUID()){
  const el=document.createElement('form');el.className='rw-form';el.innerHTML=markup;
  el.dataset.requestId=requestId;
  el.addEventListener('submit',event=>{event.preventDefault();if(!busy&&el.reportValidity())submit(el,requestId);});
  return el;
}
function reason(){return '<label>Reason<textarea name="reason" maxlength="500" required placeholder="Explain this correction for the audit history"></textarea></label>';}
function note(title,copy,warning=false){return '<div class="rw-note'+(warning?' warning':'')+'"><strong>'+escape(title)+'</strong>'+escape(copy)+'</div>';}
function rowActions(l){
  const actions=document.createElement('div');actions.className='rw-row-actions';
  if(canEdit&&!l.closed&&Number(l.remaining)>0)actions.append(button(l.productId?'Receive':'Add to catalogue',()=>receive(l.id),'primary-button compact-button'));
  if(l.receipts>0||Number(detail(l.id).shortage)>0)actions.append(button(l.closed?'View history':'Receipts / Undo',()=>review(l.id)));
  if(l.closed&&!l.receipts){const text=document.createElement('span');text.className='rw-locked';text.textContent='No receipts';actions.append(text);}
  return actions;
}
async function refresh(){
  const params=new URLSearchParams();state.documents.forEach(d=>params.append('documents',d.id));
  const response=await fetch('/app/receiving/work/state?'+params,{cache:'no-store',headers:{Accept:'application/json'}});
  if(!response.ok)throw new Error('Saved, but the checklist could not be refreshed. Reload this view before making another change.');
  const updated=await response.json();state.documents=updated.documents;state.lines=updated.lines;state.details=updated.details;
  document.querySelectorAll('[data-line]').forEach(row=>{
    const l=line(row.dataset.line);if(!l){row.remove();return;}
    row.querySelector('[data-column="expected"]').textContent=number(l.expected)+' each';
    row.querySelector('[data-column="received"]').textContent=number(l.received)+' each';
    row.querySelector('[data-column="remaining"]').textContent=l.closed?'Closed':number(l.remaining)+' each';
    const actionCell=row.querySelector('[data-column="actions"]'),chat=actionCell.querySelector('.collaboration-row-button');
    actionCell.replaceChildren(rowActions(l));if(chat)actionCell.prepend(chat);
    renderLine(row,l);
  });
  document.querySelectorAll('.rw-document').forEach(card=>{
    const d=doc(card.dataset.document);if(!d){card.remove();return;}
    card.querySelector('.rw-progress').textContent=progress(d);
    card.dataset.receivingStatus=d.closed?'closed':Number(d.outstanding)===0?'complete':Number(d.received)>0||Number(d.outstanding)<Number(d.expected)?'partial':'open';
  });
  document.querySelector('.rw-table').dispatchEvent(new CustomEvent('table:filter',{bubbles:true}));
}
async function run(operation,target,data,el,after){
  const buttons=[...drawer.querySelectorAll('button')];buttons.forEach(b=>b.disabled=true);busy=true;
  let saved=false;
  try{
    const token=document.getElementById('receiving-csrf'),headers={'Content-Type':'application/json',Accept:'application/json'};
    if(token)headers[token.dataset.header]=token.value;
    const response=await fetch('/app/receiving/work/'+operation+'/'+encodeURIComponent(target),{method:'POST',headers,body:JSON.stringify({creditExpected:false,...data})});
    let result;try{result=await response.json();}catch(_){throw new Error('The server response could not be confirmed. Retry this same action safely; do not create a second receipt.');}
    if(!response.ok)throw new Error(result.message||'Nothing was saved. Please try again.');
    saved=true;if(['outcomes','batches','overage'].includes(operation)){receiptDrafts.delete(target);receiptDirty=false;}await refresh();busy=false;
    if(after)await after();if(!['batches','overage'].includes(operation)){message(result.message,false,true);message(result.message);}else document.getElementById('rw-feedback').hidden=true;
  }catch(error){
    message(error.message,true,true);
    if(saved){el.querySelectorAll('input,select,textarea,button').forEach(field=>field.disabled=true);el.dataset.saved='true';}
  }finally{
    busy=false;buttons.filter(b=>b.isConnected&&!b.closest('[data-saved]')).forEach(b=>b.disabled=false);
  }
}
function receive(id,overage=false){
  const l=line(id);if(!l||l.closed||!canEdit)return;
  if(receiptDrafts.has(id)&&Boolean(receiptDrafts.get(id).overage)!==overage){message('Finish or close the existing receipt draft before switching receiving mode.');return;}
  if(!l.productId)return addCatalogue(id);
  if(overage&&Number(l.remaining)>0){open('Receive overshipped items',l.product,label(doc(l.documentId)));body.innerHTML=note('Account for expected units first','Receive or resolve the remaining '+number(l.remaining)+' expected units before recording a separate zero-cost overage. Normal receiving also separates confirmed extra units at zero cost.');body.append(button('Receive expected units',()=>receive(id),'primary-button'));return;}
  const d=doc(l.documentId);open(overage?'Receive overshipped items':'Receive stock',l.product,label(d));
  const identity=document.getElementById('rw-drawer-subtitle');identity.className='rw-product-identity';
  identity.innerHTML='<span class="rw-product-picture table-overview-picture" data-standard-picture-url="/app/catalog/products/'+escape(l.productId)+'/image"><b>'+escape((l.product||'?').slice(0,1))+'</b><button type="button" class="standard-picture-edit" aria-label="Edit product picture" title="Edit product picture"><svg viewBox="0 0 24 24"><path d="m5 16-1 4 4-1L20 7l-3-3Z M14 7l3 3"/></svg></button></span><span><small>'+escape(l.code||'')+'</small>'+escape(l.product)+'</span>';
  const picture=identity.querySelector('.rw-product-picture'),edit=picture.querySelector('button');edit.onclick=()=>window.NextAiStandardTables?.editPicture(edit,picture,picture.dataset.standardPictureUrl);
  const sequence=viewSequence;
  fetch('/app/receiving/work/lines/'+id+'/identity').then(r=>r.ok?r.json():{}).then(result=>{
    if(sequence!==viewSequence||!result.imageUrl)return;
    const image=document.createElement('img');image.src=result.imageUrl;image.alt='';image.onerror=()=>image.remove();image.onload=()=>picture.querySelector('b').hidden=true;picture.prepend(image);
  }).catch(()=>{});
  const facts=detail(id),pack=Math.max(1,Number(facts.catalogPack||l.unitsPerCase||1));
  body.innerHTML=(overage?note('Zero-cost extra stock','Only extra units are recorded here. The original invoice cost and expected quantity stay unchanged.'):'')+'<div class="rw-receive-summary" aria-live="polite"><span data-summary-remaining><strong>'+number(l.remaining)+'</strong> each remaining</span><span data-summary-received>'+number(l.received)+' received</span><span>'+escape(money(overage?0:l.unitCost,l.currency))+' / each</span></div>';
  const summary=body.querySelector('.rw-receive-summary');
  const locations=state.locations.filter(v=>v.status==='ACTIVE').map(v=>'<option value="'+escape(v.id)+'"'+(v.id===l.locationId?' selected':'')+'>'+escape(v.code+' · '+v.name)+'</option>').join('');
  const exceptions=[['damaged','Damaged'],['shortShipped','Short shipped'],['wrongItem','Mispick']];
  const initial=Number(l.remaining);let savedPack=Number(facts.catalogPack||pack);
  const f=form('<fieldset class="rw-exception-fields rw-sellable"><legend>Sellable</legend><div class="rw-quantity-grid"><label>Cases<input name="cases" type="number" min="0" step="1" value="'+Math.floor(initial/pack)+'"></label><label>Units / case<input name="pack" type="number" min="1" max="100000" step="1" required value="'+pack+'"></label><label>Total units<input name="quantity" type="number" min="0" step="1" required value="'+initial+'"></label></div><small class="rw-muted" data-remainder="quantity"></small></fieldset>'+
    '<div><button class="rw-text-action" type="button" data-update-pack disabled>Update catalogue case size</button><small class="rw-muted" data-pack-feedback role="status">Explicit update only · shared catalogue product, including other accounts. Invoice quantities stay unchanged.</small></div>'+
    '<div class="rw-pair"><label>Expiration date<input name="expiration" type="date"></label><div class="rw-location-field"><label>Location<select name="locationId" required><option value="">Choose a location</option>'+locations+'</select></label>'+(document.querySelector('.receive-work').dataset.canAddLocation==='true'?'<button type="button" class="rw-add-location secondary-button" data-add-location aria-label="Add location" title="Add location">+</button>':'')+'</div></div>'+
    '<label class="rw-check"><input name="requiresExpiration" type="checkbox"'+(l.requiresExpiration?' checked':'')+'><span>Require expiration date when receiving this item<small>Saved to catalogue for future receipts.</small></span></label>'+
    '<p class="rw-status-badge" data-expiration-status role="status">No expiration date entered</p>'+
    '<div class="rw-exception-actions" aria-label="Receiving exceptions">'+exceptions.map(([key,title])=>'<button type="button" class="secondary-button compact-button" data-exception="'+key+'" aria-expanded="false" aria-controls="rw-'+key+'">'+title+'</button>').join('')+'</div>'+
    exceptions.map(([key,title])=>'<fieldset id="rw-'+key+'" data-exception-fields="'+key+'" class="rw-exception-fields" hidden disabled><legend>'+title+'</legend><div class="rw-quantity-grid"><label>Cases<input name="'+key+'Cases" type="number" min="0" step="1" value="0"></label><label>Units / case<input data-exception-pack readonly value="'+pack+'" aria-label="'+title+' units per case"></label><label>Total units<input name="'+key+'" type="number" min="1" step="1" required value="0"></label></div><small class="rw-muted" data-remainder="'+key+'"></small><small class="rw-muted">'+(key==='shortShipped'?'Expected units that did not arrive.':'Separate from Sellable; not added to inventory.')+'</small></fieldset>').join('')+
    '<p class="rw-quantity-preview" data-preview role="status"></p><button class="primary-button compact-button rw-primary-action" type="submit">Save receipt</button>',
    async (f,requestId)=>{
      const batches=[{quantity:Number(f.elements.quantity.value),expiration:f.elements.expiration.value||null},...extraBatches.map(row=>({quantity:Number(row.querySelector('[data-batch-quantity]').value),expiration:row.querySelector('[data-batch-date]').value||null}))];
      const extra=Math.max(0,batches.reduce((sum,b)=>sum+b.quantity,0)+amount('damaged')+amount('wrongItem')-Number(l.remaining));
      if(extra&&!await confirmReceiving('Receive extra units?',number(extra)+' units exceed the remaining expected quantity. These extra units will be added to inventory as overage with zero item acquisition cost.','Receive with overage'))return;
      run(overage?'overage':'batches',id,{requestId,batches,confirmedOverage:extra,expirationRequired:f.elements.requiresExpiration.checked,location:f.elements.locationId.value,...Object.fromEntries(exceptions.map(([key])=>[key,amount(key)]))},f,()=>{receiptDrafts.delete(id);drawer.close();requestAnimationFrame(()=>document.querySelector('#rw-lines-card .table-standard-search input')?.focus());});
    },receiptDrafts.get(id)?.requestId);
  // Each entry owns its case size. Only the calculated total is read-only.
  f.elements.quantity.readOnly=true;
  if(initial%pack){f.elements.cases.value='1';f.elements.pack.value=initial;}
  function editablePack(input,name){input.readOnly=false;input.type='number';input.name=name;input.min='1';input.max='100000';input.step='1';input.required=true;}
  exceptions.forEach(([key])=>{f.elements[key].readOnly=true;editablePack(f.elements[key].closest('.rw-quantity-grid').querySelector('[data-exception-pack]'),key+'Pack');});
  const extraBatches=[];let batchSequence=0;
  const sellable=f.querySelector('.rw-sellable');
  // Tab names already label their panels; avoid repeating visible headings.
  f.querySelectorAll('fieldset>legend').forEach(legend=>legend.remove());
  sellable.append(f.elements.expiration.closest('label'),f.querySelector('[data-expiration-status]'));
  const batchList=document.createElement('div');batchList.className='rw-expiration-batches';sellable.append(batchList);
  const addBatch=button('+ Add quantity / expiration date',()=>{insertBatch();receiptDirty=true;preview();remember();},'secondary-button compact-button rw-add-batch');sellable.append(addBatch);
  f.insertBefore(f.querySelector('[data-update-pack]').parentElement,f.querySelector('[data-preview]'));
  sellable.append(f.elements.locationId.closest('.rw-pair'),f.elements.requiresExpiration.closest('label'));
  const packButton=f.querySelector('[data-update-pack]');packButton.dataset.value=f.elements.pack.value;
  f.addEventListener('input',event=>{if(event.target===f.elements.pack||event.target.matches('[data-exception-pack]'))packButton.dataset.value=event.target.value;});
  function insertBatch(values={}){
    const key=++batchSequence,row=document.createElement('div');row.className='rw-expiration-batch';
    row.innerHTML='<div class="rw-quantity-grid"><label>Cases<input data-batch-cases type="number" min="0" step="1" value="0"></label><label>Units / case<input data-exception-pack readonly value="'+escape(f.elements.pack.value)+'"></label><label>Total units<input name="batchQuantity'+key+'" data-batch-quantity type="number" min="0" step="1" required value="0"></label></div><small data-batch-remainder></small><label>Expiration date<input name="batchDate'+key+'" data-batch-date type="date"></label>';
    row.querySelector('[data-batch-quantity]').value=values.quantity??0;row.querySelector('[data-batch-date]').value=values.expiration||'';
    const total=row.querySelector('[data-batch-quantity]'),cases=row.querySelector('[data-batch-cases]');total.readOnly=true;cases.value=values.cases??Math.floor(Number(values.quantity||0)/Number(f.elements.pack.value));
    const size=row.querySelector('[data-exception-pack]');editablePack(size,'batchPack'+key);size.value=values.pack??f.elements.pack.value;
    const sync=()=>{total.value=Number(cases.value)*Number(size.value);row.querySelector('[data-batch-remainder]').textContent=number(cases.value)+' cases × '+number(size.value)+' units';};
    row.sync=sync;
    const remove=button('−',async()=>{if(Number(row.querySelector('[data-batch-quantity]').value)>0&&!await confirmReceiving('Remove this expiration entry?','This unsaved quantity and expiration date will be removed. Other entries will stay unchanged.','Remove entry'))return;extraBatches.splice(extraBatches.indexOf(row),1);row.remove();receiptDirty=true;preview();remember();},'rw-remove-batch');remove.setAttribute('aria-label','Remove expiration date');remove.title='Remove expiration date';row.addEventListener('input',sync);row.append(remove);
    extraBatches.push(row);batchList.append(row);sync();row.querySelector('[data-batch-date]').focus();
  }
  const amount=key=>f.elements[key].matches(':disabled')?0:Number(f.elements[key].value);
  const preview=()=>{
    const physical=Number(f.elements.quantity.value)+extraBatches.reduce((sum,row)=>sum+Number(row.querySelector('[data-batch-quantity]').value),0),damaged=amount('damaged'),wrong=amount('wrongItem'),missing=amount('shortShipped'),delivered=physical+damaged+wrong;
    f.elements.expiration.required=f.elements.requiresExpiration.checked&&Number(f.elements.quantity.value)>0;
    f.elements.locationId.required=physical>0;
    extraBatches.forEach(row=>{row.querySelector('[data-batch-date]').required=f.elements.requiresExpiration.checked&&Number(row.querySelector('[data-batch-quantity]').value)>0;row.sync();});
    const error=missing>0&&delivered+missing>Number(l.remaining)?'Sellable + Damaged + Short shipped + Mispick exceeds the remaining expected quantity. A receipt cannot be both overage and short shipped.':damaged+wrong>Number(l.remaining)?'Damaged and Mispick exceed the remaining expected balance.':delivered+missing<=0?'Enter a quantity in at least one panel.':'';
    f.elements.quantity.setCustomValidity(error);
    const extra=Math.max(0,delivered-Number(l.remaining)),remaining=Math.max(0,Number(l.remaining)-delivered-missing);
    f.querySelector('[data-preview]').textContent=error||[number(Math.min(Number(l.remaining),delivered+missing))+' of '+number(l.remaining)+' expected accounted for',number(physical)+' accepted physical units',damaged?number(damaged)+' damaged':null,wrong?number(wrong)+' mispick':null,missing?number(missing)+' short shipped':null,number(remaining)+' remain open'].filter(Boolean).join(' · ')+'.'+(extra?' '+number(extra)+' extra accepted units · '+money(0,l.currency)+' item cost.':'');
    const proposedPack=Number(packButton.dataset.value);packButton.disabled=busy||proposedPack===savedPack||!Number.isInteger(proposedPack)||proposedPack<1||proposedPack>100000;
    f.querySelector('[data-preview]').dataset.tone=error?'danger':'neutral';
    const quantities=[physical,damaged,wrong,missing],valid=!error&&quantities.every(q=>Number.isFinite(q)&&q>=0&&Number.isInteger(q));
    f.querySelector('[type=submit]').disabled=busy||!valid;
    const tone=!valid||damaged+wrong+missing>0?'invalid':extra>0?'partial':remaining===0?'complete':'partial';
    delete summary.dataset.receiptProgress;
    summary.querySelector('[data-summary-remaining]').textContent=number(remaining)+' each remaining';
    const totalEntered=summary.querySelector('[data-summary-received]');totalEntered.textContent=number(delivered+missing)+' entered';totalEntered.dataset.receiptProgress=tone;totalEntered.title='Unsaved total across all active tabs, including short shipped';
    const row=[...document.querySelectorAll('[data-line]')].find(row=>row.dataset.line===id);
    if(row){
      row.dataset.receiptPreview='true';
      const received=row.querySelector('[data-column="received"]'),left=row.querySelector('[data-column="remaining"]');
      received.dataset.receiptProgress=tone;received.textContent=(valid&&remaining===0?'✓ ':'◔ ')+number(Number(l.received)+delivered)+' each';received.title=valid?'Unsaved preview · save receipt to apply':'Check quantities · unsaved preview';
      left.dataset.receiptProgress=tone;left.classList.remove('rw-remaining-complete');left.textContent=number(remaining)+' each';
      const shortages=Number(facts.shortage||0)+missing;if(shortages){const text=document.createElement('small');text.textContent=number(shortages)+' short shipped';left.append(text);}
    }
  };
  f.elements.cases.addEventListener('input',()=>{f.elements.quantity.value=String(Number((Number(f.elements.cases.value)*Number(f.elements.pack.value)).toFixed(4)))});
  const syncCases=(key,cases)=>{const count=Number(f.elements[cases].value),size=Number(f.elements[key==='quantity'?'pack':key+'Pack'].value);f.elements[key].value=String(count*size);f.querySelector('[data-remainder="'+key+'"]').textContent=number(count)+' cases × '+number(size)+' units';};
  f.elements.pack.addEventListener('input',()=>{syncCases('quantity','cases');exceptions.forEach(([key])=>syncCases(key,key+'Cases'));});
  f.elements.quantity.addEventListener('input',()=>syncCases('quantity','cases'));
  exceptions.forEach(([key])=>{
    f.elements[key].min='0';
    f.elements[key+'Cases'].addEventListener('input',()=>{f.elements[key].value=String(Number((Number(f.elements[key+'Cases'].value)*Number(f.elements.pack.value)).toFixed(4)))});
    f.elements[key].addEventListener('input',()=>syncCases(key,key+'Cases'));
  });
  let statusRequest=0;
  f.elements.expiration.addEventListener('input',async()=>{
    const request=++statusRequest,el=f.querySelector('[data-expiration-status]');el.className='rw-status-badge';el.textContent='Checking shelf-life rules…';
    try{const r=await fetch('/app/receiving/work/expiration-status?'+new URLSearchParams(f.elements.expiration.value?{expiration:f.elements.expiration.value}:{}));if(!r.ok)throw Error();const result=await r.json();if(request!==statusRequest||!f.isConnected)return;el.className='rw-status-badge '+result.tone;el.textContent=result.message;}
    catch(_){if(request===statusRequest)el.textContent='Preview unavailable. Shelf-life rules will be checked when saving.';}
  });
  f.querySelector('[data-add-location]')?.addEventListener('click',()=>quickLocation(f));
  f.querySelector('[data-update-pack]').onclick=async()=>{const packValue=Number(packButton.dataset.value),feedback=f.querySelector('[data-pack-feedback]');if(packButton.disabled||busy)return;if(!await confirmReceiving('Update catalogue case size?', 'Change the shared catalogue from '+number(savedPack)+' to '+number(packValue)+' units per case? Future receipts will use this size, including other accounts using this product. Existing invoice and receipt quantities stay unchanged.', 'Update catalogue'))return;await run('case-pack',id,{requestId:crypto.randomUUID(),unitsPerCase:packValue},f,()=>{savedPack=packValue;feedback.textContent='Shared catalogue case pack updated to '+number(savedPack)+'. Receipt quantities unchanged.';preview();});preview();};
  f.addEventListener('input',()=>{syncCases('quantity','cases');exceptions.forEach(([key])=>syncCases(key,key+'Cases'));});
  f.addEventListener('input',preview);preview();body.append(f);
  let selectedTab='sellable';
  const tabs=f.querySelector('.rw-exception-actions');tabs.setAttribute('role','tablist');tabs.setAttribute('aria-label','Receiving outcomes');
  const mainTab=button('Sellable',()=>selectTab('sellable'));mainTab.dataset.exception='sellable';tabs.prepend(mainTab);f.prepend(tabs);
  sellable.id='rw-sellable';sellable.setAttribute('role','tabpanel');sellable.setAttribute('aria-labelledby','rw-tab-sellable');
  exceptions.forEach(([key])=>{const panel=f.querySelector('[data-exception-fields="'+key+'"]');panel.setAttribute('role','tabpanel');panel.setAttribute('aria-labelledby','rw-tab-'+key);});
  tabs.querySelectorAll('button').forEach(b=>{b.id='rw-tab-'+b.dataset.exception;b.setAttribute('aria-label',b.textContent);b.setAttribute('aria-controls','rw-'+b.dataset.exception);});
  const selectTab=key=>{
    selectedTab=key;sellable.hidden=key!=='sellable';
    exceptions.forEach(([name])=>{const section=f.querySelector('[data-exception-fields="'+name+'"]');section.hidden=key!==name;if(key===name&&section.disabled){section.disabled=false;f.elements[name+'Cases'].value='1';syncCases(name,name+'Cases');}});
    tabs.querySelectorAll('button').forEach(b=>{const active=b.dataset.exception===key,enabled=b.dataset.exception==='sellable'||!f.querySelector('[data-exception-fields="'+b.dataset.exception+'"]').disabled;b.dataset.enabled=String(enabled);b.setAttribute('aria-selected',String(active));b.tabIndex=active?0:-1;});preview();
    const input=key==='sellable'?f.elements.expiration:f.elements[key+'Cases'];input.focus();if(input.type!=='date')input.select();
  };
  tabs.querySelectorAll('button').forEach((b,index)=>{b.setAttribute('role','tab');b.removeAttribute('aria-expanded');b.onclick=async()=>{const key=b.dataset.exception,section=f.querySelector('[data-exception-fields="'+key+'"]');if(section&&!section.disabled&&selectedTab===key){if(Number(f.elements[key].value)>0&&!await confirmReceiving('Clear '+b.textContent+' quantity?',number(f.elements[key].value)+' unsaved units will be cleared and this section will become inactive. Other receipt entries will stay unchanged. No saved inventory or receipt history will be changed.','Clear quantity'))return;section.disabled=true;f.elements[key+'Cases'].value='0';syncCases(key,key+'Cases');selectTab('sellable');}else selectTab(key);receiptDirty=true;remember();};b.onkeydown=event=>{const buttons=[...tabs.querySelectorAll('button')];let next=event.key==='ArrowRight'?(index+1)%buttons.length:event.key==='ArrowLeft'?(index+buttons.length-1)%buttons.length:event.key==='Home'?0:event.key==='End'?buttons.length-1:-1;if(next>=0){event.preventDefault();buttons[next].click();}};});
  const remember=()=>receiptDrafts.set(id,{overage,requestId:f.dataset.requestId,tab:selectedTab,batches:extraBatches.map(row=>({cases:row.querySelector('[data-batch-cases]').value,pack:row.querySelector('[data-exception-pack]').value,quantity:row.querySelector('[data-batch-quantity]').value,expiration:row.querySelector('[data-batch-date]').value})),values:Object.fromEntries([...f.elements].filter(el=>el.name&&!el.closest('.rw-expiration-batch')).map(el=>[el.name,el.type==='checkbox'?el.checked:el.value])),active:exceptions.filter(([key])=>!f.querySelector('[data-exception-fields="'+key+'"]').disabled).map(([key])=>key)});
  const draft=receiptDrafts.get(id);
  if(draft?.batches)draft.batches.forEach(insertBatch);
  if(draft){exceptions.forEach(([key])=>{const active=draft.active.includes(key),section=f.querySelector('[data-exception-fields="'+key+'"]');section.hidden=!active;section.disabled=!active;f.querySelector('[data-exception="'+key+'"]').setAttribute('aria-expanded',String(active));});Object.entries(draft.values).forEach(([name,value])=>{const el=f.elements[name];if(el){if(el.type==='checkbox')el.checked=value;else el.value=value;}});exceptions.forEach(([key])=>syncCases(key,key+'Cases'));preview();f.elements.expiration.dispatchEvent(new Event('input',{bubbles:true}));}
  receiptDirty=Boolean(draft);f.addEventListener('input',()=>{receiptDirty=true;remember();});f.addEventListener('change',()=>{receiptDirty=true;remember();});f.addEventListener('click',event=>{if(event.target.closest('[data-exception]'))remember();});
  window.NextAiPlatformControls?.enhance(f);
  if(!l.receipts)body.append(button('Item fees…',()=>{remember();prepare(id);returnToReceipt=()=>receive(id);drawer.querySelectorAll('[data-rw-close]').forEach(b=>{if(b.textContent.includes('Back to'))b.textContent='Back to receipt';});},'rw-text-action'));
  f.addEventListener('invalid',event=>{const section=event.target.closest('[data-exception-fields]');selectTab(section?section.dataset.exceptionFields:'sellable');},true);
  syncCases('quantity','cases');selectTab(draft?.tab||'sellable');
  if(overage)tabs.hidden=true;
  requestAnimationFrame(()=>{if(f.isConnected&&!sellable.hidden)f.elements.expiration.focus();});
}
function quickLocation(receiptForm){
  const modal=document.getElementById('location-dialog'),locationForm=modal.querySelector('form');
  const created=event=>{const result=event.detail;if(!state.locations.some(l=>l.id===result.id))state.locations.push({...result,status:'ACTIVE'});receiptForm.elements.locationId.value=result.id;receiptForm.elements.locationId.dispatchEvent(new Event('change',{bubbles:true}));modal.close();};
  modal.addEventListener('location:created',created);modal.addEventListener('close',()=>modal.removeEventListener('location:created',created),{once:true});
  locationForm.reset();locationForm.querySelector('[data-location-feedback]').hidden=true;modal.showModal();locationForm.elements.code.focus();
}
function addCatalogue(id){
  const l=line(id);open('Add to catalogue',l.product,label(doc(l.documentId)));
  body.innerHTML=note(l.code||'New item','Create this invoice item in your account catalogue, then continue receiving.');
  body.append(form('<label>Units / case<input name="pack" type="number" min="1" step="1" required value="'+escape(l.unitsPerCase||1)+'"></label><label class="rw-check"><input name="requiresExpiration" type="checkbox" checked>Requires expiration date</label><button type="submit" class="primary-button compact-button">Add item and continue</button>',
    (f,requestId)=>run('catalogue',id,{requestId,unitsPerCase:Number(f.elements.pack.value),expirationRequired:f.elements.requiresExpiration.checked},f,()=>receive(id))));
}
function prepare(id){
  const l=line(id);open('Pack & item fees',l.product,label(doc(l.documentId)));
  body.innerHTML=note('Before the first receipt','Confirm how many each are in a case and any per-each deposit or other fee. These settings lock once receiving begins. No physical inventory changes here.');
  body.append(form('<label>Each per case<input name="pack" type="number" min="1" step="1" required value="'+escape(l.unitsPerCase)+'"></label>'+
    '<div class="rw-pair"><label>Deposit per each<input name="deposit" type="number" min="0" step=".0001" required value="'+escape(l.depositFee)+'"></label><label>Other fee per each<input name="other" type="number" min="0" step=".0001" required value="'+escape(l.otherFee)+'"></label></div>'+
    '<button class="primary-button compact-button rw-primary-action" type="submit">Save and continue receiving</button>',
    (f,requestId)=>run('prepare',id,{requestId,unitsPerCase:Number(f.elements.pack.value),depositFee:Number(f.elements.deposit.value),otherFee:Number(f.elements.other.value)},f,()=>receive(id))));
}
async function review(id){
  const l=line(id);if(!l)return;open('Receipt history',l.product,label(doc(l.documentId)));
  const sequence=viewSequence;body.innerHTML='<p class="rw-muted">Checking stock and order commitments…</p>';
  try{
    const response=await fetch('/app/receiving/work/lines/'+id+'/receipts',{headers:{Accept:'application/json'}});
    const receipts=await response.json();if(!response.ok)throw new Error(receipts.message||'Receipt history could not be loaded.');
    if(sequence!==viewSequence)return;body.replaceChildren();
    if(!receipts.length)body.innerHTML=note('No receipts yet','Receive the quantities that arrived, or close this document with an explanation.');
    receipts.forEach(r=>{
      const card=document.createElement('article');card.className='rw-receipt'+(r.undone?' is-undone':'');
      card.dataset.receivingStatus=['EXPIRED','DAMAGED','MISPICKED'].includes(r.disposition)?'danger':['SHORT_SHIPPED','SOON_EXPIRED'].includes(r.disposition)?'warning':'success';
      card.innerHTML='<h3>'+number(r.quantity)+' each · '+escape(conditionNames[r.disposition]||r.disposition)+(r.undone?' · Undone':'')+'</h3><p>'+escape(time(r.receivedAt))+' · '+escape(r.location||'No stock location')+'</p><p>'+escape(date(r.expiration))+'</p>';
      const physical=['SELLABLE','SOON_EXPIRED','EXPIRED','OVER_SHIPPED'].includes(r.disposition);
      if(!r.undone&&physical)card.innerHTML+='<p>Batch now: '+number(r.onHand)+' on hand · '+number(r.reserved)+' reserved</p>';
      if(!r.undone&&r.blockedReason)card.innerHTML+='<p>'+escape(r.blockedReason)+'</p>';
      if(!r.undone&&canEdit&&!l.closed){
        const actions=document.createElement('div');actions.className='rw-row-actions';
        const undo=button('Undo receipt',()=>undoReceipt(id,r));undo.disabled=!!r.blockedReason;if(r.blockedReason)undo.title=r.blockedReason;actions.append(undo);
        if(physical&&!l.closed)actions.append(button('Adjust stock',()=>adjust(id,r)));
        card.append(actions);
      }
      body.append(card);
    });
  }catch(error){if(sequence===viewSequence)message(error.message,true,true);}
}
function undoReceipt(id,r){
  const l=line(id);open('Undo this receipt?',l.product,label(doc(l.documentId)));
  body.innerHTML=note('Undo '+number(r.quantity)+' each · '+(conditionNames[r.disposition]||r.disposition),r.disposition==='SHORT_SHIPPED'?'The quantity returns to remaining, and its open vendor follow-up is reduced. No stock changes.':'Only untouched stock can be undone. Active orders and movements are checked before saving. The original receipt remains visible.',true);
  body.append(form(reason()+'<button class="secondary-button compact-button rw-primary-action rw-danger" type="submit">Confirm undo receipt</button>',
    (f,requestId)=>run('undo',id,{requestId,receipt:r.id,reason:f.elements.reason.value},f,()=>review(id))));
}
function adjust(id,r){
  const l=line(id);open('Adjust batch stock',l.product,label(doc(l.documentId)));
  body.innerHTML=note(number(r.onHand)+' each on hand · '+number(r.reserved)+' reserved',(r.location||'')+' · '+(r.expiration||'No expiration')+'. This appends a correction; the invoice and receipt stay unchanged.');
  const f=form('<fieldset class="rw-direction" aria-label="Adjustment direction"><label><input name="direction" type="radio" value="INCREASE" checked>+ Increase</label><label><input name="direction" type="radio" value="DECREASE">− Decrease</label></fieldset>'+
    '<label>Quantity · each<input name="quantity" type="number" min="1" step="1" required></label><p class="rw-quantity-preview" data-preview></p>'+
    '<label>Reason<select name="reason"><option value="COUNT_CORRECTION">Count correction</option><option value="DAMAGE">Damage</option><option value="LOSS">Loss</option><option value="DONATION">Donation</option><option value="REMOVAL">Disposal / removal</option><option value="RETURN_TO_VENDOR">Return to vendor</option><option value="OTHER">Other</option></select></label>'+
    '<button class="primary-button compact-button rw-primary-action" type="submit">Save adjustment</button>',
    (f,requestId)=>run('adjust',id,{requestId,receipt:r.id,quantity:Number(f.elements.quantity.value),direction:f.elements.direction.value,reason:f.elements.reason.value},f,()=>review(id)));
  f.addEventListener('input',()=>{
    const decreasing=f.elements.direction.value==='DECREASE',after=Number(r.onHand)+(decreasing?-1:1)*Number(f.elements.quantity.value);
    f.querySelector('[data-preview]').textContent='After adjustment: '+number(after)+' each. '+(after<Number(r.reserved)?'This would use stock committed to orders and cannot be saved.':'Reserved stock stays protected.');
    f.elements.quantity.setCustomValidity(decreasing&&after<Number(r.reserved)?'The remaining quantity must cover reserved orders.':'');
  });body.append(f);f.elements.quantity.focus();
}
function reviewDocument(id){
  const d=doc(id);if(!d)return;open(label(d),d.vendor,'Source document');
  body.innerHTML='<dl class="rw-detail"><dt>Document date</dt><dd>'+escape(d.date||'Not provided')+'</dd><dt>Uploaded</dt><dd>'+escape(time(d.uploadedAt))+'</dd><dt>Expected</dt><dd>'+number(d.expected)+' each</dd><dt>Received</dt><dd>'+number(d.received)+' each</dd><dt>Still expected</dt><dd>'+number(d.outstanding)+' each</dd><dt>Status</dt><dd>'+escape(progress(d))+'</dd></dl>';
  if(d.closed){
    body.innerHTML+=note('Closed documents are locked',d.closeReason||'All quantities resolved')+'<p class="rw-muted">Use Available Inventory to adjust remaining stock. The original receipt and this document cannot be edited or deleted.</p>';return;
  }
  body.innerHTML+=note('Keep receiving whenever the rest arrives','There is no deadline. Close the document only when you do not expect another delivery against it.');
  if(!canEdit)return;
  const partial=Number(d.outstanding)>0;
  body.append(button(partial?'Close remaining balance…':'Close completed document…',()=>closeDocument(id),'primary-button compact-button'));
  const divider=document.createElement('div');divider.className='rw-divider';body.append(divider);
  if(d.hasHistory||Number(d.received)>0){
    const text=document.createElement('p');text.className='rw-muted';text.textContent='Cannot delete: this document has receipt or vendor follow-up history. You can close it, or review individual receipts for an eligible undo or stock adjustment.';body.append(text);
  }else body.append(button('Delete unused document…',()=>{
    open('Delete this unused document?',label(d),'Receiving');
    body.innerHTML=note('No stock will be removed','Deletion is allowed only if nothing has ever been received and no vendor claim exists. The source record is retained in the audit history.',true);
    body.append(form('<button class="secondary-button compact-button rw-primary-action rw-danger" type="submit">Confirm delete unused document</button>',
      (f,requestId)=>run('remove',id,{requestId},f,()=>{open('Document removed','Choose Back to checklist, or return to Receiving documents.');body.innerHTML=note('Source record retained','No inventory changed.');})));
  },'secondary-button compact-button rw-danger'));
}
function closeDocument(id){
  if(!canEdit||!doc(id)||doc(id).closed)return;
  const d=doc(id),partial=Number(d.outstanding)>0;open(partial?'Close with a remaining balance?':'Close this document?',label(d),'Final review');
  body.innerHTML=note(number(d.received)+' each received · '+number(d.outstanding)+' each still expected',
    'Received stock stays in inventory. Closing locks the document and its receipts. Future corrections use an adjustment; this document cannot be reopened.',true);
  body.append(form((partial?reason()+'<label class="rw-check"><input name="creditExpected" type="checkbox">Create a vendor follow-up for the undelivered balance. This records a claim; it does not issue a credit or contact the vendor.</label>':'')+
    '<label class="rw-check"><input type="checkbox" required>I do not expect any further receipt against this document.</label>'+
    '<button class="primary-button compact-button rw-primary-action" type="submit">Confirm close document</button>',
    (f,requestId)=>run('close',id,{requestId,reason:partial?f.elements.reason.value:null,creditExpected:!!f.elements.creditExpected?.checked,previewReceived:d.received,previewOutstanding:d.outstanding},f,()=>reviewDocument(id))));
}
document.addEventListener('click',event=>{
  const target=event.target.closest('[data-receive],[data-review-line],[data-review-document],[data-adjust-line],[data-overage-line]');if(!target)return;
  const menu=target.closest('details');if(menu)menu.open=false;
  if(target.dataset.adjustLine)review(target.dataset.adjustLine);
  if(target.dataset.overageLine)receive(target.dataset.overageLine,true);
  if(target.dataset.receive)receive(target.dataset.receive);
  if(target.dataset.reviewLine)review(target.dataset.reviewLine);
  if(target.dataset.reviewDocument)reviewDocument(target.dataset.reviewDocument);
});
const requestedClose=new URLSearchParams(window.location.search).get('closeDocument');
document.querySelectorAll('[data-line]').forEach(row=>{const l=line(row.dataset.line);if(l){const actions=row.querySelector('[data-column="actions"]');const chat=actions.querySelector('.collaboration-row-button');actions.replaceChildren(rowActions(l));if(chat)actions.prepend(chat);renderLine(row,l);}});
if(requestedClose&&canEdit&&doc(requestedClose)&&!doc(requestedClose).closed)closeDocument(requestedClose);
})();
