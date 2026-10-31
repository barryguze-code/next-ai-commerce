/* Shared read-only comparison; prospective approval is a separate explicit action. */
(()=>{'use strict';
 const dialog=document.querySelector('#fee-comparison'),approval=document.querySelector('#fee-approval');if(!dialog||!approval)return;
 let pending=null,comparisonSequence=0,comparisonController=null;const origins=new WeakMap(),fallbacks=new WeakMap(),inFlight=new Map(),completed=new Set();
 const text=(tag,value,cls)=>{const n=document.createElement(tag);n.textContent=value;if(cls)n.className=cls;return n;};
 const choiceKey=items=>items.map(i=>i.comparison+':'+(i.expectedApproval||'')).sort().join('|');
 const matchingControls=key=>[...document.querySelectorAll('[data-fee-approval-id]')].filter(b=>b.feeChoices&&choiceKey(b.feeChoices)===key);
 const coherent=rows=>rows.length>0&&rows.length<=2&&new Set(rows.map(r=>r.fee_type)).size===rows.length&&rows.every(r=>['order_item_id','order_id','seller_sku','currency','quantity','unit_price','shipping'].every(k=>r[k]!=null&&String(r[k])===String(rows[0][k])));
 const recordLabel=button=>{const row=button.closest('[data-fee-order],[data-fee-sku]');return [row?.dataset.feeOrder?'Order '+row.dataset.feeOrder:'',row?.dataset.feeSku?'SKU '+row.dataset.feeSku:''].filter(Boolean).join(' · ')||'Selected fee record';};
 const rowStatus=(host,message)=>{if(!host?.isConnected)return;let n=host.querySelector('[data-fee-approval-state]');if(!n){n=text('small','');n.dataset.feeApprovalState='true';n.setAttribute('role','status');host.append(n);}n.textContent=message;};
 const date=v=>v?new Date(v).toLocaleString(): 'Unavailable';
 const money=(v,c,exact=false)=>v==null?'Unavailable':new Intl.NumberFormat(undefined,{style:'currency',currency:c,...(exact?{maximumFractionDigits:4}:{})}).format(v);
 // Derivation revisions of one immutable charge are not new economic differences.
 const economicKey=r=>[r.order_item_id,r.fee_type,r.currency,r.charge_id,r.expected_amount,r.actual_amount].map(v=>v??'').join('|');
 function earlierDifferences(rows){const seen=new Set();return rows.filter(r=>!r.current_revision&&!r.same_economic_as_current&&['HIGHER','LOWER'].includes(r.state)).filter(r=>{const key=economicKey(r);if(seen.has(key))return false;seen.add(key);return true;});}
 const delta=r=>r.state==='MATCHES'?'No variance':r.difference==null?r.reason:(r.state==='HIGHER'?'↑ +':'↓ −')+money(String(r.difference).replace(/^-/,'') ,r.currency);
 function close(d){if(d===dialog){comparisonSequence++;comparisonController?.abort();}d.close();document.body.classList.toggle('fee-overlay-open',!!document.querySelector('.fee-dialog[open]'));const origin=origins.get(d);if(origin?.isConnected&&!origin.disabled)origin.focus();else fallbacks.get(d)?.querySelector('button:not(:disabled)')?.focus();if(d===approval&&pending&&!inFlight.has(pending.key))pending=null;}
 for(const d of [dialog,approval]){d.querySelectorAll('[data-fee-close]').forEach(b=>b.addEventListener('click',()=>close(d)));d.addEventListener('cancel',e=>{e.preventDefault();close(d);});}
 function show(d,button){origins.set(d,button);fallbacks.set(d,button.parentElement);d.showModal();document.body.classList.add('fee-overlay-open');d.querySelector('button')?.focus();}
 async function open(button,order,sku,page=0,snapshot=null){
  const sequence=++comparisonSequence;comparisonController?.abort();comparisonController=new AbortController();const body=dialog.querySelector('[data-fee-body]'),feedback=dialog.querySelector('[data-fee-feedback]');
  if(dialog.open&&body.contains(document.activeElement))dialog.querySelector('[data-fee-close]')?.focus();
  body.replaceChildren();body.setAttribute('aria-busy','true');feedback.textContent='Loading fee evidence…';dialog.querySelector('[data-fee-identity]').textContent=order?'Order '+order:'SKU '+sku;if(!dialog.open)show(dialog,button);
  try{const qs=new URLSearchParams({order:order||'',sku:sku||'',page:String(page)});if(snapshot!=null)qs.set('snapshot',String(snapshot));const response=await fetch('/app/fee-variance?'+qs,{headers:{Accept:'application/json'},signal:comparisonController.signal});if(!response.ok)throw new Error('Fee evidence could not be loaded. Try again.');const data=await response.json();if(sequence!==comparisonSequence||!dialog.open)return;body.replaceChildren();feedback.textContent='Loaded 0 evidence records; displaying 0 current comparisons and 0 earlier differences.';
   if(!data.rows.length){body.append(text('p','No comparable fee differences. Missing evidence remains unavailable.'));if(page>0){const back=text('button','Newest fee evidence');back.type='button';back.addEventListener('click',()=>open(button,order,sku,0,null));body.append(back);}return;}
   const host=button.parentElement;host.querySelectorAll('[data-fee-approval-id]').forEach(n=>n.remove());
   const eligible=data.rows.filter(r=>order&&data.canApprove&&r.current_revision&&r.shipping_verified&&r.unit_actual!=null&&!r.already_approved&&['HIGHER','LOWER'].includes(r.state));
   if(coherent(eligible)&&!completed.has(choiceKey(eligible.map(r=>({comparison:r.id,expectedApproval:r.current_approval||null}))))){const use=text('button','Use actual fees');use.type='button';use.dataset.feeApprovalId='group';
    use.feeChoices=eligible.map(r=>({comparison:r.id,expectedApproval:r.current_approval||null}));use.disabled=inFlight.has(choiceKey(use.feeChoices));
    use.dataset.feeApprovalIdentity=(data.scope?.display_name||'Selected store')+' · '+(data.scope?.marketplace_identifier||'')+' · '+eligible[0].seller_sku+' · '+eligible[0].currency+' · FBA · Price '+money(eligible[0].unit_price,eligible[0].currency)+' · Shipping '+money(eligible[0].shipping,eligible[0].currency);
    use.dataset.feeApprovalIdentity+=' · Source order '+order+' · Effective when confirmed';
    use.dataset.feeApprovalAmount=eligible.map(r=>(r.fee_type==='FBA'?'FBA':'Referral')+': '+money(r.unit_expected,r.currency,true)+' → '+money(r.unit_actual,r.currency,true)+' per unit').join(' · ');
    host.append(use);
   }
   if(order&&data.canApprove&&!eligible.length&&data.rows.some(r=>r.current_revision&&!r.already_approved&&['HIGHER','LOWER'].includes(r.state))){const unavailable=text('button','Use actual fees');unavailable.type='button';unavailable.dataset.feeApprovalId='unavailable';unavailable.disabled=true;unavailable.title='Current price or shipping basis needs verification';unavailable.setAttribute('aria-label','Use actual fees unavailable: current price or shipping basis needs verification');host.append(unavailable);}
   if(order&&data.caseUrl&&!host.querySelector('[data-fee-case-link]')){const link=text('a','Open Seller Central case log');link.href=data.caseUrl;link.target='_blank';link.rel='noopener noreferrer';link.dataset.feeCaseLink='true';host.append(link);}
   const current=data.rows.filter(r=>r.current_revision),differences=current.filter(r=>['HIGHER','LOWER'].includes(r.state));
   const shown=differences.length?differences:current.filter(r=>r.state!=='MATCHES');
   const prior=earlierDifferences(data.rows);
   feedback.textContent='Loaded '+data.rows.length+' evidence '+(data.rows.length===1?'record':'records')+'; displaying '+shown.length+' current '+(shown.length===1?'comparison':'comparisons')+' and '+prior.length+' earlier '+(prior.length===1?'difference.':'differences.');
   if(!shown.length&&!prior.length)body.append(text('p',page>0?'No distinct comparisons or earlier differences are displayed on this page.'+(data.hasMore?' Continue to older evidence.':' Return to newest evidence.'):'No comparable fee differences.'));
   for(const r of shown){const section=text('section','','fee-detail');section.append(text('h3',(r.fee_type==='FBA'?'FBA':'Referral')+' · '+r.order_id),text('p',r.seller_sku+' · '+r.currency+' · total for '+r.quantity+(r.quantity===1?' unit':' units')+(r.current_revision?'':' · Earlier evidence')));
    const values=text('div','','fee-values');for(const [label,value] of [['Basis at order',money(r.expected_amount,r.currency)],['Charged',money(r.actual_amount,r.currency)],['Difference',delta(r)]]){const cell=text('div','');cell.append(text('span',label),text('strong',value,label==='Difference'?(r.state==='HIGHER'?'fee-higher':r.state==='LOWER'?'fee-lower':''):''));values.append(cell);}section.append(values,text('p','Change detected: '+date(r.boundary_at)+' · Charge received: '+date(r.charge_received_at),'fee-sources'));body.append(section);
   }
   if(prior.length){const details=text('details','');details.append(text('summary','Earlier fee differences'));
    for(const r of prior)details.append(text('p',(r.fee_type==='FBA'?'FBA':'Referral')+' · '+r.order_id+' · '+r.currency+' · total for '+r.quantity+(r.quantity===1?' unit':' units')+' · '+delta(r)+' · Charge received '+date(r.charge_received_at)));
    body.append(details);
   }
   if(data.hasMore){const more=text('button','Older fee evidence');more.type='button';more.addEventListener('click',()=>open(button,order,sku,page+1,data.snapshot));body.append(more);}
   if(page>0){const back=text('button','Newest fee evidence');back.type='button';back.addEventListener('click',()=>open(button,order,sku,0,null));body.append(back);}
  }catch(e){if(sequence===comparisonSequence&&dialog.open&&e.name!=='AbortError'){body.replaceChildren();feedback.textContent='Fee evidence could not be loaded. Try again.';const retry=text('button',page>0?'Newest fee evidence':'Try again');retry.type='button';retry.addEventListener('click',()=>open(button,order,sku,0,null));body.append(retry);}}finally{if(sequence===comparisonSequence)body.setAttribute('aria-busy','false');}
 }
 // Tab is orthogonal to current order status/search/filters; no reset of those controls.
 const feeFilterSelected=new URL(location.href).searchParams.get('f_fee_variance')==='1';
 document.querySelectorAll('[data-fee-variance-tab]').forEach(a=>{const url=new URL(location.href),selected=url.searchParams.get('f_fee_variance')==='1';if(selected)url.searchParams.delete('f_fee_variance');else url.searchParams.set('f_fee_variance','1');url.searchParams.delete('page');url.searchParams.delete('goToPage');a.href=url.href;a.classList.toggle('active',selected);if(selected){a.setAttribute('aria-current','page');a.title='Clear fee variance filter';}});
 // Retain other filters even when the confirmed-zero optional tab is absent.
 if(feeFilterSelected)for(const link of document.querySelectorAll('.order-filters a:not([data-fee-variance-tab]):not([data-fee-variance-clear]),.sku-filter-bar a:not([data-fee-variance-tab]):not([data-fee-variance-clear])')){const target=new URL(link.href);target.searchParams.set('f_fee_variance','1');link.href=target.href;}
 const clearFeeFilter=link=>{const target=new URL(location.href);target.searchParams.delete('f_fee_variance');target.searchParams.delete('page');target.searchParams.delete('goToPage');link.href=target.href;};
 document.querySelectorAll('[data-fee-variance-clear]').forEach(clearFeeFilter);
 // Live Orders refresh can replace the link; update its target before native navigation.
 document.addEventListener('click',event=>{const link=event.target.closest('[data-fee-variance-clear]');if(link)clearFeeFilter(link);});
 // Add contextual actions without whole-row click handlers or altering table/export cells.
 document.querySelectorAll('[data-fee-order],[data-fee-sku]').forEach(row=>{const host=row.querySelector('.record-collaboration-actions,.order-actions,.item-product-copy,.sku-approved-product,.sku-record-actions')||row.querySelector('td:last-child');if(!host)return;const actions=text('span','','fee-row-actions');const b=text('button','Compare fees');b.type='button';b.hidden=true;b.addEventListener('click',()=>open(b,row.dataset.feeOrder,row.dataset.feeSku));actions.append(b);host.append(actions);});
 // One scoped request for the visible page, no per-row API or historical order scan.
 function hasFeeVariance(facts,order,sku){return facts.some(f=>f.seller_sku===sku&&(order?f.order_id===order&&['HIGHER','LOWER'].includes(f.state):Number(f.affected_orders)>0));}
 async function previews(){
  const orderMode=location.pathname==='/app/orders',rows=[...document.querySelectorAll(orderMode?'[data-fee-order]':'[data-fee-sku]')];
  const keys=[...new Set(rows.map(r=>orderMode?r.dataset.feeOrder:r.dataset.feeSku))].filter(Boolean).slice(0,200);if(!keys.length)return;
  const params=new URLSearchParams({kind:orderMode?'ORDER':'SKU'});keys.forEach(k=>params.append('key',k));
  try{const response=await fetch('/app/fee-variance/visible?'+params,{headers:{Accept:'application/json'}});if(!response.ok)return;const facts=await response.json();
   for(const row of rows){const actions=row.querySelector('.fee-row-actions');if(!actions)continue;actions.querySelector('button').hidden=!hasFeeVariance(facts,orderMode?row.dataset.feeOrder:null,row.dataset.feeSku);
    if(!orderMode){const info=facts.find(f=>f.seller_sku===row.dataset.feeSku);if(info&&Number(info.affected_orders)>0){actions.querySelector('button').hidden=false;const link=text('a',info.affected_orders+(Number(info.affected_orders)===1?' affected order':' affected orders'));const u=new URL('/app/orders',location.origin);u.searchParams.set('f_fee_variance','1');u.searchParams.set('f_sku',row.dataset.feeSku);u.searchParams.set('f_sku_op','equals');link.href=u.href;actions.prepend(link);}continue;}
    for(const r of facts.filter(f=>f.order_id===row.dataset.feeOrder&&f.seller_sku===row.dataset.feeSku)){
     if(['HIGHER','LOWER'].includes(r.state))actions.querySelector('button').hidden=false;
     if(r.state==='MATCHES')continue;
     if(!actions.querySelector('[data-fee-total]')){const scope=text('small',r.currency+' · total for '+r.quantity+(r.quantity===1?' unit':' units'),'fee-total-context');scope.dataset.feeTotal='true';actions.append(scope);}
     const value=text('span',(r.fee_type==='FBA'?'FBA':'Referral')+' '+(r.state==='MATCHES'?'No variance':delta(r)),r.state==='HIGHER'?'fee-higher':r.state==='LOWER'?'fee-lower':'');
     value.setAttribute('aria-label',(r.fee_type==='FBA'?'FBA':'Referral')+' '+(r.state==='HIGHER'?'charge higher by ':r.state==='LOWER'?'charge lower by ':'')+(r.difference==null?r.reason:money(String(r.difference).replace(/^-/,'') ,r.currency)+' for '+r.quantity+' units'));actions.prepend(value);if(r.approved_amount!=null&&!actions.querySelector('[data-fee-approved]')){const approved=text('small','Actual fees approved · future basis');approved.dataset.feeApproved='true';actions.append(approved);}
    }
   }
  }catch(error){/* Existing rows stay usable; explicit comparison reports any load error. */}
 }
 previews();
 // Approval is only exposed on an exact current order evidence action, never a representative SKU example.
 document.addEventListener('click',e=>{const button=e.target.closest('[data-fee-approval-id]');if(!button||button.disabled||!button.feeChoices)return;const items=button.feeChoices.map(i=>Object.freeze({comparison:i.comparison,expectedApproval:i.expectedApproval||null})),key=choiceKey(items);if(inFlight.has(key)){rowStatus(button.parentElement,inFlight.get(key).label+' · Saving actual fees…');return;}pending=Object.freeze({items:Object.freeze(items),request:crypto.randomUUID(),key,trigger:button,host:button.parentElement,label:recordLabel(button)});approval.querySelector('.fee-feedback').textContent='';approval.querySelector('footer [data-fee-close]').textContent='Cancel';approval.querySelector('[data-fee-approve]').disabled=false;approval.querySelector('[data-fee-approval-identity]').textContent=button.dataset.feeApprovalIdentity;approval.querySelector('[data-fee-approval-amount]').textContent=button.dataset.feeApprovalAmount;show(approval,button);});
 approval.querySelector('[data-fee-approve]').addEventListener('click',async e=>{const submission=pending;if(!submission||inFlight.has(submission.key))return;const b=e.currentTarget;inFlight.set(submission.key,submission);b.disabled=true;submission.trigger.disabled=true;approval.querySelector('footer [data-fee-close]').textContent='Close';approval.querySelector('.fee-feedback').textContent='Saving actual fees. Closing this window does not cancel the request.';rowStatus(submission.host,submission.label+' · Saving actual fees…');
  const csrf=document.querySelector('#buy-shipping-csrf');const headers={'Content-Type':'application/json'};if(csrf)headers[csrf.dataset.header||csrf.dataset.headerName]=csrf.value;
  const unconfirmed='The approval result could not be confirmed. Compare fees before retrying.';
  let rejection=false;
  try{const response=await fetch('/app/fee-variance/approve',{method:'POST',headers,body:JSON.stringify({items:submission.items,request:submission.request})});
   if(!response.ok){rejection=[400,401,403,404,409].includes(response.status);throw new Error('Approval unavailable');}
   const result=await response.json();if(!Array.isArray(result.id)||result.id.length!==submission.items.length||!result.id.every(id=>typeof id==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)))throw new Error('Approval response unavailable');
   completed.add(submission.key);matchingControls(submission.key).forEach(button=>button.remove());
   if(submission.host.isConnected&&!submission.host.querySelector('[data-fee-approved]')){const approved=text('small','Actual fees approved · future basis');approved.dataset.feeApproved='true';approved.setAttribute('role','status');approved.setAttribute('aria-label',submission.label+' · Actual fees approved · future basis');submission.host.append(approved);}rowStatus(submission.host,'');
   if(pending===submission){if(approval.open)close(approval);pending=null;}
  }catch(error){
   // A lost response can follow a committed write. Require a fresh read before another request.
   matchingControls(submission.key).forEach(button=>button.remove());const message=rejection?'Fee evidence changed or approval is unavailable. Compare again.':unconfirmed;rowStatus(submission.host,submission.label+' · '+message);
   if(pending===submission){if(approval.open){approval.querySelector('.fee-feedback').textContent=message;b.disabled=true;approval.querySelector('footer [data-fee-close]').textContent='Close';approval.querySelector('header [data-fee-close]')?.focus();}pending=null;}
  }finally{inFlight.delete(submission.key);}

 });
})();
