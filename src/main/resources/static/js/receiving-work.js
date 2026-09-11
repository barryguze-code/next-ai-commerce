(()=>{
'use strict';
const state=window.receivingWork;if(!state)return;
const drawer=document.getElementById('receiving-drawer'),body=document.getElementById('rw-drawer-body');
const canEdit=document.querySelector('.receive-work').dataset.canEdit==='true';
const escape=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const number=value=>Number(value||0).toLocaleString(undefined,{maximumFractionDigits:4});
const doc=id=>state.documents.find(d=>d.id===id),line=id=>state.lines.find(l=>l.id===id);
const label=d=>(d.type==='INVOICE'?'Invoice':'Packing list')+' · '+(d.number||d.filename);
const progress=d=>d.closed?(d.partial?'Closed · Partial':'Closed · Received'):Number(d.received)===0?'Not received':Number(d.outstanding)>0?'Partially received':'Fully received';
const time=value=>value?new Date(value).toLocaleString(undefined,{dateStyle:'medium',timeStyle:'short'}):'—';
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
function notes(){return '<label>Notes · Optional<textarea name="notes" maxlength="500" placeholder="Anything the next teammate should know"></textarea></label>';}
function reason(){return '<label>Reason<textarea name="reason" maxlength="500" required placeholder="Explain this correction for the audit history"></textarea></label>';}
function note(title,copy,warning=false){return '<div class="rw-note'+(warning?' warning':'')+'"><strong>'+escape(title)+'</strong>'+escape(copy)+'</div>';}
function rowActions(l){
  const actions=document.createElement('div');actions.className='rw-row-actions';
  if(canEdit&&!l.closed&&Number(l.remaining)>0)actions.append(button('Receive',()=>receive(l.id),'primary-button compact-button'));
  if(l.receipts>0)actions.append(button('Review receipts',()=>review(l.id)));
  if(l.closed&&!l.receipts){const text=document.createElement('span');text.className='rw-locked';text.textContent='No receipts';actions.append(text);}
  return actions;
}
async function refresh(){
  const params=new URLSearchParams();state.documents.forEach(d=>params.append('documents',d.id));
  const response=await fetch('/app/receiving/work/state?'+params,{headers:{Accept:'application/json'}});
  if(!response.ok)throw new Error('Saved, but the checklist could not be refreshed. Reload this view before making another change.');
  const updated=await response.json();state.documents=updated.documents;state.lines=updated.lines;
  document.querySelectorAll('[data-line]').forEach(row=>{
    const l=line(row.dataset.line);if(!l){row.remove();return;}
    row.querySelector('[data-column="expected"]').textContent=number(l.expected)+' each';
    row.querySelector('[data-column="received"]').textContent=number(l.received)+' each';
    row.querySelector('[data-column="remaining"]').textContent=l.closed?'Closed':number(l.remaining)+' each';
    row.querySelector('[data-column="actions"]').replaceChildren(rowActions(l));
  });
  document.querySelectorAll('.rw-document').forEach(card=>{
    const d=doc(card.dataset.document);if(!d){card.remove();return;}
    card.querySelector('.rw-progress').textContent=progress(d);
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
  const d=doc(l.documentId);open('Receive stock',l.product,label(d));
  body.innerHTML=note(number(l.remaining)+' each still expected','Previously received: '+number(l.received)+' each. Save one expiration batch at a time. Leaving this panel does not close the document.');
  if(!l.receipts){const edit=button('Review pack & item fees',()=>prepare(id));edit.style.marginBottom='16px';body.append(edit);}
  const locations=state.locations.filter(v=>v.status==='ACTIVE').map(v=>'<option value="'+escape(v.id)+'"'+(v.id===l.locationId?' selected':'')+'>'+escape(v.code+' · '+v.name)+'</option>').join('');
  const f=form('<div class="rw-pair"><label>Quantity · each<input name="quantity" type="number" min="1" step="1" required value="'+escape(l.remaining)+'"></label><label>Expiration date'+(l.requiresExpiration?' · Required':' · Optional')+'<input name="expiration" type="date"'+(l.requiresExpiration?' required':'')+'></label></div>'+
    '<p class="rw-muted">'+number(l.unitsPerCase)+' each per case · Invoice unit cost '+escape(money(l.unitCost,l.currency))+'. Shared landed costs finalize when all documents in the original upload are closed.</p>'+
    '<label>Location<select name="location" required><option value="">Choose a location</option>'+locations+'</select></label>'+
    '<label>Condition<select name="disposition"><option value="SELLABLE">Received in good condition</option><option value="SOON_EXPIRED">Short shelf life · vendor follow-up</option><option value="EXPIRED">Expired · cannot sell</option><option value="DAMAGED">Damaged · not added to inventory</option><option value="MISPICKED">Wrong item · not added to inventory</option><option value="OVER_SHIPPED">Extra units received · overage at zero cost</option><option value="SHORT_SHIPPED">Not delivered · record shortage</option></select></label>'+
    '<p class="rw-quantity-preview" data-preview></p>'+notes()+'<button class="primary-button compact-button rw-primary-action" type="submit">Save receipt</button>',
    (f,requestId)=>run('receive',id,{requestId,quantity:Number(f.elements.quantity.value),expiration:f.elements.expiration.value||null,location:f.elements.location.value,disposition:f.elements.disposition.value,notes:f.elements.notes.value},f,()=>Number(line(id).remaining)>0?receive(id):review(id)));
  const preview=()=>{const condition=f.elements.disposition.value;
    const nonPhysical=['DAMAGED','MISPICKED','SHORT_SHIPPED'].includes(condition);
    f.elements.expiration.required=l.requiresExpiration&&!nonPhysical;
    f.querySelector('[data-preview]').textContent=nonPhysical?'No warehouse inventory will be added. This quantity is recorded for vendor follow-up.':number(f.elements.quantity.value)+' each will be added to physical inventory. Sellability follows the configured shelf-life rules.';
    if(f.elements.expiration.value&&new Date(f.elements.expiration.value+'T23:59:59')<new Date()&&condition==='SELLABLE')
      f.querySelector('[data-preview]').textContent='This date is in the past. Choose Expired to receive the units as cannot sell.';
  };
  f.addEventListener('input',preview);preview();body.append(f);f.elements.quantity.focus();
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
      card.innerHTML='<h3>'+number(r.quantity)+' each'+(r.undone?' · Undone':'')+'</h3><p>'+escape(time(r.receivedAt))+' · '+escape(r.location||'No stock location')+'</p><p>'+escape(r.disposition.replaceAll('_',' '))+' · '+escape(r.expiration||'No expiration')+'</p>';
      const physical=['SELLABLE','SOON_EXPIRED','EXPIRED','OVER_SHIPPED'].includes(r.disposition);
      if(!r.undone&&physical)card.innerHTML+='<p>Batch now: '+number(r.onHand)+' on hand · '+number(r.reserved)+' reserved</p>';
      if(!r.undone&&r.blockedReason)card.innerHTML+='<p>'+escape(r.blockedReason)+'</p>';
      if(!r.undone&&canEdit){
        const actions=document.createElement('div');actions.className='rw-row-actions';
        const undo=button('Undo receipt',()=>undoReceipt(id,r));undo.disabled=!!r.blockedReason;if(r.blockedReason)undo.title=r.blockedReason;actions.append(undo);
        if(physical)actions.append(button('Adjust stock',()=>adjust(id,r)));
        card.append(actions);
      }
      body.append(card);
    });
  }catch(error){if(sequence===viewSequence)message(error.message,true,true);}
}
function undoReceipt(id,r){
  const l=line(id);open('Undo this receipt?',l.product,label(doc(l.documentId)));
  body.innerHTML=note('Reverse '+number(r.quantity)+' each','Only untouched stock can be undone. We will recheck active orders and batch movements before saving. The original receipt remains visible.',true);
  body.append(form(reason()+'<button class="secondary-button compact-button rw-primary-action rw-danger" type="submit">Confirm undo receipt</button>',
    (f,requestId)=>run('undo',id,{requestId,receipt:r.id,reason:f.elements.reason.value},f,()=>review(id))));
}
function adjust(id,r){
  const l=line(id);open('Adjust batch stock',l.product,label(doc(l.documentId)));
  body.innerHTML=note(number(r.onHand)+' each on hand · '+number(r.reserved)+' reserved',(r.location||'')+' · '+(r.expiration||'No expiration')+'. This appends a correction; the invoice and receipt stay unchanged.');
  const f=form('<fieldset class="rw-direction" aria-label="Adjustment direction"><label><input name="direction" type="radio" value="INCREASE" checked>+ Increase</label><label><input name="direction" type="radio" value="DECREASE">− Decrease</label></fieldset>'+
    '<label>Quantity · each<input name="quantity" type="number" min="1" step="1" required></label><p class="rw-quantity-preview" data-preview></p>'+
    '<label>Reason<select name="reason"><option value="COUNT_CORRECTION">Count correction</option><option value="DAMAGE">Damage</option><option value="LOSS">Loss</option><option value="DONATION">Donation</option><option value="REMOVAL">Disposal / removal</option><option value="RETURN_TO_VENDOR">Return to vendor</option><option value="OTHER">Other</option></select></label>'+notes()+
    '<button class="primary-button compact-button rw-primary-action" type="submit">Save adjustment</button>',
    (f,requestId)=>run('adjust',id,{requestId,receipt:r.id,quantity:Number(f.elements.quantity.value),direction:f.elements.direction.value,reason:f.elements.reason.value,notes:f.elements.notes.value},f,()=>review(id)));
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
    body.innerHTML+=note('Closed documents are locked',d.closeReason||'All quantities resolved')+'<p class="rw-muted">Use Review receipts on a product to adjust its remaining stock. The original receipt and this document cannot be edited or deleted.</p>';return;
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
})();
