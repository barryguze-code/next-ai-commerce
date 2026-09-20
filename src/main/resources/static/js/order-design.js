(()=>{
 const base='/images/platform/';
 function icon(name){const vector=['05-unlocked','06-locked','01-pending','02-unshipped-truck','03-waiting-for-pickup','04-shipped','collaboration','edit-image','human-ai'].includes(name);if(!vector&&window.NextAiIcons)return window.NextAiIcons.create(name);const image=document.createElement('img');image.src=vector?base+name+'.svg':base+'table/'+name+'.png?v=20260919-13';image.alt='';image.decoding='async';image.className='platform-icon';return image;}
 function statusIcon(name){const frame=document.createElement('span');frame.className='order-status-art';frame.dataset.asset=name;frame.setAttribute('aria-hidden','true');frame.append(icon(name));return frame;}
 function filterButton(value){const b=document.createElement('button');b.type='button';b.className='order-identifier-lock';const active=new URL(location.href).searchParams.get('q')===value;b.setAttribute('aria-pressed',String(active));b.setAttribute('aria-label',(active?'Clear filter for ':'Filter orders by ')+value);b.title=active?'Filter locked — click to clear':'Not locked — click to filter';b.append(icon(active?'06-locked':'05-unlocked'));b.onclick=()=>{const url=new URL(location.href);if(active)url.searchParams.delete('q');else url.searchParams.set('q',value);url.searchParams.delete('page');url.searchParams.delete('goToPage');location.assign(url);};return b;}
 function copyButton(value){const b=document.createElement('button');b.type='button';b.dataset.orderCopy=value;b.textContent=value;b.className='order-copy-value';b.setAttribute('aria-label','Copy '+value);return b;}
 function init(){
  document.querySelectorAll('.orders-workspace .order-summary strong:not([data-currency-ready])').forEach(el=>{
   el.dataset.currencyReady='true';
   el.textContent=el.textContent.replace(/\b(USD|CAD|AUD|EUR|GBP|JPY|MXN)\s+([\d,]+\.\d{2})/g,(_,currency,amount)=>new Intl.NumberFormat('en-US',{style:'currency',currency}).format(Number(amount.replaceAll(',',''))));
  });
  document.querySelectorAll('.order-item').forEach(row=>{
   const stage=row.querySelector('[data-picture-actions]'),order=row.closest('.order-row');
   if(stage&&!row.dataset.alertsReady){
    row.dataset.alertsReady='true';const reasons=[];
    if(row.dataset.unmapped==='true')reasons.push(['Unmapped','Connect this SKU to catalogue items.']);
    if(row.dataset.stockShortage==='true')reasons.push(['Stock Shortage','Review available inventory for this order.']);
    // Buy Box Lost remains a review filter, not an actionable alert yet.
    stage.dataset.actionRequired=String(reasons.length>0);stage.dataset.actionSummary=reasons.map(([name,help])=>name+' — '+help).join(' ');
    if(reasons.length){row.classList.add('order-needs-attention');order?.classList.add('order-has-attention');const tags=document.createElement('div');tags.className='order-reason-tags';reasons.forEach(([name,help])=>{const tag=document.createElement('span');tag.textContent=name;tag.title=help;tags.append(tag);});row.querySelector('.item-order-reference')?.append(tags);}
   }
   const product=row.querySelector('.item-product-copy');
   if(product&&!product.dataset.designReady){product.dataset.designReady='true';
    const title=product.querySelector(':scope > strong');if(title){title.dataset.orderCopy=title.textContent;title.tabIndex=0;title.setAttribute('role','button');title.setAttribute('aria-label','Copy product title: '+title.textContent);}
    const sku=product.querySelector('.order-sku-copy');if(sku){const line=document.createElement('div');line.className='order-identifier-line';sku.before(line);line.append(sku,filterButton(sku.dataset.copySku));const link=row.querySelector('.order-marketplace-links a');if(link){const a=link.cloneNode(true);a.className='order-identifier-market';line.prepend(a);}}
   }
   row.querySelectorAll('.order-reference-options > button:not([data-lock-ready])').forEach(button=>{
    if(button.closest('fieldset')?.querySelector('legend')?.textContent==='Item code'&&row.querySelector('.mapping-summary-button')){button.dataset.lockReady='true';button.closest('fieldset').classList.add('order-replaced-mapping');return;}
    button.dataset.lockReady='true';const value=button.querySelector('.reference-value')?.textContent||'';const line=document.createElement('div');line.className='order-identifier-line';button.before(line);const copy=copyButton(value);line.append(copy,button);button.classList.add('order-identifier-lock');button.prepend(icon(button.getAttribute('aria-pressed')==='true'?'06-locked':'05-unlocked'));
    const group=button.closest('fieldset');if(group?.querySelector('legend')?.textContent==='ASIN'){const a=row.querySelector('.order-marketplace-links a[title="Open the Amazon product page"]')?.cloneNode(true);if(a){a.className='order-identifier-market';line.prepend(a);}}else{const mark=document.createElement('span');mark.className='order-identifier-market';mark.append(icon('sku-mapping'));line.prepend(mark);}
   });
   const status=row.querySelector('.amazon-order-status');if(status&&!status.dataset.designReady){const label=status.textContent.trim(),normalized=label.toLowerCase().replace(/[^a-z]/g,'');let kind='other',file;
    if(normalized==='pending'||normalized==='pendingavailability'){kind='pending';file='pending-final';}
    else if(normalized.includes('waitingforpickup')||normalized==='readyforpickup'){kind='pickup';file='waiting-for-pickup-final';}
    else if(normalized==='unshipped'||normalized==='partiallyshipped'){kind='unshipped';file='unshipped-final';}
    else if(/cancel|issue|exception|undeliver/.test(normalized))kind='issue';
    else if(normalized.startsWith('shipped')||['delivered','intransit','pickedup'].includes(normalized)){kind='shipped';file='shipped-final';}
    status.dataset.tone=kind;if(file&&!status.querySelector('.order-status-art'))status.prepend(statusIcon(file));status.dataset.designReady='true';
   }
   const pickup=row.querySelector('.platform-pickup-label');
   if(pickup&&status?.dataset.tone==='shipped')pickup.remove();
   else if(pickup&&!pickup.querySelector('img'))pickup.prepend(statusIcon('waiting-for-pickup-final'));

   const actions=row.querySelector('.item-actions');
   if(actions){
    actions.querySelectorAll('.order-icon-action').forEach(b=>{
     const pack=b.classList.contains('packing-slip'),ship=!!b.closest('.platform-pickup-action');
     if(!pack&&!ship){const tip=b.closest('.order-action-tip');if(tip&&!tip.hidden)tip.hidden=true;return;}
     if(b.dataset.designIcon)return;b.dataset.designIcon='true';
     b.replaceChildren(icon(pack?'print-packing-slip-simple':'shipped'));
     b.title=pack?'Print packing slip':b.getAttribute('aria-label');
    });
   }
   row.querySelectorAll('.item-number strong,.order-customer-shipping').forEach(el=>{
    if(el.dataset.currencyReady)return;el.dataset.currencyReady='true';
    el.textContent=el.textContent.replace(/\b(USD|CAD|AUD|EUR|GBP|JPY|MXN)\s+([\d,]+\.\d{2})/g,(_,code,amount)=>{
      try{return new Intl.NumberFormat('en-US',{style:'currency',currency:code}).format(Number(amount.replaceAll(',','')));}catch(_){return code+' '+amount;}
    });
    if(el.classList.contains('order-customer-shipping')){el.textContent=el.textContent.replace(/ Shipping$/,'');el.prepend(icon('shipping-truck'));el.title='Shipping paid by buyer';}
   });
   if(product&&!row.querySelector('.order-mapping-inline')){
    const source=row.querySelector('.mapping-summary-button');
    if(source){
     const mapping=document.createElement('div');mapping.className='order-mapping-inline';
     const trigger=source.cloneNode(false);trigger.className='order-inline-mapping-trigger';
     trigger.title=source.classList.contains('is-mapped')?'SKU mapping — view linked items':'Map this SKU to catalogue items';trigger.setAttribute('aria-label',trigger.title);trigger.append(icon(source.classList.contains('is-mapped')?'sku-mapped':'sku-not-mapped'));
     mapping.append(trigger);
     const lines=document.createElement('div');lines.className='order-mapping-values';
     const details=row.querySelector('.mapping-codes')?.textContent||'';
     if(source.classList.contains('is-mapped'))details.split(/\n+/).filter(Boolean).forEach(detail=>{
      const line=document.createElement('div');line.className='order-identifier-line';
      const code=detail.split(/\s*[×x]\s*/)[0].trim();const value=copyButton(code);value.textContent=detail;line.append(value,filterButton(code));lines.append(line);
     });else {const note=document.createElement('span');note.textContent='Needs mapping';lines.append(note);}
     mapping.append(lines);product.append(mapping);
    }
   }
  });
  for(const [selector,name] of [['.order-picture-edit','edit'],['.order-picture-more','actions-ai-human']])document.querySelectorAll(selector+':not([data-library-icon])').forEach(button=>{button.dataset.libraryIcon='true';button.querySelector('svg')?.remove();const stage=button.closest('.order-item')?.querySelector('[data-picture-actions]'),alert=button.matches('.order-picture-more')&&stage?.dataset.actionRequired==='true';button.prepend(icon(alert?'actions-ai-human-alert':name));if(button.matches('.order-picture-more')){button.setAttribute('aria-label','Actions');button.title=alert?'Actions — '+stage.dataset.actionSummary:'Actions';}});
 }
 const tip=document.createElement('div');tip.className='order-design-tooltip';tip.setAttribute('role','tooltip');tip.id='order-design-tooltip';tip.hidden=true;document.body.append(tip);
 function show(e){const target=e.target.closest('[data-order-copy]');if(!target)return;tip.textContent=target.dataset.orderCopy+'  ⧉';tip.hidden=false;target.setAttribute('aria-describedby',tip.id);const r=target.getBoundingClientRect();tip.style.left=Math.max(8,Math.min(r.left,innerWidth-tip.offsetWidth-8))+'px';tip.style.top=Math.max(8,Math.min(r.bottom+7,innerHeight-tip.offsetHeight-8))+'px';}
 document.addEventListener('mouseover',show);document.addEventListener('focusin',show);document.addEventListener('mouseout',()=>tip.hidden=true);document.addEventListener('focusout',()=>tip.hidden=true);
 document.addEventListener('click',async e=>{const target=e.target.closest('[data-order-copy]');if(target){try{await navigator.clipboard.writeText(target.dataset.orderCopy);tip.textContent='Copied';tip.hidden=false;}catch(_){tip.textContent='Could not copy. Please allow clipboard access.';tip.hidden=false;}}
  document.querySelectorAll('.order-compact-actions[open]').forEach(d=>{if(!d.contains(e.target)||e.target.closest('button,a'))d.open=false;});
 });
 document.addEventListener('keydown',e=>{if(e.key==='Escape'){tip.hidden=true;document.querySelectorAll('.order-compact-actions[open]').forEach(d=>{d.open=false;d.querySelector('summary').focus();});}if((e.key==='Enter'||e.key===' ')&&e.target.matches('strong[data-order-copy]')){e.preventDefault();e.target.click();}});
 window.addEventListener('scroll',()=>tip.hidden=true,true);
 new MutationObserver(init).observe(document.querySelector('.orders-workspace')||document.body,{childList:true,subtree:true});init();
})();
