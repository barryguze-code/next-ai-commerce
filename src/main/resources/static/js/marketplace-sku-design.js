(()=>{'use strict';
document.querySelectorAll('[data-sku-view-filter]').forEach(select=>select.addEventListener('change',()=>{const url=new URL(location.href);if(select.value)url.searchParams.set(select.dataset.skuViewFilter,select.value);else url.searchParams.delete(select.dataset.skuViewFilter);url.searchParams.delete('page');url.searchParams.delete('goToPage');location.assign(url)}));
document.querySelectorAll('[data-sku-channel]').forEach(button=>button.addEventListener('click',()=>{const url=new URL(location.href);if(button.dataset.skuChannel)url.searchParams.set('f_channel',button.dataset.skuChannel);else url.searchParams.delete('f_channel');url.searchParams.delete('page');url.searchParams.delete('goToPage');location.assign(url)}));
document.querySelector('[data-sku-clear-condition]')?.addEventListener('click',()=>{const url=new URL(location.href);url.searchParams.set('f_condition','ALL');url.searchParams.delete('page');url.searchParams.delete('goToPage');location.assign(url)});
function enhance(table){if(!table)return;
table.querySelectorAll('[data-picture-editable="true"]').forEach(holder=>window.NextAiMappingWidget.attachEditor(holder,null,holder.dataset.pictureSku));
table.querySelectorAll('.sku-filter-lock').forEach(button=>{
 const key=button.dataset.filterCode?'f_itemCode':'q',value=button.dataset.filterCode||button.dataset.filterValue;
 const active=new URL(location.href).searchParams.get(key)===value;
 button.setAttribute('aria-pressed',String(active));button.title=active?'Filter locked — click to clear':'Not locked — click to filter';
 button.querySelector('img').src='/images/platform/'+(active?'06-locked':'05-unlocked')+'.svg';
 button.onclick=()=>{const url=new URL(location.href);if(active)url.searchParams.delete(key);else url.searchParams.set(key,value);url.searchParams.delete('page');url.searchParams.delete('goToPage');location.assign(url)};
});
table.querySelectorAll('[data-copy-value]').forEach(button=>{button.title='Copy '+button.dataset.copyValue;button.onclick=async()=>{try{await navigator.clipboard.writeText(button.dataset.copyValue);button.title='Copied'}catch{button.title='Copy unavailable'}}});
table.querySelectorAll('.customer-shipping-note').forEach(note=>{const img=window.NextAiIcons.create('shipping-truck');note.prepend(img)});
table.querySelectorAll('.sku-open-actions').forEach(trigger=>{
 if(trigger.dataset.skuActionsReady)return;trigger.dataset.skuActionsReady='true';
 if(trigger.closest('.sku-family-table')){trigger.classList.add('table-action-trigger');trigger.setAttribute('aria-label','Actions for '+trigger.dataset.sellerSku);trigger.replaceChildren(window.NextAiIcons.create('actions'));}
 trigger.onclick=()=>{
  const dialog=document.createElement('dialog');dialog.className='sku-actions-dialog';dialog.dataset.title=trigger.closest('tr')?.querySelector('.sku-approved-title')?.textContent||'Marketplace SKU actions';
  const header=document.createElement('header'),title=document.createElement('strong'),close=document.createElement('button');title.textContent='Actions';close.textContent='×';close.type='button';close.setAttribute('aria-label','Close actions');header.append(title,close);dialog.append(header);
  const position=()=>{const r=trigger.getBoundingClientRect();dialog.style.left=Math.max(12,Math.min(r.right+8,innerWidth-dialog.offsetWidth-12))+'px';dialog.style.top=Math.max(12,Math.min(r.top,innerHeight-dialog.offsetHeight-12))+'px'};
  const outside=e=>{if(!dialog.contains(e.target)&&!trigger.contains(e.target))dismiss()};
  const dismiss=()=>{document.removeEventListener('click',outside);window.removeEventListener('resize',position);document.removeEventListener('scroll',position,true);dialog.close();dialog.remove();trigger.focus()};
close.onclick=dismiss;dialog.addEventListener('cancel',e=>{e.preventDefault();dismiss()});dialog.addEventListener('click',e=>{if(e.target===dialog)dismiss()});
  if(trigger.dataset.warningText){const info=document.createElement('p');info.className='sku-action-info';info.textContent=trigger.dataset.warningText;dialog.append(info)}
  const entry=(icon,name,description,action,url)=>{const b=document.createElement(url?'a':'button');if(url){b.href=url;b.target='_blank';b.rel='noopener noreferrer'}else{b.type='button';b.onclick=()=>{dismiss();action()}}b.className='sku-action-entry';const copy=document.createElement('span'),strong=document.createElement('strong'),small=document.createElement('small');strong.textContent=name;small.textContent=description;copy.append(strong,small);b.append(window.NextAiIcons.create(icon),copy);dialog.append(b)};
  entry(trigger.dataset.unmapped==='true'?'unmapped':'mapping','SKU mapping','Review catalogue products and quantities',()=>window.NextAiMappingWidget.open(document.querySelector('[data-target="'+trigger.dataset.mappingDialog+'"]')));
  if(trigger.dataset.stockEligible!=='false')entry('inventory','Adjust inventory','Review mapped stock for this SKU',()=>window.openSharedInventoryAdjustment?.(trigger));
  entry('price','Change SKU price','Edit this listing in Seller Central ↗',null,trigger.dataset.sellerUrl);
  entry('sale','Set sale price','Set a promotional price in Seller Central ↗',null,trigger.dataset.sellerUrl);
  document.querySelectorAll('.sku-actions-dialog').forEach(d=>d.dispatchEvent(new Event('cancel',{cancelable:true})));document.body.append(dialog);window.prepareActionSuggestionMenu?.(dialog);dialog.show();position();close.focus();document.addEventListener('click',outside);window.addEventListener('resize',position);document.addEventListener('scroll',position,true);dialog.addEventListener('keydown',e=>{if(e.key==='Escape')dismiss()});
 };
});
}
window.NextAiMarketplaceRows={enhance};enhance(document.querySelector('.sku-table'));
const body=document.querySelector('.sku-table>tbody');
if(body){
 let familyRowId=0;
 const syncFamilyHeadings=()=>{
  const members=new Set(),ends=new Set();
  body.querySelectorAll('[data-family-heading]').forEach(heading=>{
   const visible=[],run=[];for(let row=heading.nextElementSibling;row?.dataset.familyParent===heading.dataset.familyHeading;row=row.nextElementSibling){
    run.push(row);
    // Disclosure is separate from the existing domain/paging exclusions.
    if(!row.hidden&&!row.classList.contains('table-data-hidden')&&row.style.display!=='none'&&(row.hasAttribute('data-family-collapsed')||getComputedStyle(row).display!=='none'))visible.push(row);
   }
   for(const row of visible)if(!row.id)row.id='sku-family-listing-'+(++familyRowId);
   const membership=visible.map(row=>row.id).join(' ');
   if(heading.dataset.familyMembership!==membership){heading.dataset.familyMembership=membership;delete heading.dataset.familyCollapsed;}
   let disclosure=heading.querySelector('.sku-family-toggle');
   if(!disclosure){
    disclosure=document.createElement('button');disclosure.type='button';disclosure.className='sku-family-toggle';
    const icon=document.createElement('span');icon.className='sku-family-chevron';icon.setAttribute('aria-hidden','true');icon.textContent='⌄';
    const label=heading.querySelector('.sku-family-identity')||document.createElement('span');
    label.className='sku-family-identity';label.textContent='Parent SKU '+heading.dataset.familyHeading;disclosure.append(icon,label);
    let count=heading.querySelector('.sku-family-count');if(!count){count=document.createElement('span');count.className='sku-family-count';heading.cells[0].append(count);}
    disclosure.setAttribute('aria-description','Family total reflects complete verified membership. Group count reflects matching listings in this section and page before collapse. Other variations may be outside the current filters or page.');
    disclosure.onclick=()=>{if(heading.dataset.familyCollapsed)delete heading.dataset.familyCollapsed;else heading.dataset.familyCollapsed='true';syncFamilyHeadings();disclosure.focus();};
    heading.cells[0].prepend(disclosure);
   }
   const expanded=!heading.dataset.familyCollapsed;
   const total=Number(heading.dataset.familyTotal);
   const verified=Number.isSafeInteger(total)&&total>0&&total>=visible.length;
   const count=(verified?total+' '+(total===1?'variation':'variations'):'Family total unverified')+(!verified||total!==visible.length?' · '+visible.length+' in this group':'');
   const label=heading.querySelector('.sku-family-count');if(label.textContent!==count)label.textContent=count;
   disclosure.setAttribute('aria-expanded',String(expanded));disclosure.setAttribute('aria-controls',membership);
   disclosure.setAttribute('aria-label',(expanded?'Collapse':'Expand')+' family for Parent SKU '+heading.dataset.familyHeading);
   for(const row of run){const collapsed=!expanded&&visible.includes(row);if(collapsed)row.dataset.familyCollapsed='true';else row.removeAttribute('data-family-collapsed');}

   if(heading.hidden!==(visible.length===0))heading.hidden=visible.length===0;
   visible.forEach(row=>members.add(row));if(visible.length)ends.add(visible.at(-1));
  });
  body.querySelectorAll('tr[data-sku]').forEach(row=>{if(row.classList.contains('sku-family-member')!==members.has(row))row.classList.toggle('sku-family-member',members.has(row));if(row.classList.contains('sku-family-end')!==ends.has(row))row.classList.toggle('sku-family-end',ends.has(row));});
 };
 let pending=false;new MutationObserver(()=>{if(!pending){pending=true;requestAnimationFrame(()=>{pending=false;syncFamilyHeadings()})}}).observe(body,{subtree:true,childList:true,attributes:true,attributeFilter:['hidden','class','style']});window.NextAiMarketplaceRows.refreshFamilies=syncFamilyHeadings;syncFamilyHeadings();
}

})();
