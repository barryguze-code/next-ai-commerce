/* Purchasing plans are internal records; no vendor requests or stock movements. */
(()=>{
 'use strict';
 if(typeof document==='undefined')return;
 const rows=[...document.querySelectorAll('[data-replenishment-row]')],basket=new Map();
 let legacyZeroRows=false;
 const $=s=>document.querySelector(s),text=(tag,value)=>{const n=document.createElement(tag);n.textContent=value;return n;};
 const formatCases=value=>Number.isInteger(value)?String(value):value>0&&value<0.005?value.toFixed(8).replace(/0+$/,''):value.toFixed(2);
 const money=(value,currency='USD')=>new Intl.NumberFormat('en-US',{style:'currency',currency}).format(value);
 const scope=$('[data-basket-scope]')?.dataset.basketScope;
 const storageKey=scope?'replenishment-basket:v1:'+scope:null;
 let noticeTimer;function showNotice(message,transient=false){clearTimeout(noticeTimer);const notice=$('.rp-notice');if(!notice)return;notice.textContent=message;notice.hidden=!message;if(message&&transient)noticeTimer=setTimeout(()=>{notice.hidden=true;notice.textContent='';},5000);}
 function persistBasket(){try{if(storageKey)sessionStorage.setItem(storageKey,JSON.stringify([...basket.values()]));}catch{showNotice('Browser storage is unavailable. Keep this page open to retain your basket.');}}
 try{const saved=storageKey?JSON.parse(sessionStorage.getItem(storageKey)||'[]'):[];
  if(Array.isArray(saved))saved.forEach(item=>{if(item&&typeof item.id==='string'&&Number.isFinite(item.pack)&&item.pack>0&&Number.isSafeInteger(item.each)&&item.each>=0){if(item.each===0)legacyZeroRows=true;else basket.set(item.id,item);}});
 }catch{/* A damaged or unavailable browser cache must not prevent planning. */}
 const expanded=new Map();
 function syncDetails(){expanded.forEach(({parent,control,child,section})=>{
  if(parent.nextElementSibling!==control)parent.after(control);
  if(control.nextElementSibling!==child)control.after(child);
  const parentHidden=parent.hidden||parent.classList.contains('table-data-hidden')||parent.style.display==='none';
  if(control.hidden!==parentHidden)control.hidden=parentHidden;
  const hide=section.hidden||parentHidden;
  if(child.hidden!==hide)child.hidden=hide;
  const span=parent.closest('table').tHead.rows[0].cells.length;
  if(child.cells[0].colSpan!==span)child.cells[0].colSpan=span;
  if(control.cells[0].colSpan!==span)control.cells[0].colSpan=span;
 });}
 const body=rows[0]?.parentElement;
 if(body)new MutationObserver(syncDetails).observe(body,{childList:true,subtree:true,attributes:true,attributeFilter:['class','hidden','style']});
 function toggleSkus(id){
  const section=document.getElementById('rp-skus-'+id),parent=rows.find(row=>row.dataset.item===id);if(!section||!parent)return;
  if(!expanded.has(id)){
   const child=document.createElement('tr'),cell=document.createElement('td'),control=document.createElement('tr'),controlCell=document.createElement('td');
   child.className='rp-detail-row';control.className='rp-toggle-row';
   cell.colSpan=controlCell.colSpan=parent.cells.length;
   control.append(controlCell);controlCell.append(parent.querySelector('.rp-sku-toggle'));
   child.append(cell);cell.append(section);expanded.set(id,{parent,control,child,section});
  }
  section.hidden=!section.hidden;
  document.querySelectorAll('[data-skus-open]').forEach(button=>{if(button.dataset.skusOpen===id){button.setAttribute('aria-expanded',String(!section.hidden));button.setAttribute('aria-controls',section.id);button.setAttribute('aria-label',section.hidden?'Expand SKU rows':'Collapse SKU rows');button.setAttribute('title',section.hidden?'Expand SKU rows':'Collapse SKU rows');}});
  syncDetails();
 }
 function settings(section){if(section==='vendors'){document.dispatchEvent(new CustomEvent('rp:vendors-open'));return;}$('#rp-settings').showModal();window.NextAiStandardTables?.enhance();document.getElementById('rp-'+section)?.scrollIntoView({block:'start'});}
 const metaKey=storageKey?storageKey+':plan':null;
 const defaultPlanName=()=>{const d=new Date();return 'PO-'+[d.getMonth()+1,d.getDate(),d.getFullYear()%100].map(n=>String(n).padStart(2,'0')).join('')+'-Draft';};
 let plan={id:null,revision:0,name:defaultPlanName(),createdAt:null,status:'DRAFT'},busy=false;
 try{const saved=JSON.parse(sessionStorage.getItem(metaKey)||'null');if(saved&&typeof saved.name==='string'&&['DRAFT','SUBMITTED'].includes(saved.status)&&Number.isSafeInteger(saved.revision)&&saved.revision>=0)plan=saved;}catch{}
 if(!plan.id&&['Suggested PO','Purchase plan'].includes(plan.name))plan.name=defaultPlanName();
 const persistPlan=()=>{try{if(metaKey)sessionStorage.setItem(metaKey,JSON.stringify(plan));}catch{}};
 const initialKey=storageKey?storageKey+':suggested-initialized':null;
 let initialized=!!basket.size||!!plan.id;
 try{initialized=initialized||sessionStorage.getItem(initialKey)==='1';}catch{}
 // Current zero edits remove a line. Legacy placeholder rows are not an edited draft.
 if(legacyZeroRows&&!basket.size&&!plan.id)initialized=false;
 function markInitialized(){initialized=true;try{if(initialKey)sessionStorage.setItem(initialKey,'1');}catch{}}
 if(initialized)markInitialized();
 function rowItem(row){const d=row.dataset;return {id:d.item,name:d.name,code:d.code||'',image:d.image,vendor:d.vendor,vendorId:d.vendorId||null,upc:d.upc||'',currency:'USD',dc:d.dc,pack:Number(d.pack),each:0,cost:d.cost!=null&&d.cost!==''&&Number.isFinite(+d.cost)&&+d.cost>=0?+d.cost:null};}
 function syncPoRows(){for(const row of rows){const input=row.querySelector('[data-po-cases]');if(!input)continue;const item=basket.get(row.dataset.item),pack=item?.pack||Number(row.dataset.pack),each=item?.each||0;if(document.activeElement!==input)input.value=formatCases(each/pack);input.setCustomValidity('');input.disabled=busy||readOnly()||$('#rp-basket')?.dataset.planCanEdit==='false';const remove=row.querySelector('[data-po-remove]');if(remove)remove.disabled=input.disabled||each===0;const note=row.querySelector('[data-po-each]');if(note)note.textContent=each+' each · ';}}
 const quantityCueTimers=new WeakMap();
 function changePoQuantity(row,units){markInitialized();if(units===0)basket.delete(row.dataset.item);else{const item=basket.get(row.dataset.item)||rowItem(row);item.each=units;basket.set(item.id,item);}basketView();}
 const quantityFocusStart=new WeakMap();
 function confirmPoQuantity(input){window.NextAiTooltips?.notify(input,'Basket Updated');clearTimeout(quantityCueTimers.get(input));input.classList.add('rp-quantity-updated');quantityCueTimers.set(input,setTimeout(()=>input.classList.remove('rp-quantity-updated'),600));const status=$('[data-po-update-status]');if(status)status.textContent='Basket Updated';}
 document.addEventListener('focusin',event=>{const input=event.target.closest('[data-po-cases]');if(input)quantityFocusStart.set(input,basket.get(input.closest('[data-replenishment-row]').dataset.item)?.each||0);});
 document.addEventListener('focusout',event=>{const input=event.target.closest('[data-po-cases]');if(!input||input.disabled||busy||readOnly())return;const row=input.closest('[data-replenishment-row]'),value=Number(input.value),pack=basket.get(row.dataset.item)?.pack||Number(row.dataset.pack),units=Math.round(value*pack),start=quantityFocusStart.get(input);quantityFocusStart.delete(input);if(input.value===''||!Number.isFinite(value)||value<0||!Number.isSafeInteger(units)||units>100000000)return;if(start!==undefined&&start!==units)confirmPoQuantity(input);});


 async function startSuggestedPo(explicit=false){
  if(!$('#rp-basket')||busy||(!explicit&&initialized)||$('#rp-basket').dataset.planCanEdit==='false')return;
  if(explicit&&(basket.size||plan.id)){
   const accepted=window.NextAiConfirm?await window.NextAiConfirm({title:'Start a new suggested PO?',message:'Fill a new working draft with all positive suggestions from the latest forecast, across all pages. Your current unsaved basket changes will be replaced. Saved drafts stay available.',accept:'Start new PO'}):window.confirm('Start a new suggested PO? Unsaved basket changes will be replaced. Saved drafts stay available.');if(!accepted)return;
  }
  busy=true;controls();
  try{let result;for(let attempt=0;attempt<10;attempt++){result=await api('',undefined,'/app/inventory/replenishment/suggested-po');if(result.ready)break;showNotice('Preparing suggested PO…');if(attempt<9)await new Promise(resolve=>setTimeout(resolve,2000));}
   if(!result.ready)throw Error('Forecast is still preparing. Your draft is unchanged. Try New suggested PO again shortly.');
   if(!Array.isArray(result.items)||!result.items.every(i=>i.id&&Number.isFinite(i.pack)&&i.pack>0&&Number.isSafeInteger(i.each)&&i.each>0))throw Error('Suggestions could not be verified. Your current draft is unchanged.');
   basket.clear();result.items.forEach(i=>basket.set(i.id,i));plan={id:null,revision:0,name:defaultPlanName(),createdAt:null,status:'DRAFT'};markInitialized();showNotice('');basketView();feedback('');if(explicit)showNotice('Suggested PO ready · '+basket.size+' items.',true);
   if(explicit&&!$('#rp-basket').open)$('#rp-basket').showModal();
  }catch(error){showNotice(error.message);}
  finally{busy=false;controls();syncPoRows();}
 }
 const feedback=value=>{const target=$('[data-plan-feedback]');if(target)target.textContent=value;};
 const readOnly=()=>plan.status==='SUBMITTED';
 function controls(){
  syncPoRows();const newPo=$('[data-po-new]');if(newPo)newPo.disabled=busy;
  if(!$('[data-plan-submit]'))return;
  const vendors=new Set([...basket.values()].filter(i=>i.each>0).map(i=>i.vendorId||i.vendor));
  $('[data-plan-submit]').textContent=vendors.size>1?'Submit '+vendors.size+' POs':'Submit PO';
  for(const key of ['save','submit'])$('[data-plan-'+key+']').disabled=busy||readOnly()||!basket.size||$('#rp-basket').dataset.planCanEdit==='false';
  $('#rp-basket').querySelectorAll('input,button[data-close-dialog],button[data-plan-list],button[data-plan-history]').forEach(el=>el.disabled=busy||((el.matches('[data-plan-list],[data-plan-history]'))&&$('#rp-basket').dataset.planCanEdit==='false'));
  $('#rp-basket').querySelectorAll('.rp-basket-line input,.rp-basket-line button').forEach(el=>el.disabled=busy||readOnly());
 }
 let manualGeneration=0,manualTimer;
 function manualPicture(item){const frame=text('span','');frame.className='rp-po-picture';const fallback=()=>{frame.replaceChildren(text('span',item.name.trim().split(/\s+/).slice(0,2).map(w=>w[0]).join('').toUpperCase()));};if(item.image){const image=document.createElement('img');image.src=item.image;image.alt='';image.onerror=fallback;frame.append(image);}else fallback();return frame;}
 function quantityField(line,item,label){const wrap=text('label',label),input=document.createElement('input');input.type='number';input.min='0';if(label==='Each')input.max='100000000';input.step=label==='Cases'?'any':'1';input.setAttribute('aria-label',label+' for '+item.name);wrap.append(input);line.append(wrap);return input;}
 function focusPoItem(id){const line=[...document.querySelectorAll('.rp-basket-line[data-po-item]')].find(e=>e.dataset.poItem===id);line?.querySelector('input')?.focus();}
 function productAdder(host){
  const generation=++manualGeneration;clearTimeout(manualTimer);
  if(readOnly()||$('#rp-basket').dataset.planCanEdit==='false')return;
  const wrap=text('div','');wrap.className='rp-po-add';
  const trigger=text('button','');trigger.type='button';trigger.className='rp-po-add-trigger';const plus=text('span','+');plus.setAttribute('aria-hidden','true');trigger.append(plus,text('span','Add item to this PO'));wrap.append(trigger);
  const panel=text('div','');panel.className='rp-po-add-panel';panel.hidden=true;
  const controls=text('div','');controls.className='rp-po-add-controls';const label=text('label','Add item to this PO'),search=document.createElement('input');search.type='search';search.placeholder='Search product, brand, UPC or item code';label.append(search);controls.append(label);panel.append(controls);
  const results=text('div',''),message=text('p','');message.className='cell-note';message.setAttribute('role','status');results.className='rp-po-product-results';panel.append(results,message);
  const cancel=text('button','Cancel');cancel.type='button';cancel.className='secondary-button';controls.append(cancel);wrap.append(panel);host.append(wrap);
  const current=()=>generation===manualGeneration&&$('#rp-basket').open&&!readOnly();
  trigger.onclick=()=>{if(busy)return;panel.hidden=false;trigger.hidden=true;search.focus();};
  cancel.onclick=()=>{++request;clearTimeout(manualTimer);panel.hidden=true;trigger.hidden=false;results.replaceChildren();search.value='';message.textContent='';trigger.focus();};
  let request=0;
  search.oninput=()=>{clearTimeout(manualTimer);const version=++request,q=search.value.trim();results.replaceChildren();message.textContent=q.length<2?'':'Searching…';if(q.length<2)return;
   manualTimer=setTimeout(async()=>{try{const choices=await api('/catalogue?'+new URLSearchParams({q,vendor:$('[data-plan-vendor]').value}));if(!current()||version!==request||panel.hidden)return;message.textContent=choices.length===20?'Refine your search to find more products.':choices.length?'':'No account products match.';
    choices.forEach(choice=>{const item={...choice.item,image:choice.image,brand:choice.brand,manualProduct:true};const pick=text('button','');pick.type='button';pick.className='rp-po-product-option';const image=manualPicture(item);const copy=text('span','');copy.append(text('strong',item.name),text('small',[item.brand,item.code,item.vendor,item.pack+' / case'].filter(Boolean).join(' · ')));pick.append(image,copy);pick.onclick=()=>stage(item);results.append(pick);});
   }catch(e){if(current()&&version===request)message.textContent=e.message;}},200);
  };
  function stage(item){
   if(basket.has(item.id)){feedback('');cancel.onclick();const duplicate=text('span','Already in this PO.');duplicate.className='rp-po-add-status';duplicate.setAttribute('role','status');wrap.append(duplicate);focusPoItem(item.id);return;}
   results.replaceChildren();message.textContent='';const line=text('div','');line.className='rp-basket-line';line.append(manualPicture(item));const name=text('div',item.name);name.className='rp-basket-product';name.append(text('small',[item.brand,item.code,item.vendor,item.pack+' / case'].filter(Boolean).join(' · ')));line.append(name);
   const cases=quantityField(line,item,'Cases'),each=quantityField(line,item,'Each'),cost=text('strong',item.cost==null?'Cost needed':'');line.append(cost);
   const update=isCases=>{const input=isCases?cases:each,value=Number(input.value),units=isCases?Math.round(value*item.pack):value;if(input.value===''||!Number.isSafeInteger(units)||units<=0||units>100000000){input.setCustomValidity('Enter a positive whole item quantity.');return null;}cases.setCustomValidity('');each.setCustomValidity('');if(isCases)each.value=units;else cases.value=formatCases(units/item.pack);cost.textContent=item.cost==null?'Cost needed':money(units*item.cost,item.currency||'USD');return units;};cases.oninput=()=>update(true);each.oninput=()=>update(false);
   const add=text('button','Add to PO');add.type='button';add.className='primary-button';add.onclick=()=>{if(busy||readOnly()||!current())return;if(!cases.reportValidity()||!each.reportValidity())return;const units=update(false);if(units===null){each.reportValidity();return;}if(basket.has(item.id)){feedback('');cancel.onclick();const duplicate=text('span','Already in this PO.');duplicate.className='rp-po-add-status';duplicate.setAttribute('role','status');wrap.append(duplicate);focusPoItem(item.id);return;}const previous=[...basket.values()];basket.clear();basket.set(item.id,{...item,each:units});previous.forEach(i=>basket.set(i.id,i));markInitialized();basketView();feedback('');const announcement=text('span','Product added to PO.');announcement.className='rp-po-announcement';announcement.setAttribute('role','status');host.append(announcement);focusPoItem(item.id);};line.classList.add('rp-po-selected');line.append(add);results.append(line);cases.focus();
  }
 }
 function basketView(){
  const host=$('[data-basket-body]');if(!host)return;host.replaceChildren();productAdder(host);
  if(!basket.size)host.append(text('p','Your basket is empty.'));
  const name=$('[data-plan-name]');name.value=plan.name;name.readOnly=true;name.tabIndex=-1;name.hidden=true;const display=$('[data-plan-display]');if(display){display.textContent=plan.name;display.hidden=false;}$('[data-plan-rename]').hidden=readOnly()||$('#rp-basket').dataset.planCanEdit==='false';
  $('[data-plan-created]').hidden=!plan.createdAt;
  $('[data-plan-created]').textContent=plan.createdAt?'Created '+new Date(plan.createdAt).toLocaleString()+(readOnly()?' · Submitted':''):'';
  const choice=$('[data-plan-vendor]'),selected=choice.value;choice.replaceChildren(new Option('All vendors',''));
  const vendors=[...new Set([...basket.values()].map(i=>i.vendor))];vendors.forEach(v=>{const option=new Option(v,v);option.dataset.choiceNoEdit=String(![...basket.values()].some(i=>i.vendor===v&&i.vendorId));choice.add(option)});choice.value=vendors.includes(selected)?selected:'';
  const groups=new Map();basket.forEach(item=>{if(choice.value&&choice.value!==item.vendor)return;const key=item.vendor+' · '+(item.dc?'DC '+item.dc:'No DC')+' · '+(item.currency||'USD');if(!groups.has(key))groups.set(key,[]);groups.get(key).push(item);});
  groups.forEach((items,vendor)=>{
   const symbol=new Intl.NumberFormat('en-US',{style:'currency',currency:items[0].currency||'USD'}).formatToParts(0).find(p=>p.type==='currency').value;const heading=text('h3',items[0].vendor+(items[0].dc?' · DC '+items[0].dc:'')+' · '+symbol);host.append(heading);
   const summary=text('p','');summary.className='rp-basket-total';
   const updateSummary=()=>{const known=items.filter(i=>i.cost!=null);summary.textContent='Subtotal: '+money(known.reduce((a,i)=>a+i.each*i.cost,0),items[0].currency||'USD')+(known.length<items.length?' · Cost needed':'')+' · Before tax and freight';};
   items.forEach(item=>{
    const line=text('div','');line.className='rp-basket-line';line.dataset.poItem=item.id;
    if(item.manualProduct)line.append(manualPicture(item));else{const image=document.createElement('img');image.src=item.image||'/app/catalog/products/'+item.id+'/image';image.alt='';image.loading='lazy';line.append(image);}
    const name=text('div',item.name);name.className='rp-basket-product';name.append(text('small',item.code+' · '+item.pack+' / case'));line.append(name);
    const cases=quantityField(line,item,'Cases'),each=quantityField(line,item,'Each'),cost=text('strong','');line.append(cost);
    const sync=()=>{cases.setCustomValidity('');each.setCustomValidity('');cases.value=formatCases(item.each/item.pack);cases.title=item.each/item.pack<0.000001?'Less than 0.000001 cases; see the exact Each quantity.':'';each.value=item.each;cost.textContent=item.cost==null?'Cost needed':money(item.each*item.cost,item.currency||'USD');updateSummary();};sync();
    const change=(input,isCases)=>{const value=Number(input.value),units=isCases?Math.round(value*item.pack):value;if(input.value===''||!Number.isSafeInteger(units)||units<0||units>100000000){input.setCustomValidity('Enter a nonnegative whole quantity.');input.reportValidity();return;}input.setCustomValidity('');markInitialized();if(units===0){basket.delete(item.id);basketView();return;}item.each=units;persistBasket();sync();syncPoRows();};
    cases.onchange=()=>change(cases,true);each.onchange=()=>change(each,false);
    const remove=text('button','Remove');remove.type='button';remove.className='rp-text-button';remove.setAttribute('aria-label','Remove '+item.name);remove.onclick=()=>{markInitialized();basket.delete(item.id);basketView();};line.append(remove);host.append(line);
   });
   updateSummary();host.append(summary);
  });
  if(readOnly()&&plan.pos?.length)host.append(text('p',plan.pos.map(p=>p.po_number).join(' · ')));
  $('[data-basket-count]').textContent=basket.size;controls();persistBasket();persistPlan();window.NextAiPlatformControls?.enhance($('#rp-basket'));
 }
 async function api(path='',body,base='/app/inventory/replenishment/plans'){
  const headers={Accept:'application/json','X-Workspace-Account':scope.split(':')[0]},abort=new AbortController(),timer=setTimeout(()=>abort.abort(),20000);
  if(body){headers['Content-Type']='application/json';const token=$('[data-plan-csrf] input');if(token)headers['X-CSRF-TOKEN']=token.value;}
  try{const response=await fetch(base+path,{method:body?'POST':'GET',headers,body:body?JSON.stringify(body):undefined,signal:abort.signal,cache:'no-store'});const value=await response.json().catch(()=>{throw Error('Your session ended or the response was incomplete. Your basket is retained. Sign in and retry.');});if(!response.ok)throw Error(value.detail||value.message||'Could not save. Your basket is unchanged.');return value;}
  catch(e){if(e.name==='AbortError')throw Error('The response timed out. Your basket is retained; retry the same save safely.');throw e;}finally{clearTimeout(timer);}
 }
 async function savePlan(submit){
  if(busy||readOnly()||!basket.size)return;
  for(const input of document.querySelectorAll('[data-po-cases],#rp-basket .rp-basket-line input'))if(!input.reportValidity()){feedback('Correct the quantity before saving.');return;}
  const name=$('[data-plan-name]');if(!name.value.trim()){name.focus();feedback('Enter a plan name.');return;}
  plan.name=name.value.trim();if(!plan.id)plan.id=crypto.randomUUID();persistPlan();busy=true;controls();feedback('Saving…');
  try{let termsRevision=null;
   if(submit){const quote=await api('/quote',[...basket.values()].map(i=>({id:i.id,each:i.each})));if(!Array.isArray(quote.lines)||!quote.revision)throw Error('Buying terms could not be verified. Your basket is retained.');
    const changed=quote.lines.some(i=>{const old=basket.get(i.id);return !old||String(old.vendorId||'')!==String(i.vendorId||'')||Number(old.cost)!==Number(i.cost)||(old.cost==null)!==(i.cost==null)||old.pack!==i.pack||(old.currency||'USD')!==i.currency;});
    quote.lines.forEach(i=>basket.set(i.id,i));termsRevision=quote.revision;basketView();if(changed){feedback('Buying terms refreshed. Review the vendor, quantities and costs, then submit again.');return;}
   }
   const result=await api(submit?'/submit':'',{termsRevision,id:plan.id,revision:plan.revision,name:plan.name,lines:[...basket.values()].map(i=>({id:i.id,each:i.each}))});
   markInitialized();if(result.id!==plan.id||!Number.isInteger(result.revision)||result.revision<1||result.status!==(submit?'SUBMITTED':'DRAFT')||!Array.isArray(result.lines)||result.lines.length!==basket.size||(submit&&(!Array.isArray(result.pos)||result.pos.length!==new Set(result.lines.map(i=>i.vendorId)).size)))throw Error('Save confirmation was incomplete. Your basket is retained; retry safely.');
   basket.clear();plan={id:null,revision:0,name:defaultPlanName(),createdAt:null,status:'DRAFT'};persistBasket();persistPlan();basketView();feedback('');$('#rp-basket').close();
   showNotice(submit?'PO submitted internally. Nothing sent to vendors.':'Draft saved. Open it from Saved drafts.',true);
  }catch(error){feedback(error.message);}finally{busy=false;controls();}
 }
 async function savedPlans(status){
  const host=$('[data-plan-saved-list]');host.replaceChildren(text('p','Loading…'));$('#rp-saved-title').textContent=status==='DRAFT'?'Saved drafts':'Submitted POs';$('#rp-saved-plans').showModal();
  try{const values=await api('?status='+status);host.replaceChildren();if(!values.length)host.append(text('p',status==='DRAFT'?'No saved drafts.':'No submitted POs.'));
   values.forEach(value=>{const row=text('div','');row.className='rp-saved-plan';const copy=text('div','');copy.append(text('strong',value.name),text('small','Created '+new Date(value.created_at).toLocaleString()+' · '+value.vendors+(Number(value.vendors)===1?' vendor · ':' vendors · ')+value.items+(Number(value.items)===1?' item':' items')));const open=text('button','Open');open.type='button';open.className='secondary-button';open.onclick=async()=>{
    if(busy)return;open.disabled=true;
    try{if(basket.size&&!(await (window.NextAiConfirm?window.NextAiConfirm({title:'Replace current basket?',message:'Your current basket will be replaced by this saved plan.',accept:'Open plan'}):Promise.resolve(window.confirm('Replace current basket?')))))return;
     const saved=await api('/'+value.id);if(saved.id!==value.id||!['DRAFT','SUBMITTED'].includes(saved.status)||!Array.isArray(saved.lines)||!saved.lines.every(i=>typeof i.id==='string'&&Number.isFinite(i.pack)&&i.pack>0&&Number.isSafeInteger(i.each)&&i.each>0))throw Error('Saved plan response was incomplete. Your current basket is retained.');markInitialized();plan={id:saved.id,name:saved.name,status:saved.status,revision:saved.revision,createdAt:saved.createdAt,pos:saved.pos};basket.clear();saved.lines.forEach(i=>basket.set(i.id,i));persistPlan();basketView();feedback(saved.status==='SUBMITTED'?'Submitted POs are read-only.':'Catalogue costs are refreshed when this draft is saved.');$('#rp-saved-plans').close();
    }catch(e){host.prepend(text('p',e.message));}finally{open.disabled=false;}
   };row.append(copy,open);
   if(status==='DRAFT'){const remove=text('button','Delete');remove.type='button';remove.className='rp-text-button rp-delete-draft';remove.setAttribute('aria-label','Delete draft '+value.name);remove.onclick=async()=>{
    if(busy)return;const accepted=window.NextAiConfirm?await window.NextAiConfirm({title:'Delete draft?',message:'Delete “'+value.name+'”? This cannot be undone. Your working basket stays unchanged.',accept:'Delete draft'}):window.confirm('Delete draft “'+value.name+'”? This cannot be undone.');if(!accepted)return;
    busy=true;open.disabled=remove.disabled=true;controls();
    try{const result=await api('/'+value.id+'/delete',{revision:value.revision});if(result.id!==value.id||result.deleted!==true)throw Error('Deletion was not confirmed. Reopen Saved drafts to check.');if(plan.id===value.id){plan={...plan,id:null,revision:0,createdAt:null,status:'DRAFT'};persistPlan();basketView();}row.remove();if(!host.querySelector('.rp-saved-plan'))host.replaceChildren(text('p','No saved drafts.'));}
    catch(e){const error=text('p',e.message);error.setAttribute('role','alert');host.prepend(error);}
    finally{busy=false;open.disabled=remove.disabled=false;controls();}
   };row.append(remove);}host.append(row);});
  }catch(e){host.replaceChildren(text('p',e.message));}
 }
 function exportBasket(){
  const cell=value=>{let s=value==null?'':String(value);if(/^[\s]*[=+@-]/.test(s)||/^[\t\r]/.test(s))s="'"+s;return '"'+s.replaceAll('"','""')+'"';};
  const selected=$('[data-plan-vendor]').value,items=[...basket.values()].filter(i=>!selected||i.vendor===selected);
  const data=[['Plan','Vendor','Vendor item code','UPC / EAN','Product','Units per case','Cases','Each','Unit cost','Currency','Line total'],...items.map(i=>[plan.name,i.vendor,i.code,i.upc||'',i.name,i.pack,formatCases(i.each/i.pack),i.each,i.cost??'',i.currency||'USD',i.cost==null?'':(i.each*i.cost).toFixed(2)])].map(row=>row.map(cell).join(',')).join('\r\n')+'\r\n';
  const url=URL.createObjectURL(new Blob(['\uFEFF'+data],{type:'text/csv;charset=utf-8'})),link=document.createElement('a');link.href=url;link.download='purchase-plan.csv';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
 }
 $('[data-plan-vendor]')?.addEventListener('change',basketView);
 document.addEventListener('rp:vendors-updated',event=>{const choice=$('[data-plan-vendor]'),selected=choice.value;let replacement=selected;for(const vendor of event.detail.vendors){for(const item of basket.values())if(item.vendorId===vendor.id){if(item.vendor===selected)replacement=vendor.name;item.vendor=vendor.name;}document.querySelectorAll('#rp-vendors tr[data-picture-id]').forEach(row=>{if(row.dataset.pictureId===vendor.id)row.querySelector('.rp-vendor-identity strong').textContent=vendor.name;});}persistBasket();basketView();choice.value=[...choice.options].some(o=>o.value===replacement)?replacement:'';choice.dispatchEvent(new Event('platform-choice-sync'));basketView();});
 $('[data-plan-save]')?.addEventListener('click',()=>savePlan(false));$('[data-plan-submit]')?.addEventListener('click',()=>savePlan(true));
 $('[data-plan-export]')?.addEventListener('click',exportBasket);$('[data-plan-list]')?.addEventListener('click',()=>savedPlans('DRAFT'));$('[data-plan-history]')?.addEventListener('click',()=>savedPlans('SUBMITTED'));
 $('[data-plan-name]')?.addEventListener('input',event=>{plan.name=event.target.value;persistPlan();});
 let titleBeforeEdit;
 $('[data-plan-rename]')?.addEventListener('click',()=>{const name=$('[data-plan-name]');titleBeforeEdit=name.value;name.hidden=false;$('[data-plan-display]')?.setAttribute('hidden','');name.readOnly=false;name.tabIndex=0;name.focus();name.select();});
 $('[data-plan-name]')?.addEventListener('blur',event=>{event.target.readOnly=true;event.target.tabIndex=-1;event.target.hidden=true;const display=$('[data-plan-display]');if(display){display.textContent=event.target.value;display.hidden=false;}});
 $('[data-plan-name]')?.addEventListener('keydown',event=>{if(event.key==='Enter'){event.preventDefault();event.target.blur();}else if(event.key==='Escape'&&!event.target.readOnly){event.preventDefault();event.stopPropagation();event.target.value=titleBeforeEdit;plan.name=titleBeforeEdit;persistPlan();event.target.blur();}});
 $('#rp-basket')?.addEventListener('cancel',event=>{if(busy)event.preventDefault();});
 document.addEventListener('submit',async event=>{
  const form=event.target.closest('.rp-case-edit form');if(!form)return;
  event.preventDefault();const row=form.closest('[data-replenishment-row]'),button=form.querySelector('[type="submit"]');
  if(button.disabled)return;
  let message=form.querySelector('[role="status"]');if(!message){message=text('small','');message.setAttribute('role','status');form.append(message);}
  button.disabled=true;message.textContent='Saving…';
  try{
   const data=new FormData(form);data.set('inline','true');
   const response=await fetch(form.action,{method:'POST',body:data,headers:{Accept:'application/json'}});
   if(!response.ok)throw new Error('Save failed');
   const result=await response.json(),pack=Number(result.pack);
   if(!Number.isFinite(pack)||pack<=0)throw new Error('Invalid case size');
   row.dataset.pack=String(pack);
   form.querySelector('[name="units"]').value=String(pack);
   form.closest('details').querySelector('summary span').textContent=pack+' / case';
   const item=basket.get(row.dataset.item);if(item){item.pack=pack;persistBasket();}
   // Preserve ordered eaches while displaying the revised account case pack.
   if(Number.isFinite(result.cases)&&Number.isFinite(result.each)){
    row.dataset.cases=String(result.cases);
    syncPoRows();
   }
   message.textContent='Case size saved. Basket quantities are unchanged.';
  }catch{message.textContent='Could not save case size. Your basket and table position are unchanged. Please retry.';}
  finally{button.disabled=false;}
 });
 if($('[data-basket-count]'))basketView();
 window.addEventListener('pageshow',event=>{
  if(!event.persisted||!storageKey)return;
  try{const saved=JSON.parse(sessionStorage.getItem(storageKey)||'[]');if(!Array.isArray(saved))return;const savedPlan=JSON.parse(sessionStorage.getItem(metaKey)||'null');if(savedPlan)plan=savedPlan;
   basket.clear();saved.forEach(item=>{if(item&&typeof item.id==='string'&&Number.isFinite(item.pack)&&item.pack>0&&Number.isSafeInteger(item.each)&&item.each>=0)basket.set(item.id,item);});basketView();
  }catch{/* Keep the currently visible basket when browser storage is unavailable. */}
 });
 document.addEventListener('click',event=>{
  const open=event.target.closest('[data-skus-open]');if(open){toggleSkus(open.dataset.skusOpen);return;}
  const skuTable=event.target.closest('.rp-sku-details');
  if(skuTable&&!event.target.closest('a,button,input,select,textarea')){toggleSkus(skuTable.id.slice('rp-skus-'.length));return;}
  if(event.target.closest('[data-close-dialog]')&&!busy)event.target.closest('dialog')?.close();
  const setting=event.target.closest('[data-settings-open]');if(setting)settings(setting.dataset.settingsOpen);
  const removePo=event.target.closest('[data-po-remove]');if(removePo&&!busy&&!readOnly()){window.NextAiTooltips?.hide();changePoQuantity(removePo.closest('[data-replenishment-row]'),0);}
  if(event.target.closest('[data-po-new]'))startSuggestedPo(true);
  if(event.target.closest('[data-basket-open]')){basketView();$('#rp-basket').showModal();}

 });
 function applyPoCases(event){const input=event.target.closest('[data-po-cases]');if(!input||input.disabled||busy||readOnly())return;const row=input.closest('[data-replenishment-row]'),pack=basket.get(row.dataset.item)?.pack||Number(row.dataset.pack),value=Number(input.value),units=Math.round(value*pack);if(input.value===''||!Number.isFinite(value)||value<0||!Number.isSafeInteger(units)||units>100000000){if(event.type==='change'){input.setCustomValidity('Enter a nonnegative quantity up to 100,000,000 each.');input.reportValidity();}return;}input.setCustomValidity('');changePoQuantity(row,units);if(event.type==='change')input.value=formatCases(units/pack);}
 document.addEventListener('input',applyPoCases);
 document.addEventListener('change',applyPoCases);
 document.addEventListener('keydown',event=>{
  const header=event.target.closest('.rp-sku-head[data-skus-open]');
  if(header&&(event.key==='Enter'||event.key===' ')){event.preventDefault();toggleSkus(header.dataset.skusOpen);}
 });
 if(location.hash==='#vendors')settings('vendors');
 // Wait for shared table setup, then show every item's picture-free SKU lines.
 const openAll=()=>rows.forEach(row=>{if(document.getElementById('rp-skus-'+row.dataset.item)?.hidden)toggleSkus(row.dataset.item);});
 const refreshForm=document.querySelector('form[action$="/replenishment/refresh"]'),syncButton=refreshForm?.querySelector('[data-rp-sync]'),refreshCommand=document.querySelector('[data-rp-refresh]');
 refreshCommand?.addEventListener('click',()=>{if(syncButton&&!syncButton.disabled)refreshForm.requestSubmit(syncButton);});
 refreshForm?.addEventListener('submit',event=>{if(refreshForm.dataset.submitting){event.preventDefault();return;}refreshForm.dataset.submitting='true';refreshForm.setAttribute('aria-busy','true');requestAnimationFrame(()=>{syncButton.disabled=true;if(refreshCommand)refreshCommand.disabled=true;syncButton.querySelector('[data-sync-label]').textContent='Preparing…';});});
 const enableRefresh=label=>{if(syncButton){syncButton.disabled=false;syncButton.querySelector('[data-sync-label]').textContent=label;}if(refreshCommand)refreshCommand.disabled=false;};
 const forecastRoot=document.querySelector('[data-forecast-pending]'),viewKey=scope?'replenishment-view:v1:'+scope:null;
 const rememberView=()=>{if(!viewKey)return;try{sessionStorage.setItem(viewKey,JSON.stringify({url:location.pathname+location.search,y:scrollY,closed:rows.filter(row=>document.getElementById('rp-skus-'+row.dataset.item)?.hidden).map(row=>row.dataset.item)}));}catch{}};
 document.querySelector('#thread-conversation-start')?.addEventListener('submit',rememberView);
 document.addEventListener('table:search-navigation',event=>{rememberView();if(!viewKey)return;try{const saved=JSON.parse(sessionStorage.getItem(viewKey));saved.url=event.detail.url;sessionStorage.setItem(viewKey,JSON.stringify(saved));}catch{}});
 function restoreView(){if(!viewKey)return;try{const saved=JSON.parse(sessionStorage.getItem(viewKey)||'null');sessionStorage.removeItem(viewKey);if(!saved||saved.url!==location.pathname+location.search)return;for(const id of saved.closed||[]){const section=document.getElementById('rp-skus-'+id);if(section&&!section.hidden)toggleSkus(id);}requestAnimationFrame(()=>window.scrollTo(0,saved.y||0));}catch{}}
 let forecastStopped=false;window.addEventListener('pagehide',()=>forecastStopped=true);window.addEventListener('pageshow',event=>{if(event.persisted&&forecastRoot?.dataset.forecastPending==='true'){forecastStopped=false;checkForecast();}});
 async function checkForecast(){
  if(forecastStopped)return;
  try{const response=await fetch('/app/inventory/replenishment/forecast-state',{headers:{Accept:'application/json'},cache:'no-store',signal:AbortSignal.timeout(10000)});if(!response.ok)throw Error();const state=await response.json();
   if(state.failed){enableRefresh('Retry forecast');return;}
   if(state.ready&&!state.pending&&!busy&&!document.querySelector('dialog[open]')&&!document.activeElement?.matches('input,textarea,select')){rememberView();location.reload();return;}
  }catch{enableRefresh('Sync now');return;}
  setTimeout(checkForecast,2000);
 }
 const ready=()=>{const prepared=$('[data-forecast-time]');if(prepared){const date=new Date(prepared.dateTime);if(Number.isFinite(date.getTime()))prepared.textContent='Prepared '+new Intl.DateTimeFormat(undefined,{month:'short',day:'numeric',hour:'numeric',minute:'2-digit'}).format(date);}openAll();restoreView();startSuggestedPo();if(forecastRoot?.dataset.forecastPending==='true'&&forecastRoot.dataset.forecastFailed!=='true')setTimeout(checkForecast,2000);};
 if(document.readyState==='complete')ready();else window.addEventListener('load',ready,{once:true});
})();
