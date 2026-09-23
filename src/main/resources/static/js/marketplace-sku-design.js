(()=>{'use strict';
const table=document.querySelector('.sku-table');if(!table)return;
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
 trigger.onclick=()=>{
  const dialog=document.createElement('dialog');dialog.className='sku-actions-dialog';
  const header=document.createElement('header'),title=document.createElement('strong'),close=document.createElement('button');title.textContent='Actions';close.textContent='×';close.type='button';close.setAttribute('aria-label','Close actions');header.append(title,close);dialog.append(header);
  const position=()=>{const r=trigger.getBoundingClientRect();dialog.style.left=Math.max(12,Math.min(r.right+8,innerWidth-dialog.offsetWidth-12))+'px';dialog.style.top=Math.max(12,Math.min(r.top,innerHeight-dialog.offsetHeight-12))+'px'};
  const outside=e=>{if(!dialog.contains(e.target)&&!trigger.contains(e.target))dismiss()};
  const dismiss=()=>{document.removeEventListener('click',outside);window.removeEventListener('resize',position);document.removeEventListener('scroll',position,true);dialog.close();dialog.remove();trigger.focus()};
close.onclick=dismiss;dialog.addEventListener('cancel',e=>{e.preventDefault();dismiss()});dialog.addEventListener('click',e=>{if(e.target===dialog)dismiss()});
  if(trigger.dataset.warningText){const info=document.createElement('p');info.className='sku-action-info';info.textContent=trigger.dataset.warningText;dialog.append(info)}
  const entry=(icon,name,description,action,url)=>{const b=document.createElement(url?'a':'button');if(url){b.href=url;b.target='_blank';b.rel='noopener noreferrer'}else{b.type='button';b.onclick=()=>{dismiss();action()}}b.className='sku-action-entry';const copy=document.createElement('span'),strong=document.createElement('strong'),small=document.createElement('small');strong.textContent=name;small.textContent=description;copy.append(strong,small);b.append(window.NextAiIcons.create(icon),copy);dialog.append(b)};
  entry(trigger.dataset.unmapped==='true'?'unmapped':'mapping','SKU mapping','Review catalogue products and quantities',()=>window.NextAiMappingWidget.open(document.querySelector('[data-target="'+trigger.dataset.mappingDialog+'"]')));
  entry('inventory','Adjust inventory','Review mapped stock for this SKU',()=>window.openSharedInventoryAdjustment?.(trigger));
  entry('price','Change SKU price','Edit this listing in Seller Central ↗',null,trigger.dataset.sellerUrl);
  entry('sale','Set sale price','Set a promotional price in Seller Central ↗',null,trigger.dataset.sellerUrl);
  document.querySelectorAll('.sku-actions-dialog').forEach(d=>d.dispatchEvent(new Event('cancel',{cancelable:true})));document.body.append(dialog);dialog.show();position();close.focus();document.addEventListener('click',outside);window.addEventListener('resize',position);document.addEventListener('scroll',position,true);dialog.addEventListener('keydown',e=>{if(e.key==='Escape')dismiss()});
 };
});
})();
