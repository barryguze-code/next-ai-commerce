/* Shared read-only comparison; prospective approval is a separate explicit action. */
(()=>{'use strict';
 const dialog=document.querySelector('#fee-comparison'),approval=document.querySelector('#fee-approval');if(!dialog||!approval)return;
 let trigger=null,pending=null,loading=false;const text=(tag,value,cls)=>{const n=document.createElement(tag);n.textContent=value;if(cls)n.className=cls;return n;};
 const date=v=>v?new Date(v).toLocaleString(): 'Unavailable';
 const money=(v,c,exact=false)=>v==null?'Unavailable':new Intl.NumberFormat(undefined,{style:'currency',currency:c,...(exact?{maximumFractionDigits:4}:{})}).format(v);
 // Derivation revisions of one immutable charge are not new economic differences.
 const economicKey=r=>[r.order_item_id,r.fee_type,r.currency,r.charge_id,r.expected_amount,r.actual_amount].map(v=>v??'').join('|');
 function earlierDifferences(rows){const seen=new Set();return rows.filter(r=>!r.current_revision&&!r.same_economic_as_current&&['HIGHER','LOWER'].includes(r.state)).filter(r=>{const key=economicKey(r);if(seen.has(key))return false;seen.add(key);return true;});}
 const delta=r=>r.state==='MATCHES'?'No variance':r.difference==null?r.reason:(r.state==='HIGHER'?'↑ +':'↓ −')+money(String(r.difference).replace(/^-/,'') ,r.currency);
 function close(d){d.close();document.body.classList.toggle('fee-overlay-open',!!document.querySelector('.fee-dialog[open]'));if(trigger?.isConnected)trigger.focus();}
 for(const d of [dialog,approval]){d.querySelectorAll('[data-fee-close]').forEach(b=>b.addEventListener('click',()=>close(d)));d.addEventListener('cancel',e=>{e.preventDefault();close(d);});}
 function show(d,button){trigger=button;d.showModal();document.body.classList.add('fee-overlay-open');d.querySelector('button')?.focus();}
 async function open(button,order,sku,page=0,snapshot=null){
  if(loading)return;loading=true;const body=dialog.querySelector('[data-fee-body]');body.replaceChildren(text('p','Loading fee evidence…'));dialog.querySelector('[data-fee-feedback]').textContent='';dialog.querySelector('[data-fee-identity]').textContent=order?'Order '+order:'SKU '+sku;if(!dialog.open)show(dialog,button);
  try{const qs=new URLSearchParams({order:order||'',sku:sku||'',page:String(page)});if(snapshot!=null)qs.set('snapshot',String(snapshot));const response=await fetch('/app/fee-variance?'+qs,{headers:{Accept:'application/json'}});if(!response.ok)throw new Error('Fee evidence could not be loaded. Try again.');const data=await response.json();body.replaceChildren();
   if(!data.rows.length){body.append(text('p','No comparable fee differences. Missing evidence remains unavailable.'));return;}
   const host=button.parentElement;host.querySelectorAll('[data-fee-approval-id]').forEach(n=>n.remove());
   const eligible=data.rows.filter(r=>order&&data.canApprove&&r.current_revision&&r.shipping_verified&&r.unit_actual!=null&&!r.already_approved&&['HIGHER','LOWER'].includes(r.state));
   if(eligible.length&&eligible.length<=2){const use=text('button','Use actual fees');use.type='button';use.dataset.feeApprovalId='group';
    use.feeChoices=eligible.map(r=>({comparison:r.id,expectedApproval:r.current_approval||null}));
    use.dataset.feeApprovalIdentity=(data.scope?.display_name||'Selected store')+' · '+(data.scope?.marketplace_identifier||'')+' · '+eligible[0].seller_sku+' · '+eligible[0].currency+' · FBA · Price '+money(eligible[0].unit_price,eligible[0].currency)+' · Shipping '+money(eligible[0].shipping,eligible[0].currency);
    use.dataset.feeApprovalIdentity+=' · Source order '+order+' · Effective when confirmed';
    use.dataset.feeApprovalAmount=eligible.map(r=>(r.fee_type==='FBA'?'FBA':'Referral')+': '+money(r.unit_expected,r.currency,true)+' → '+money(r.unit_actual,r.currency,true)+' per unit').join(' · ');
    host.append(use);
   }
   if(order&&data.canApprove&&!eligible.length&&data.rows.some(r=>r.current_revision&&!r.already_approved&&['HIGHER','LOWER'].includes(r.state))){const unavailable=text('button','Use actual fees');unavailable.type='button';unavailable.dataset.feeApprovalId='unavailable';unavailable.disabled=true;unavailable.title='Current price or shipping basis needs verification';unavailable.setAttribute('aria-label','Use actual fees unavailable: current price or shipping basis needs verification');host.append(unavailable);}
   if(order&&data.caseUrl&&!host.querySelector('[data-fee-case-link]')){const link=text('a','Open Seller Central case log');link.href=data.caseUrl;link.target='_blank';link.rel='noopener noreferrer';link.dataset.feeCaseLink='true';host.append(link);}
   const current=data.rows.filter(r=>r.current_revision),differences=current.filter(r=>['HIGHER','LOWER'].includes(r.state));
   const shown=differences.length?differences:current.filter(r=>r.state!=='MATCHES');
   if(!shown.length)body.append(text('p','No comparable fee differences.'));
   for(const r of shown){const section=text('section','','fee-detail');section.append(text('h3',(r.fee_type==='FBA'?'FBA':'Referral')+' · '+r.order_id),text('p',r.seller_sku+' · '+r.currency+' · total for '+r.quantity+(r.quantity===1?' unit':' units')+(r.current_revision?'':' · Earlier evidence')));
    const values=text('div','','fee-values');for(const [label,value] of [['Basis at order',money(r.expected_amount,r.currency)],['Charged',money(r.actual_amount,r.currency)],['Difference',delta(r)]]){const cell=text('div','');cell.append(text('span',label),text('strong',value,label==='Difference'?(r.state==='HIGHER'?'fee-higher':r.state==='LOWER'?'fee-lower':''):''));values.append(cell);}section.append(values,text('p','Change detected: '+date(r.boundary_at)+' · Charge received: '+date(r.charge_received_at),'fee-sources'));body.append(section);
   }
   const prior=earlierDifferences(data.rows);
   if(prior.length){const details=text('details','');details.append(text('summary','Earlier fee differences'));
    for(const r of prior)details.append(text('p',(r.fee_type==='FBA'?'FBA':'Referral')+' · '+delta(r)+' · Charge received '+date(r.charge_received_at)));
    body.append(details);
   }
   if(data.hasMore){const more=text('button','Older fee evidence');more.type='button';more.addEventListener('click',()=>open(button,order,sku,page+1,data.snapshot));body.append(text('p','Showing up to 100 evidence records on this page.'),more);}
   if(page>0){const back=text('button','Newest fee evidence');back.type='button';back.addEventListener('click',()=>open(button,order,sku,0,null));body.append(back);}
  }catch(e){body.replaceChildren(text('p',e.message));}finally{loading=false;}
 }
 // Tab is orthogonal to current order status/search/filters; no reset of those controls.
 document.querySelectorAll('[data-fee-variance-tab]').forEach(a=>{const url=new URL(location.href),selected=url.searchParams.get('f_fee_variance')==='1';if(selected)url.searchParams.delete('f_fee_variance');else url.searchParams.set('f_fee_variance','1');url.searchParams.delete('page');url.searchParams.delete('goToPage');a.href=url.href;if(selected){a.setAttribute('aria-current','page');a.title='Clear fee variance filter';for(const link of document.querySelectorAll('.order-filters a:not([data-fee-variance-tab]),.sku-filter-bar a:not([data-fee-variance-tab])')){const target=new URL(link.href);target.searchParams.set('f_fee_variance','1');link.href=target.href;}}});
 // Add contextual actions without whole-row click handlers or altering table/export cells.
 document.querySelectorAll('[data-fee-order],[data-fee-sku]').forEach(row=>{const host=row.querySelector('.record-collaboration-actions,.order-actions,.item-product-copy,.sku-approved-product,.sku-record-actions')||row.querySelector('td:last-child');if(!host)return;const actions=text('span','','fee-row-actions');const b=text('button','Compare fees');b.type='button';b.addEventListener('click',()=>open(b,row.dataset.feeOrder,row.dataset.feeSku));actions.append(b);host.append(actions);});
 // One scoped request for the visible page, no per-row API or historical order scan.
 async function previews(){
  const orderMode=location.pathname==='/app/orders',rows=[...document.querySelectorAll(orderMode?'[data-fee-order]':'[data-fee-sku]')];
  const keys=[...new Set(rows.map(r=>orderMode?r.dataset.feeOrder:r.dataset.feeSku))].filter(Boolean).slice(0,200);if(!keys.length)return;
  const params=new URLSearchParams({kind:orderMode?'ORDER':'SKU'});keys.forEach(k=>params.append('key',k));
  try{const response=await fetch('/app/fee-variance/visible?'+params,{headers:{Accept:'application/json'}});if(!response.ok)return;const facts=await response.json();
   for(const row of rows){const actions=row.querySelector('.fee-row-actions');if(!actions)continue;
    if(!orderMode){const info=facts.find(f=>f.seller_sku===row.dataset.feeSku);if(info){const link=text('a',info.affected_orders+(Number(info.affected_orders)===1?' affected order':' affected orders'));const u=new URL('/app/orders',location.origin);u.searchParams.set('f_fee_variance','1');u.searchParams.set('f_sku',row.dataset.feeSku);u.searchParams.set('f_sku_op','equals');link.href=u.href;actions.prepend(link);}continue;}
    for(const r of facts.filter(f=>f.order_id===row.dataset.feeOrder&&f.seller_sku===row.dataset.feeSku)){
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
 document.addEventListener('click',async e=>{const button=e.target.closest('[data-fee-approval-id]');if(!button)return;pending={items:button.feeChoices,request:crypto.randomUUID()};approval.querySelector('[data-fee-approval-identity]').textContent=button.dataset.feeApprovalIdentity;approval.querySelector('[data-fee-approval-amount]').textContent=button.dataset.feeApprovalAmount;show(approval,button);});
 approval.querySelector('[data-fee-approve]').addEventListener('click',async e=>{if(!pending)return;const b=e.currentTarget;b.disabled=true;const csrf=document.querySelector('#buy-shipping-csrf');const headers={'Content-Type':'application/json'};if(csrf)headers[csrf.dataset.header||csrf.dataset.headerName]=csrf.value;try{const response=await fetch('/app/fee-variance/approve',{method:'POST',headers,body:JSON.stringify(pending)});if(!response.ok)throw new Error('Fee evidence changed or approval is unavailable. Compare again.');const approved=text('small','Actual fees approved · future basis');approved.dataset.feeApproved='true';approved.setAttribute('role','status');const host=trigger?.parentElement;host?.append(approved);if(trigger)trigger.disabled=true;trigger=host?.querySelector('button:not([data-fee-approval-id])')||trigger;close(approval);pending=null;}catch(error){approval.querySelector('.fee-feedback').textContent=error.message;}finally{b.disabled=false;if(approval.open)b.focus();}});
})();
