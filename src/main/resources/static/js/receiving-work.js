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
const conditionNames={SELLABLE:'Received',SOON_EXPIRED:'Short shelf life',EXPIRED:'Expired',SHORT_SHIPPED:'Short shipped',DAMAGED:'Damaged',MISPICKED:'Wrong item',OVER_SHIPPED:'Over received'};
const date=value=>value?value.split('-').slice(1).concat(value.slice(2,4)).join('/'):'No expiration';
const shelf=value=>{if(!value)return 'neutral';const days=Math.round((new Date(value+'T00:00:00')-new Date(new Date().toDateString()))/86400000);return days<=Number(state.policy?.minimumSellableDays??10)?'danger':days<=Number(state.policy?.warningDays??30)?'warning':'success'};
function renderLine(row,l){
  const d=detail(l.id);let batches=[];try{batches=JSON.parse(d.batches||'[]')}catch(_){}
  const dates=[...new Set(batches.filter(b=>['SELLABLE','SOON_EXPIRED','EXPIRED','OVER_SHIPPED'].includes(b.condition)).map(b=>b.expiration))];
  row.querySelector('[data-column="expiration"]').innerHTML=dates.length?dates.map(v=>'<span class="rw-status-badge '+shelf(v)+'">'+escape(date(v))+'</span>').join(' '):'—';
  const conditions=[...new Set(batches.map(b=>b.condition))];if(Number(d.shortage)>0&&!conditions.includes('SHORT_SHIPPED'))conditions.push('SHORT_SHIPPED');
  const tone=conditions.some(c=>['EXPIRED','DAMAGED','MISPICKED'].includes(c))||dates.some(v=>shelf(v)==='danger')?'danger':conditions.some(c=>['SHORT_SHIPPED','SOON_EXPIRED'].includes(c))||dates.some(v=>shelf(v)==='warning')?'warning':Number(l.received)>0?'success':'open';
  row.dataset.receivingStatus=l.closed?'closed':tone;
  row.querySelector('[data-column="condition"]').innerHTML=(conditions.length?conditions.map(c=>'<span class="rw-status-badge '+(['EXPIRED','DAMAGED','MISPICKED'].includes(c)?'danger':['SHORT_SHIPPED','SOON_EXPIRED'].includes(c)?'warning':'success')+'">'+escape(conditionNames[c]||c)+'</span>').join(' '):'<span class="rw-status-badge open">Not received</span>')+(l.closed?'<small>Closed · Read only</small>':'');
  row.querySelector('[data-column="remaining"]').innerHTML=(l.closed?'Closed':number(l.remaining)+' each')+(Number(d.shortage)>0?'<small>'+number(d.shortage)+' short shipped</small>':'');
}
const time=value=>value?new Date(value).toLocaleString('en-US',{month:'2-digit',day:'2-digit',year:'2-digit',hour:'numeric',minute:'2-digit'}):'—';
const money=(amount,currency)=>currency+' '+Number(amount||0).toFixed(2);
const message=(text,error=false,panel=false)=>{const el=document.getElementById(panel?'rw-panel-feedback':'rw-feedback');el.textContent=text;el.classList.toggle('error',error);el.hidden=false;};
let viewSequence=0,busy=false;
function open(title,subtitle,context){
  viewSequence++;document.getElementById('rw-drawer-title').textContent=title;
  document.getElementById('rw-drawer-subtitle').textContent=subtitle||'';
  document.getElementById('rw-drawer-context').textContent=context||'Receiving';
  document.getElementById('rw-panel-feedback').hidden=true;body.replaceChildren();body.scrollTop=0;
  if(!drawer.open)drawer.showModal();
}
document.querySelectorAll('[data-rw-close]').forEach(button=>button.onclick=()=>{if(!busy)drawer.close();});
drawer.addEventListener('cancel',event=>{if(busy)event.preventDefault();});
function button(text,action,cls='secondary-button compact-button'){
  const el=document.createElement('button');el.type='button';el.className=cls;el.textContent=text;el.onclick=action;return el;
}
function form(markup,submit){
  const el=document.createElement('form');el.className='rw-form';el.innerHTML=markup;
  const requestId=crypto.randomUUID();
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
  const response=await fetch('/app/receiving/work/state?'+params,{headers:{Accept:'application/json'}});
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
    saved=true;await refresh();busy=false;
    if(after)await after();message(result.message,false,true);message(result.message);
  }catch(error){
    message(error.message,true,true);
    if(saved){el.querySelectorAll('input,select,textarea,button').forEach(field=>field.disabled=true);el.dataset.saved='true';}
  }finally{
    busy=false;buttons.filter(b=>b.isConnected&&!b.closest('[data-saved]')).forEach(b=>b.disabled=false);
  }
}
function receive(id){
  const l=line(id);if(!l||l.closed||!canEdit)return;
  if(!l.productId)return addCatalogue(id);
  const d=doc(l.documentId);open('Receive stock',l.product,label(d));
  const facts=detail(id),pack=Number(l.receipts?facts.catalogPack||l.unitsPerCase:l.unitsPerCase||facts.catalogPack||1);
  body.innerHTML='<div class="rw-receive-summary"><span><strong>'+number(l.remaining)+'</strong> each remaining</span><span>'+number(l.received)+' received</span><span>'+escape(money(l.unitCost,l.currency))+' / each</span></div>';
  const locations=state.locations.filter(v=>v.status==='ACTIVE').map(v=>'<option value="'+escape(v.id)+'"'+(v.id===l.locationId?' selected':'')+'>'+escape(v.code+' · '+v.name)+'</option>').join('');
  const f=form('<div class="rw-quantity-grid"><label>Cases<input name="cases" type="number" min="0" step="any" value="'+Number((Number(l.remaining)/pack).toFixed(6))+'"></label><label>Units / case<input name="pack" type="number" min="1" max="100000" step="1" required value="'+pack+'"></label><label>Total units<input name="quantity" type="number" min="1" step="1" required value="'+escape(l.remaining)+'"></label></div>'+
    '<small class="rw-muted">Case size is saved to the catalogue for future receiving.'+(l.receipts?' Existing invoice quantities stay unchanged.':'')+'</small>'+
    '<div class="rw-pair"><label>Expiration date<input name="expiration" type="date"'+(l.requiresExpiration?' required':'')+'></label><label>Location<select name="location" required><option value="">Choose a location</option>'+locations+'</select></label></div>'+
    '<label class="rw-check"><input name="requiresExpiration" type="checkbox"'+(l.requiresExpiration?' checked':'')+'>This item requires an expiration date <small>Saved to catalogue</small></label>'+
    '<label>Condition<select name="disposition"><option value="SELLABLE">Received in good condition</option><option value="SOON_EXPIRED">Short shelf life · vendor follow-up</option><option value="EXPIRED">Expired · cannot sell</option><option value="DAMAGED">Damaged · not added to inventory</option><option value="MISPICKED">Wrong item · not added to inventory</option><option value="OVER_SHIPPED">Extra units received · overage at zero cost</option><option value="SHORT_SHIPPED">Not delivered · record shortage</option></select></label>'+
    '<p class="rw-quantity-preview" data-preview></p><button class="primary-button compact-button rw-primary-action" type="submit">Save receipt</button>',
    (f,requestId)=>run('receive',id,{requestId,quantity:Number(f.elements.quantity.value),unitsPerCase:Number(f.elements.pack.value),expirationRequired:f.elements.requiresExpiration.checked,expiration:f.elements.expiration.value||null,location:f.elements.location.value,disposition:f.elements.disposition.value},f,()=>Number(line(id).remaining)>0?receive(id):review(id)));
  const preview=()=>{const condition=f.elements.disposition.value;
    const nonPhysical=['DAMAGED','MISPICKED','SHORT_SHIPPED'].includes(condition);
    f.elements.expiration.required=(f.elements.requiresExpiration.checked||['SOON_EXPIRED','EXPIRED'].includes(condition))&&!nonPhysical;
    f.querySelector('[data-preview]').textContent=nonPhysical?number(f.elements.quantity.value)+' units recorded as '+(conditionNames[condition]||condition).toLowerCase()+'. No stock added.':number(f.elements.quantity.value)+' units will be added to inventory.';
    if(!nonPhysical&&f.elements.expiration.value){const tone=shelf(f.elements.expiration.value);f.querySelector('[data-preview]').textContent+=' '+(tone==='danger'?'Cannot sell under your shelf-life rules.':tone==='warning'?'Short shelf life — act soon.':'Healthy shelf life.');f.querySelector('[data-preview]').dataset.tone=tone;}
    if(f.elements.expiration.value&&new Date(f.elements.expiration.value+'T23:59:59')<new Date()&&condition==='SELLABLE')
      f.querySelector('[data-preview]').textContent='This date is in the past. Choose Expired to receive the units as cannot sell.';
  };
  f.elements.cases.addEventListener('input',()=>{f.elements.quantity.value=String(Number((Number(f.elements.cases.value)*Number(f.elements.pack.value)).toFixed(4)))});
  f.elements.pack.addEventListener('input',()=>{f.elements.quantity.value=String(Number((Number(f.elements.cases.value)*Number(f.elements.pack.value)).toFixed(4)))});
  f.elements.quantity.addEventListener('input',()=>{f.elements.cases.value=String(Number((Number(f.elements.quantity.value)/Number(f.elements.pack.value||1)).toFixed(6)))});
  f.addEventListener('input',preview);preview();body.append(f);
  if(!l.receipts)body.append(button('Item fees…',()=>prepare(id),'rw-text-action'));
  (l.requiresExpiration?f.elements.expiration:f.elements.quantity).focus();
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
  const target=event.target.closest('[data-receive],[data-review-line],[data-review-document]');if(!target)return;
  if(target.dataset.receive)receive(target.dataset.receive);
  if(target.dataset.reviewLine)review(target.dataset.reviewLine);
  if(target.dataset.reviewDocument)reviewDocument(target.dataset.reviewDocument);
});
const requestedClose=new URLSearchParams(window.location.search).get('closeDocument');
document.querySelectorAll('[data-line]').forEach(row=>{const l=line(row.dataset.line);if(l){const actions=row.querySelector('[data-column="actions"]');const chat=actions.querySelector('.collaboration-row-button');actions.replaceChildren(rowActions(l));if(chat)actions.prepend(chat);renderLine(row,l);}});
if(requestedClose&&canEdit&&doc(requestedClose)&&!doc(requestedClose).closed)closeDocument(requestedClose);
})();
