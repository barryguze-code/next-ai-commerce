/* Shared presentation only. Inventory, assignment and fulfillment retain their own handlers. */
(()=>{
 'use strict';
 const titles='.item-product-copy>strong,.sku-approved-title,.product-cell>div>strong,.ledger-product>strong,.receive-product>div>strong';
 const recordName=title=>({VENDOR:'Vendor',USER:'Member',ACCOUNT:'Account'}[title.closest('tr')?.dataset.entityType]||'Product');
 const make=(tag,cls,text)=>{const el=document.createElement(tag);if(cls)el.className=cls;if(text)el.textContent=text;return el};
 let dialog,origin;
 function details(title){
  origin=title;
  const row=title.closest('tr,.order-item');if(!row)return;
  // Inventory has a richer, authoritative movement drawer. Reuse it.
  if(row.classList.contains('inventory-row')&&window.openInventoryHistory){window.openInventoryHistory(row);return;}
  if(!dialog){
   dialog=make('dialog','invite-dialog');dialog.id='ui-product-dialog';dialog.setAttribute('aria-labelledby','ui-product-title');
   dialog.innerHTML='<div class="dialog-shell"><header class="dialog-header"><img class="ui-product-picture" alt=""><div><span class="eyebrow dark">Product details</span><h2 id="ui-product-title"></h2></div><button type="button" class="dialog-close" aria-label="Close product details">×</button></header><div class="dialog-body"><dl class="ui-record-facts"></dl><nav class="ui-record-links" aria-label="Related inventory"></nav></div><footer class="dialog-actions"><button type="button" class="secondary-button">Close</button></footer></div>';
   dialog.querySelectorAll('button').forEach(b=>b.onclick=()=>dialog.close());dialog.addEventListener('close',()=>origin?.isConnected&&origin.focus());document.body.append(dialog);
  }
  dialog.querySelector('h2').textContent=title.textContent.trim();
  dialog.querySelector('.eyebrow').textContent=recordName(title)+' details';
  const source=row.querySelector('.table-overview-picture img,.order-picture-cell [data-picture-actions]>img,.product-avatar img'),picture=dialog.querySelector('img');
  picture.hidden=!source?.src;if(source?.src)picture.src=source.src;else picture.removeAttribute('src');
  const facts=dialog.querySelector('dl');facts.replaceChildren();
  const add=(label,value)=>{if(!value?.trim())return;const item=make('div');item.append(make('dt','',label),make('dd','',value.trim()));facts.append(item)};
  if(row.matches('.order-item')){
   add('Marketplace SKU',row.querySelector('.order-sku-copy')?.dataset.copySku);
   add('Order',row.querySelector('.item-order-reference')?.innerText);
   const headers=row.closest('[data-table-widget]')?.querySelectorAll('.table-grid-header [data-column]')||[];
   headers.forEach(h=>{if(!['quantity','sales','available','buy-box'].includes(h.dataset.column))return;add(h.textContent,row.querySelector(':scope>[data-column="'+h.dataset.column+'"]')?.innerText)});
  }else{
   const table=row.closest('table');
   [...row.cells].forEach(cell=>{
    if(['record-context','product','product-details','action','actions'].includes(cell.dataset.column))return;
    const header=[...table.querySelectorAll('thead th')].find(h=>h.dataset.column===cell.dataset.column);
    // Snapshot only already-rendered facts. Never clone controls or their authority.
    const clone=cell.cloneNode(true);clone.querySelectorAll('button,nav,dialog,form,[hidden]').forEach(el=>el.remove());
    add(header?.dataset.title||header?.textContent?.replace(/[↑↓↕]/g,'').trim()||'Details',clone.textContent);
   });
   if(row.dataset.sku)add('Marketplace SKU',row.dataset.sku);
  }
  const links=dialog.querySelector('nav');links.replaceChildren();
  row.querySelectorAll('a[href*="/mappings/ledger"],a[href*="/inventory/ledger"]').forEach(link=>{const a=make('a','', 'Inventory movements · '+link.textContent.trim());a.href=link.href;links.append(a)});
  links.hidden=!links.childElementCount;
  dialog.showModal();
 }
 function enhance(root){
  const matches=[...(root.matches?.(titles)?[root]:[]),...root.querySelectorAll(titles)];
  matches.forEach(title=>{
   if(title.dataset.uiProduct!==undefined||!title.closest('tbody tr,.order-item'))return;
   const name=recordName(title).toLowerCase();
   title.dataset.uiProduct='';title.tabIndex=0;title.setAttribute('role','button');title.setAttribute('aria-label','View '+name+' details: '+title.textContent.trim());title.title='View '+name+' details';
   delete title.dataset.orderCopy;
   const heading=make('span','ui-product-heading');title.before(heading);heading.append(title);
   const copy=make('button','ui-copy-title');copy.type='button';copy.title='Copy '+name+' name';copy.setAttribute('aria-label','Copy '+name+' name');copy.dataset.uiCopy=title.textContent.trim();
   copy.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V4H4v12h4"/></svg>';heading.append(copy);
  });
 }
 document.addEventListener('click',async event=>{
  const copy=event.target.closest('[data-ui-copy]'),title=event.target.closest('[data-ui-product]');if(!copy&&!title)return;
  event.preventDefault();event.stopPropagation();
  if(title){details(title);return;}
  try{await navigator.clipboard.writeText(copy.dataset.uiCopy);copy.title='Copied';copy.setAttribute('aria-label','Name copied');}catch(_){copy.title='Copy unavailable';}
 },true);
 document.addEventListener('keydown',event=>{if(['Enter',' '].includes(event.key)&&event.target.matches('[data-ui-product]')){event.preventDefault();event.stopPropagation();details(event.target)}},true);
 function start(){
  enhance(document);
  const pending=new Set();let scheduled=false;
  new MutationObserver(records=>{
   for(const record of records)for(const node of record.addedNodes)if(node.nodeType===1&&(node.matches(titles)||node.querySelector(titles)))pending.add(node);
   if(!pending.size||scheduled)return;scheduled=true;queueMicrotask(()=>{scheduled=false;for(const root of pending)if(root.isConnected)enhance(root);pending.clear()});
  }).observe(document.querySelector('main')||document.body,{childList:true,subtree:true});
 }
 if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',start,{once:true});else start();
})();
