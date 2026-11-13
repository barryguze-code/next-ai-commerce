/* Table data interactions shared by semantic tables and the Orders grid.
 * Domain filters retain ownership of hidden/style; paging uses its own class.
 * Server paging remains authoritative: local column filters are explicitly scoped.
 */
(()=>{
'use strict';
if(window.NextAiTableDataTools)return;
const states=new Map();
const normalize=value=>String(value??'').replace(/\s+/g,' ').trim();
const csvCell=(value,numeric=false)=>{let text=normalize(value);if(!(numeric&&/^-?\d+(?:\.\d+)?$/.test(text))&&/^[=+\-@\t\r]/.test(text))text="'"+text;return '"'+text.replace(/"/g,'""')+'"'};
// Existing GET navigation keeps domain initialization and authorization server-owned.
function bindServerSearch(input){
 if(input.dataset.liveSearch)return;input.dataset.liveSearch='true';let timer,composing=false,navigating='';
 const form=input.form,key='nextai.table.search-focus.'+location.pathname;
 try{const saved=JSON.parse(sessionStorage.getItem(key)||'null');sessionStorage.removeItem(key);if(saved&&saved.q===(new URL(location.href).searchParams.get(input.name||'q')||'')){const restore=()=>{if(!input.isConnected)return;input.focus({preventScroll:true});input.setSelectionRange(saved.start,saved.end);window.scrollTo(0,saved.y||0)};if(document.readyState==='complete')requestAnimationFrame(restore);else window.addEventListener('load',()=>requestAnimationFrame(restore),{once:true});}}catch{}
 const flush=()=>{clearTimeout(timer);if(composing)return;
  const value=input.value.trim(),next=new URL(location.href);
  // Preserve every existing scope/sort parameter, then apply the original GET form fields.
  if(form)for(const [name,value]of new FormData(form))if(name!==input.name)next.searchParams.set(name,value);
  if(value)next.searchParams.set(input.name||'q',value);else next.searchParams.delete(input.name||'q');
  if(next.href===location.href&&!navigating)return;
  next.searchParams.delete('page');next.searchParams.delete('goToPage');
  if(next.href===navigating)return;navigating=next.href;
  try{sessionStorage.setItem(key,JSON.stringify({q:value,start:input.selectionStart,end:input.selectionEnd,y:window.scrollY}));}catch{}
  const toolbar=input.closest('.table-standard-toolbar');toolbar?.setAttribute('aria-busy','true');input.setAttribute('aria-busy','true');
  document.dispatchEvent(new CustomEvent('table:search-navigation',{detail:{url:next.pathname+next.search}}));location.assign(next.href);
 };
 const schedule=()=>{clearTimeout(timer);if(!composing)timer=setTimeout(flush,300)};
 input.addEventListener('input',schedule);input.addEventListener('compositionstart',()=>{composing=true;clearTimeout(timer)});input.addEventListener('compositionend',()=>{composing=false;schedule()});
 form?.addEventListener('submit',event=>{event.preventDefault();flush()});
 window.addEventListener('pageshow',()=>{input.removeAttribute('aria-busy');input.closest('.table-standard-toolbar')?.removeAttribute('aria-busy');navigating=''});
}
// Move original controls, retaining listeners, form ownership and permission decisions.
function bindClientSearch(input,toolbar){
 if(input.dataset.compositionSearch)return;input.dataset.compositionSearch='true';let timer,composing=false,replaying=false;
 const flush=()=>{clearTimeout(timer);if(composing)return;toolbar.setAttribute('aria-busy','true');replaying=true;input.dispatchEvent(new Event('input',{bubbles:true}));replaying=false;requestAnimationFrame(()=>toolbar.removeAttribute('aria-busy'))};
 input.addEventListener('input',event=>{if(replaying)return;event.stopImmediatePropagation();clearTimeout(timer);if(!composing)timer=setTimeout(flush,300)},true);
 input.addEventListener('compositionstart',()=>{composing=true;clearTimeout(timer)});input.addEventListener('compositionend',()=>{composing=false;clearTimeout(timer);timer=setTimeout(flush,300)});
 input.form?.addEventListener('submit',event=>{event.preventDefault();event.stopImmediatePropagation();flush()},true);
}
// Accepted row-action presentation; original nodes retain listeners, fields and counts.
function presentTableCommands(group){
 const enhance=()=>group.querySelectorAll('button,a,summary').forEach(control=>{
  if(control.hasAttribute('data-shortcut-control')||control.querySelector(':scope>.table-action-label'))return;
  control.classList.add('table-action-entry');
  const icon=document.createElement('span');icon.className='table-action-icon';icon.dataset.artReady='true';icon.setAttribute('aria-hidden','true');
  // Missing artwork stays neutral pending the owner's supplied icon suite.
  const image=control.dataset.actionImage;
  if(image?.startsWith('/images/platform/')){const img=document.createElement('img');img.src=image;img.alt='';if(control.hasAttribute('data-preserve-platform-art'))img.setAttribute('data-preserve-platform-art','true');icon.append(img);}
  const label=document.createElement('span');label.className='table-action-label';const strong=document.createElement('strong');
  strong.append(...control.childNodes);const walker=document.createTreeWalker(strong,NodeFilter.SHOW_TEXT);let first;while((first=walker.nextNode())){if(first.parentElement.closest('svg,[aria-hidden=true],.table-action-icon')||!first.textContent.trim())continue;first.textContent=first.textContent.replace(/^\s*[+＋]\s*/, '');break;}label.append(strong);
  const description=control.dataset.actionDescription;if(description){const small=document.createElement('small');small.textContent=description;label.append(small);}
  control.append(icon,label);
 });
 enhance();new MutationObserver(enhance).observe(group,{subtree:true,childList:true});
}
// Local prototype: preferences contain only allowlisted command IDs, scoped to user/account/table.
function composeActionShortcuts(card,setup,cluster,details,close){
 const table=card.dataset.tableShortcuts,scope=card.closest('[data-basket-scope]')?.dataset.basketScope,pilot=!!card.closest('[data-header-pilot]')||card.classList.contains('shared-table-composition');
 if(!table||!scope)return;
 const controls=[...setup.querySelectorAll('[data-command-id]')].filter(c=>c.matches('button,a'));
 const commands=new Map(controls.map(c=>[c.dataset.commandId,c]));
 const key='nextai.table.shortcuts.v1:'+scope+':'+table;
 let selected=[];try{const saved=JSON.parse(localStorage.getItem(key)||'[]');if(Array.isArray(saved))selected=[...new Set(saved.filter(id=>typeof id==='string'&&commands.has(id)))];}catch{}
 const bar=document.createElement('div');bar.className='table-action-shortcuts';bar.setAttribute('role','group');bar.setAttribute('aria-label','Action shortcuts');cluster.insertBefore(bar,details);
 const live=document.createElement('span');live.className='sr-only';live.setAttribute('role','status');live.setAttribute('aria-live','polite');cluster.append(live);
 const label=c=>c.querySelector('.table-action-label strong')?.textContent.trim()||c.textContent.trim();
 const usable=c=>c.isConnected&&!c.matches(':disabled,[aria-disabled="true"]')&&!c.closest('[hidden],fieldset[disabled]')&&(!c.matches('a')||c.hasAttribute('href'));
 const persist=()=>{try{localStorage.setItem(key,JSON.stringify(selected));}catch{}};
 const add=id=>{if(!commands.has(id)||!usable(commands.get(id))||selected.includes(id))return;selected.push(id);persist();render();live.textContent='Shortcut added: '+label(commands.get(id));};
 let dragging='';
 function render(){
  bar.replaceChildren();for(const id of selected){const original=commands.get(id),wrap=document.createElement('div');wrap.className='table-shortcut-item';
   const invoke=document.createElement('button');invoke.type='button';invoke.className='table-action-shortcut';invoke.dataset.shortcutId=id;invoke.setAttribute('aria-label',label(original));invoke.setAttribute('data-tooltip',label(original));invoke.disabled=!usable(original);
   const image=original.querySelector('.table-action-icon img');if(image)invoke.append(image.cloneNode(true));else invoke.textContent=label(original);
   invoke.addEventListener('click',()=>{if(!usable(original))return;close(false);original.click();});
   const remove=document.createElement('button');remove.type='button';remove.className='table-shortcut-remove';remove.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"/></svg>';remove.setAttribute('aria-label','Remove '+label(original)+' shortcut');remove.onclick=()=>{if(cluster.dataset.shortcutEntry!=='true')return;selected=selected.filter(x=>x!==id);persist();render();details.querySelector('summary').focus();};wrap.append(invoke,remove);bar.append(wrap);
  }
  {const target=document.createElement('button');target.type='button';target.className='table-shortcut-target';target.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>';target.setAttribute('aria-label','Add shortcuts menu');target.setAttribute('data-tooltip','Menu');target.setAttribute('aria-controls','table-actions-'+table);target.setAttribute('aria-expanded',String(details.open));const available=[...commands].some(([id,c])=>!selected.includes(id)&&usable(c));target.hidden=!available&&!pilot;target.onclick=()=>{cluster.dataset.shortcutEntry='true';details.classList.add('is-shortcut-add');const alreadyOpen=details.open;details.open=true;if(alreadyOpen)[...commands].find(([id,c])=>!selected.includes(id)&&usable(c))?.[1].focus();};
   target.ondragover=event=>{if(dragging&&commands.has(dragging)){event.preventDefault();event.dataTransfer.dropEffect='copy';target.classList.add('drag-over');}};target.ondragleave=()=>target.classList.remove('drag-over');target.ondrop=event=>{event.preventDefault();event.stopPropagation();if(dragging)add(dragging);dragging='';};bar.prepend(target);
  }
  for(const [id,c] of commands)c.closest('.table-shortcut-command')?.classList.toggle('is-pinned',selected.includes(id));
  setup.querySelectorAll('.table-shortcut-pin').forEach(b=>{const pinned=selected.includes(b.dataset.shortcutToggle);b.disabled=!usable(commands.get(b.dataset.shortcutToggle));b.setAttribute('aria-pressed',String(pinned));b.setAttribute('aria-label',(pinned?'Unpin ':'Pin ')+label(commands.get(b.dataset.shortcutToggle)));});
 }
 for(const [id,control] of commands){const row=document.createElement('div');row.className='table-shortcut-command';control.before(row);row.append(control);control.draggable=true;control.addEventListener('dragstart',event=>{dragging=id;event.dataTransfer.setData('text/plain',id);event.dataTransfer.effectAllowed='copy';});control.addEventListener('dragend',()=>{dragging='';bar.querySelector('.drag-over')?.classList.remove('drag-over');});
  const pin=document.createElement('button');pin.type='button';pin.className='table-shortcut-pin';pin.dataset.shortcutToggle=id;pin.setAttribute('data-shortcut-control','true');pin.innerHTML='<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="M7 3h10l-1 6 3 3v2H5v-2l3-3-1-6ZM12 14v7"/></svg>';pin.onclick=event=>{event.stopPropagation();if(cluster.dataset.shortcutEntry!=='true')return;if(selected.includes(id)){selected=selected.filter(value=>value!==id);persist();render();live.textContent='Shortcut removed: '+label(control);}else add(id);};row.append(pin);
 }
 // Capture add-mode activation before an original command's own handlers or delegated actions.
 setup.addEventListener('click',event=>{if(cluster.dataset.shortcutEntry!=='true'||event.target.closest('[data-shortcut-control]'))return;const row=event.target.closest('.table-shortcut-command');if(!row&&!event.target.closest('button,a,summary'))return;event.preventDefault();event.stopImmediatePropagation();const control=row?.querySelector('[data-command-id]');if(control){const id=control.dataset.commandId;if(selected.includes(id)){selected=selected.filter(value=>value!==id);persist();render();live.textContent='Shortcut removed: '+label(control);}else add(id);}},true);
 details.querySelector('summary').addEventListener('click',()=>{delete cluster.dataset.shortcutEntry;details.classList.remove('is-shortcut-add');});
 details.addEventListener('toggle',render);
 // Observe originals, never shortcut nodes: permission/busy/count changes remain authoritative.
 for(const c of controls)new MutationObserver(render).observe(c,{attributes:true,childList:true,subtree:true,characterData:true});
 render();
}

function composeTable(card,toolbar,host,search,domainOwned=false){
 if(!card.hasAttribute('data-table-composition'))return;
 if(!domainOwned){search.form?.querySelectorAll('button').forEach(button=>{if(button.textContent.trim()==='Search')button.remove()});
 if(search.name==='q'&&search.form?.method.toLowerCase()==='get')bindServerSearch(search);else bindClientSearch(search,toolbar);}
 const columns=host.querySelector('.table-column-control>summary');
 if(columns&&!columns.querySelector('.table-columns-art')){columns.innerHTML='<svg class="table-columns-art" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="4" width="18" height="16" rx="2"></rect><path d="M9 4v16M15 4v16"></path></svg>';columns.setAttribute('aria-label','Columns');columns.title='Columns';}
 if([...card.querySelectorAll('.table-command-menu')].some(n=>n.closest('.data-card,.table-widget-surface,.receive-lines,.desk-card,[data-table-composition]')===card))return;
 const spinner=document.createElement('span');spinner.className='table-search-spinner';spinner.setAttribute('role','status');spinner.setAttribute('aria-label','Loading results');toolbar.append(spinner);
 const details=document.createElement('details');details.className='table-command-menu table-row-warning';
 const trigger=document.createElement('summary');trigger.className='table-action-trigger';trigger.setAttribute('aria-label','Actions');trigger.setAttribute('data-tooltip','Actions');
 const art=document.createElement('img');art.src='/images/platform/optimized32/actions-owner-20261111.png?v=1.14.4';art.alt='';trigger.append(art);
 const panel=document.createElement('div');panel.className='table-warning-panel table-command-panel';panel.setAttribute('popover','manual');panel.setAttribute('aria-label','Table actions');
 const setup=document.createElement('div');setup.className='table-command-group';setup.setAttribute('role','group');setup.setAttribute('aria-label','Table commands');
 card.querySelectorAll('[data-table-command]').forEach(node=>{if(node.closest('.data-card,.table-widget-surface,.receive-lines,.desk-card,[data-table-composition]')===card&&!node.parentElement.closest('[data-table-command],.table-command-menu'))setup.append(node)});
 const bulk=document.createElement('div');bulk.className='table-command-group';bulk.setAttribute('role','group');bulk.setAttribute('aria-label','Selected records');
 card.querySelectorAll('[data-table-command-group="bulk"]').forEach(node=>{if(node.closest('.data-card,.table-widget-surface,.receive-lines,.desk-card,[data-table-composition]')===card)bulk.append(node)});
 const heading=document.createElement('header');heading.className='table-action-heading';const copy=document.createElement('div');const title=document.createElement('strong');title.textContent='Actions';copy.append(title);const dismiss=document.createElement('button');dismiss.type='button';dismiss.textContent='×';dismiss.setAttribute('aria-label','Close actions');dismiss.onclick=()=>close(true);heading.append(copy,dismiss);panel.append(heading);
 if(setup.children.length)panel.append(setup);if(bulk.children.length)panel.append(bulk);
 presentTableCommands(setup);presentTableCommands(bulk);

 details.append(trigger,panel);
 const header=[...card.querySelectorAll('.card-toolbar')].find(n=>n.closest('.data-card,.table-widget-surface,.receive-lines,.desk-card,[data-table-composition]')===card);
 let commands=header?.querySelector(':scope>.table-header-commands');
 if(!commands&&header){commands=document.createElement('div');commands.className='table-header-commands';header.append(commands)}
 if(header&&commands)header.append(commands);
 if(header){const heading=header.querySelector('h2');[...header.children].find(node=>node!==commands&&node.contains(heading))?.classList.add('table-header-copy');}
 const cluster=document.createElement('div');cluster.className='table-sync-actions';
 // Sync forms retain their own status wrapper; table commands never enter it.
 cluster.append(details);(commands||host).append(cluster);
 const position=()=>{const box=trigger.getBoundingClientRect(),top=Math.max(12,Math.min(box.bottom+6,innerHeight-Math.min(panel.scrollHeight,innerHeight-24)-12));panel.style.maxHeight=Math.max(0,innerHeight-top-12)+'px';panel.style.left=Math.max(12,Math.min(box.right-panel.offsetWidth,innerWidth-panel.offsetWidth-12))+'px';panel.style.top=top+'px'};
 const close=focus=>{const shortcut=cluster.dataset.shortcutEntry==='true';delete cluster.dataset.shortcutEntry;details.classList.remove('is-shortcut-add');details.open=false;if(panel.matches(':popover-open'))panel.hidePopover();if(focus)cluster.dataset.restoreCommandFocus=shortcut?'shortcut':'actions'};if(card.dataset.tableShortcuts)panel.id='table-actions-'+card.dataset.tableShortcuts;
 composeActionShortcuts(card,setup,cluster,details,close);
 panel.addEventListener('toggle',()=>{if(details.open){position();if(cluster.dataset.shortcutEntry==='true')setup.querySelector('.table-shortcut-command:not(.is-pinned) [data-command-id]:not(:disabled)')?.focus()}},true);
 const panelResize=new ResizeObserver(()=>{if(details.open)position()});panelResize.observe(panel);
 details.addEventListener('toggle',()=>{if(!details.open){if(panel.matches(':popover-open'))panel.hidePopover();const restore=cluster.dataset.restoreCommandFocus;delete cluster.dataset.restoreCommandFocus;if(restore){const target=cluster.querySelector('.table-shortcut-target');(restore==='shortcut'&&target&&!target.hidden?target:trigger)?.focus();}return}panel.showPopover();position()});
 document.addEventListener('pointerdown',event=>{if(details.open&&!details.contains(event.target)&&!cluster.contains(event.target)&&!event.target.closest('.table-action-shortcuts'))close(false)});
 document.addEventListener('keydown',event=>{if(details.open&&event.key==='Escape'){event.preventDefault();event.stopPropagation();close(true)}});
 panel.addEventListener('click',event=>{if(!event.target.closest('[data-shortcut-control]')&&event.target.closest('button:not([disabled]),a[href]'))close(false)});
 window.addEventListener('resize',()=>{if(details.open)position()});document.addEventListener('scroll',()=>{if(details.open)position()},true);
}
function composeTabs(card){
 if(!card.hasAttribute('data-table-composition'))return;
 const header=[...card.querySelectorAll('.card-toolbar')].find(n=>n.closest('.data-card,.table-widget-surface,.receive-lines,.desk-card,[data-table-composition]')===card);
 [...card.querySelectorAll('.order-tab-caption')].filter(n=>n.closest('.data-card,.table-widget-surface,.receive-lines,.desk-card,[data-table-composition]')===card).forEach(caption=>caption.remove());
 for(const bar of [...card.querySelectorAll('.inventory-filter-bar,.sku-filter-bar,.order-filters')].filter(n=>n.closest('.data-card,.table-widget-surface,.receive-lines,.desk-card,[data-table-composition]')===card)){
  const strip=bar.closest('.inventory-filter-row,.order-tab-panel')||bar.closest('.table-tab-overflow')||bar;if(strip!==bar)bar.classList.remove('table-status-strip');strip.classList.add('table-status-strip');
  if(header&&card.contains(strip)&&!header.contains(strip))header.after(strip);
  const tabs=[...bar.children].filter(el=>el.matches('a,button')&&el.querySelector('b,.order-tab-count'));
  for(const tab of tabs){for(const node of tab.childNodes)if(node.nodeType===3&&node.textContent.trim()){const text=node.textContent;node.textContent=text.replace(/Buybox Lost/g,'Buybox lost').replace(/Out Of Stock/g,'Out of stock');}}
  const tone=tab=>tab.matches('.sku-alert-tab,.order-alert-tab,[data-filter="BLOCKED"],[data-status="oos"],[data-status="overstock"]')?'red':tab.matches('.sku-info-tab,.order-informative-tab,[data-filter="ACT_SOON"],[data-status="low"]')?'amber':'blue';
  for(const tab of tabs){if(bar.matches('.order-filters'))tab.hidden=false;const kind=tone(tab);tab.dataset.tabTone=kind;const count=tab.querySelector('b')||tab.querySelector('.order-tab-count');if(!bar.matches('.order-filters')&&kind==='red'&&count&&/^0(?:\s*\/\s*0)?$/.test(count.textContent.trim())){const active=tab.matches('.active,[aria-current="page"]');tab.hidden=true;if(active&&!bar.querySelector('.table-hidden-status-clear')){const clear=document.createElement('a');clear.className='secondary-button compact-button table-hidden-status-clear';clear.textContent='Clear filter';const url=new URL(location.href);url.searchParams.delete('status');url.searchParams.delete('page');clear.href=url.href;bar.append(clear)}}}
  // Reuse the Orders tab/count component without changing domain-owned nodes or handlers.
  bar.classList.add('table-status-tabs');
  for(const tab of tabs){
   tab.classList.add('table-status-tab');
   let badge=tab.querySelector('.order-tab-count');
   if(!badge){const count=tab.querySelector(':scope>b');if(count){badge=document.createElement('span');badge.className='order-tab-count';count.before(badge);badge.append(count);}}
   badge?.classList.add('table-status-count');
  }
  // Stable within each semantic group; no new business classifications.
  tabs.sort((a,b)=>['blue','red','amber'].indexOf(a.dataset.tabTone)-['blue','red','amber'].indexOf(b.dataset.tabTone)).forEach(tab=>bar.append(tab));
  const selected=new URL(location.href).searchParams.get('status');
  if(selected&&!['ALL','all','forecast'].includes(selected)&&!tabs.some(tab=>!tab.hidden&&tab.matches('.active,[aria-current="page"]'))&&!bar.querySelector('[data-orders-clear-status],.table-hidden-status-clear')){const clear=document.createElement('a');clear.className='secondary-button compact-button table-hidden-status-clear';clear.textContent='Clear filter';const url=new URL(location.href);url.searchParams.delete('status');url.searchParams.delete('page');clear.href=url.href;bar.append(clear);}
 }
}
function headers(root){return [...root.querySelectorAll('thead th,.table-grid-header [data-column]')].map((el,index)=>({id:el.dataset.column||'column-'+index,title:el.dataset.title||normalize(el.textContent).replace(/[↑↓]/g,''),el,index})).sort((a,b)=>Number(a.el.style.order||a.index)-Number(b.el.style.order||b.index));}
function rows(root){return root.matches('table')?[...root.tBodies].flatMap(body=>[...body.rows]).filter(row=>![...row.cells].some(cell=>cell.colSpan>1)):[...root.querySelectorAll(root.dataset.gridRow||'.order-item')];}
function textOf(cell){
  if(!cell)return '';
  if(cell.dataset.exportValue!==undefined)return cell.dataset.exportValue;
  const copy=cell.cloneNode(true);
  if(copy.matches('.item-number'))copy.querySelector(':scope > small')?.remove();
  copy.querySelectorAll('svg,img,script,style,.sr-only,.marketplace-links,.product-image-upload,.product-avatar,[hidden]').forEach(el=>el.remove());
  copy.querySelectorAll('button').forEach(el=>el.replaceWith(document.createTextNode(el.textContent)));
  copy.querySelectorAll('input,select,textarea').forEach(el=>el.replaceWith(document.createTextNode(el.type==='checkbox'?(el.checked?'Yes':'No'):el.value)));
  copy.querySelectorAll('div,p,small,strong,span,code,br').forEach(el=>el.append(document.createTextNode(' ')));
  return normalize(copy.textContent);
}
function columnCell(root,row,col){
 if(root.dataset.tableWidget==='orders'){
  const selectors={product:'.item-product',order:'.item-order-reference',sales:'.item-sales',available:'.item-number:has(>small)', 'four-week-sales':'.item-weekly-sales',profit:'.item-profit',action:'.item-actions'};
  if(col.id==='quantity'||col.id==='available')return [...row.querySelectorAll(':scope>.item-number')].find(cell=>cell.querySelector(':scope>small')?.textContent.trim()===(col.id==='quantity'?'Qty':'Available'))||row.querySelector(':scope>[data-column="'+col.id+'"]');
  if(selectors[col.id])return row.querySelector(selectors[col.id]);
 }
 return [...row.children].find(cell=>cell.dataset.column===col.id)||row.children[col.index];
}
function values(root,row,cols){return cols.map(col=>textOf(columnCell(root,row,col)));}
// Export logical fields, not the concatenated text of visual compound cells.
const shopifyOrderExportFields={product:['Product','SKU','Mapped items'],order:['Order ID','Order date','Payment status','Fulfillment status','Cancelled','Readiness'],quantity:['Ordered marketplace units','Unfulfilled marketplace units'],sales:['Item sales','Currency'],available:['Warehouse available marketplace units'],profit:['Estimated profit','Calculation state'],'four-week-sales':['Week 1 current units','Week 2 current units','Week 3 current units','Week 4 current units']};
const orderExportSchema=root=>root.dataset.provider==='SHOPIFY'?shopifyOrderExportFields:orderExportFields;
const orderExportFields={product:['Product','SKU','ASIN','Mapped items'],order:['Order ID','Order date','Amazon status','Fulfillment','Readiness'],sales:['Item sales','Currency','Customer shipping','Buy Box','Buy Box currency'],profit:['Estimated item profit','Product cost','Referral fee','Other cost','Shipping cost'],'four-week-sales':['4-week orders','4-week units','Week 1 units','Week 2 units','Week 3 units','Week 4 units','Refund totals by currency','Refunded orders']};
async function prepareExport(root,cols){
 if(root.dataset.provider==='SHOPIFY'||root.dataset.tableWidget!=='orders'||!cols.some(col=>col.id==='profit'))return;
 const items=rows(root),keys=[...new Set(items.map(row=>row.dataset.exportOrder).filter(Boolean))];
 for(let start=0;start<keys.length;start+=100){
  const params=new URLSearchParams({kind:'ORDER'});keys.slice(start,start+100).forEach(key=>params.append('key',key));
  const response=await fetch('/app/inventory/profit?'+params,{credentials:'same-origin',headers:{Accept:'application/json'}});
  if(!response.ok)throw new Error('Profit data unavailable. Retry the export.');const views=await response.json();
  for(const row of items){const view=views[row.dataset.exportOrder];if(!view||!view.totals||/cancel/i.test(row.dataset.exportStatus||''))continue;const sku=row.dataset.exportSku,fees=orderSkuFees(view,sku);row.dataset.exportProfit=orderSkuProfit(view,sku)??'Pending';for(const [key,value]of Object.entries(fees))row.dataset['exportFee'+key]=value??'Pending';}
 }
}
const inventoryExportFields={product:['Product','Brand','Account SKU','Vendor item code','UPC / EAN / identifier','ASIN'],location:['Location code','Location name'],'on-hand':['On hand quantity'],reserved:['Reserved quantity'],'physical-available':['Available quantity'],expiration:['Expiration date'],'shelf-life':['Shelf-life status'],'unit-cost':['Unit cost','Currency']};
function exportColumns(root,cols){
 if(root.dataset.tableWidget==='available-inventory')return cols.filter(col=>!['record-context','actions'].includes(col.id)).flatMap(col=>(inventoryExportFields[col.id]||[col.title]).map((title,field)=>({...col,title,field,numeric:['on-hand','reserved','physical-available'].includes(col.id)||(col.id==='unit-cost'&&field===0)})));
 return cols.filter(col=>!['Record','Actions','Select'].includes(normalize(col.title))).flatMap(col=>(root.dataset.tableWidget==='orders'&&orderExportSchema(root)[col.id]||[col.title]).map((title,field)=>({...col,title,field})));}
function exportValues(root,row,cols){
 const data=row.dataset;
 if(root.dataset.tableWidget==='available-inventory'){
  const fields={product:[data.product,data.exportBrand,data.exportAccountSku,data.exportVendorCode,data.exportIdentifier,data.exportAsin],location:[data.locationCode,data.exportLocationName],'on-hand':[data.exportOnHand],reserved:[data.exportReserved],'physical-available':[data.exportAvailable],expiration:[data.expiration],'shelf-life':[data.status],'unit-cost':[data.exportUnitCost,data.exportCurrency]};
  return cols.map(col=>inventoryExportFields[col.id]?(fields[col.id][col.field]??''):textOf(columnCell(root,row,col)));
 }

 const weekly=String(row.querySelector('.item-weekly-sales')?.dataset.exportWeeks||row.querySelector('.weekly-sales')?.textContent||'').split('|').map(normalize);
 if(root.dataset.provider==='SHOPIFY'){
  const fields={product:[data.exportProduct,data.exportSku,data.exportMapping],order:[data.exportOrder,data.exportDate,data.exportPayment,data.exportStatus,data.exportCancelled,data.exportReadiness],quantity:[data.exportQuantity,data.exportOpen],sales:[data.exportSales,data.exportCurrency],available:[data.exportAvailable],profit:['','Fees unavailable'],'four-week-sales':Array.from({length:4},(_,i)=>weekly[i]||'')};
  return cols.map(col=>shopifyOrderExportFields[col.id]?(fields[col.id][col.field]??''):textOf(columnCell(root,row,col)));
 }
 const totals=String(row.querySelector('.weekly-sold-totals>span')?.textContent||'').split('/').map(normalize);
 let refunds=[];try{refunds=JSON.parse(row.querySelector('[data-sku-refunds]')?.dataset.skuRefunds||'[]')}catch{}
 const currencies=[...new Set(refunds.map(entry=>entry.currency).filter(Boolean))];
 const refundTotals=currencies.map(currency=>currency+' '+refunds.filter(entry=>entry.currency===currency).reduce((sum,entry)=>sum+Number(entry.amount||0),0).toFixed(2)).join(' / ')+(refunds.some(entry=>entry.unknownAmounts||entry.amount==null)?' + Pending':'');
 const fields={product:[data.exportProduct,data.exportSku,data.exportAsin,data.exportMapping],order:[data.exportOrder,data.exportDate,data.exportStatus,data.exportChannel,data.exportReadiness],sales:[data.exportSales,data.exportCurrency,data.exportShipping,data.exportBuybox,data.exportBuyboxCurrency],profit:[data.exportProfit??'Pending',data.exportFeeproduct??'Pending',data.exportFeereferral??'Pending',data.exportFeeother??'Pending',data.exportFeeshipping??'Pending'],'four-week-sales':[totals[0]||'',totals[1]||'',...Array.from({length:4},(_,i)=>weekly[i]||''),refundTotals,refunds[0]?.totalOrders??'']};
 if(/cancel/i.test(data.exportStatus||'')){fields.sales=fields.sales.map(()=>'');fields.profit=fields.profit.map(()=>'');fields['four-week-sales']=fields['four-week-sales'].map(()=>'');}
 return cols.map(col=>root.dataset.tableWidget==='orders'&&orderExportFields[col.id]?(fields[col.id][col.field]??''):textOf(columnCell(root,row,col)));
}
// Keep the first visible record at the same viewport offset when page size changes.
function pageSizeAnchor(elements){
  const visible=elements.filter(el=>el.getClientRects().length&&!el.classList.contains('table-data-hidden'));
  const element=visible.find(el=>el.getBoundingClientRect().bottom>0)||visible.at(-1);
  return {element,index:Math.max(0,elements.indexOf(element)),top:element?.getBoundingClientRect().top||0};
}
function restoreRowOffset(element,top){
  if(!element)return;
  let scroller=element.parentElement;
  while(scroller&&scroller!==document.body){if(/auto|scroll/.test(getComputedStyle(scroller).overflowY)&&scroller.scrollHeight>scroller.clientHeight+1)break;scroller=scroller.parentElement;}
  const delta=element.getBoundingClientRect().top-top;
  if(scroller&&scroller!==document.body)scroller.scrollTop+=delta;else window.scrollBy(0,delta);
}
function paginationTotal(text,kind){
  text=text.trim();
  const total=text.match(/^([\d,]+)\s+(documents|orders|items|results|movements)\b/)||text.match(/^Showing\b.*?\bof\s+([\d,]+)(?:\s+(\w+))?\s*$/);
  if(total)return 'Total: '+Number(total[1].replace(/,/g,'')).toLocaleString()+' '+(total[2]||(kind==='orders'?'orders':'results'));
  return /^No movements$/i.test(text)?'Total: 0 movements':text;
}
// One pager presentation for both local collections and server-backed lists.
function paginationControls(nav,onSize,onPage){
  nav.setAttribute('aria-label','Table pages');
  const sizeLabel=document.createElement('label');sizeLabel.textContent='Showing ';sizeLabel.className='table-page-size';
  const select=document.createElement('select');select.setAttribute('aria-label','Rows per page');select.setAttribute('data-standard-choice','');
  [25,50,100].forEach(size=>select.add(new Option(String(size),String(size))));sizeLabel.append(select,' rows');
  const context=document.createElement('div');context.className='table-page-context';
  const summary=nav.previousElementSibling;nav.before(context);context.append(sizeLabel);if(summary)context.append(summary);
  select.onchange=()=>onSize(Number(select.value));
  let current=1,last=1;
  const buttons=[];
  for(const [label,delta] of [['First',-Infinity],['Previous',-1],['Next',1],['Last',Infinity]]){
    const button=document.createElement('button');button.type='button';button.className='secondary-button compact-button';button.textContent=label;button.dataset.pageDelta=String(delta);
    button.onclick=()=>onPage(delta===-Infinity?1:delta===Infinity?last:current+delta);nav.append(button);buttons.push(button);
  }
  const jump=document.createElement('label');jump.className='table-local-page';jump.append('Page ');
  const input=document.createElement('input');input.type='number';input.min='1';input.setAttribute('aria-label','Page number');
  input.onchange=()=>onPage(Math.max(1,Math.min(last,Math.floor(Number(input.value)||1))));
  input.onkeydown=event=>{if(event.key==='Enter'){event.preventDefault();input.onchange()}};
  const total=document.createElement('span');jump.append(input,total);buttons[2].before(jump);
  return (page,pages,size)=>{current=page;last=pages;input.value=String(page);input.max=String(pages);select.value=String(size);window.NextAiPlatformControls?.enhance(context);select.dispatchEvent(new Event('platform-choice-sync'));total.textContent='of '+pages;buttons.forEach((button,index)=>button.disabled=index<2?page===1:page===pages)};
}
function filterMatch(value,filter){
 if(!filter)return true;
 if(typeof filter==='string')return value.toLowerCase().includes(filter.toLowerCase());
 const raw=value.replace(/,/g,'');
 let number;
 if(filter.type==='date'){const match=raw.match(/(\d{2})\/(\d{2})\/(\d{2,4})/);number=match?Date.parse((match[3].length===2?'20':'')+match[3]+'-'+match[1]+'-'+match[2]):NaN;}
 else{const match=raw.match(/[-+]?\d+(?:\.\d+)?/);number=match?Number(match[0]):NaN;}
 const parse=v=>filter.type==='date'?Date.parse(v):Number(v);
 return Number.isFinite(number)&&(!filter.min||number>=parse(filter.min))&&(!filter.max||number<=parse(filter.max));
}
// Marketplace-only typed snapshot contract. Other table exporters retain their adapters.
async function marketplaceCsv(columns,filters,shopify=false){
 const url=new URL(shopify?'/app/marketplace-skus/shopify/export.csv':'/app/marketplace-skus/export.csv',location.origin);
 const current=new URL(location.href);
 for(const [key,value]of current.searchParams)if(['q','status','sort','direction'].includes(key)||key.startsWith('f_'))url.searchParams.set(key,value);
 url.searchParams.set('columns',columns.map(c=>c.id).join(','));
 url.searchParams.set('columnFilters',JSON.stringify(filters));
 const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),60000);
 try{
  const response=await fetch(url,{credentials:'same-origin',cache:'no-store',signal:controller.signal,headers:{Accept:'text/csv'}});
  if(!response.ok){let message='CSV preparation failed. No complete download was produced. Please retry.';try{const problem=await response.json();if(typeof problem.detail==='string'&&problem.detail.length<300)message=problem.detail;}catch{}throw new Error(message);}
  const count=response.headers.get('X-CSV-Rows'),size=Number(response.headers.get('Content-Length'));
  if(!response.headers.get('Content-Type')?.toLowerCase().startsWith('text/csv')||response.headers.get('X-CSV-Schema')!==(shopify?'shopify-skus-v1':'marketplace-skus-v1')||!/^\d+$/.test(count||'')||Number(count)>10000||!Number.isSafeInteger(size)||size<3||size>50*1024*1024)throw new Error('Invalid CSV response. No download was started.');
  const blob=await response.blob();if(blob.size!==size)throw new Error('Incomplete CSV response. No download was started.');
  const filename=response.headers.get('Content-Disposition')?.match(/filename="(marketplace-skus-\d{8}T\d{6}Z\.csv)"/)?.[1];
  if(!filename)throw new Error('Invalid CSV filename. No download was started.');
  const objectUrl=URL.createObjectURL(blob);try{const link=document.createElement('a');link.href=objectUrl;link.download=filename;link.click();}finally{setTimeout(()=>URL.revokeObjectURL(objectUrl),1000);}
  return{rows:Number(count)};
 }catch(error){if(error.name==='AbortError')throw new Error('Export timed out or was cancelled. Narrow the filters and retry.');throw error;}finally{clearTimeout(timer);}
}
function init(root){
  if(root.dataset.dataToolsReady)return;
  const cols=headers(root);if(!cols.length)return;
  const card=root.closest('.table-widget-surface,.data-card,.receive-lines,.desk-card')||root.parentElement;
  const owned=[...card.querySelectorAll('[data-column-control]')].find(el=>el.dataset.columnControl===root.dataset.tableWidget&&el.closest('.table-widget-surface,.data-card,.receive-lines,.desk-card')===card);
  const duplicate=[...document.querySelectorAll('[data-table-widget]')].filter(el=>el.dataset.tableWidget===root.dataset.tableWidget).length>1;
  const host=owned||(!duplicate?[...document.querySelectorAll('[data-column-control]')].find(el=>el.dataset.columnControl===root.dataset.tableWidget):null);
  if(!host)return;
  root.dataset.dataToolsReady='true';
  card.classList.add('table-filter-standard');
  window.NextAiTableFirstPaint?.capture(card);
  let serverPager=card.querySelector('.table-pagination');
  if(root.dataset.tableWidget==='receiving-lines'){serverPager?.remove();serverPager=null;root.querySelectorAll('tbody tr').forEach(row=>row.hidden=false)}
  const isServer=!!serverPager&&(serverPager.dataset.page!==undefined||!!serverPager.querySelector('a[href],form[data-page-jump]'));
  const sizeKey='nextai.table.rows.'+root.dataset.tableWidget;
  const positionKey=sizeKey+'.position';
  const serverRows=()=>root.matches('table')?rows(root):[...root.querySelectorAll('.order-row')].length?[...root.querySelectorAll('.order-row')]:rows(root);
  const rememberSize=value=>{document.cookie=encodeURIComponent(sizeKey)+'='+value+'; Max-Age=31536000; Path=/; SameSite=Lax'+(location.protocol==='https:'?'; Secure':'');try{localStorage.setItem(sizeKey,String(value))}catch{}};
  let storedSize=Number(document.cookie.split('; ').find(value=>value.startsWith(encodeURIComponent(sizeKey)+'='))?.split('=')[1]);
  if(![25,50,100].includes(storedSize)){try{storedSize=Number(localStorage.getItem(sizeKey))}catch{}}
  const hasPreference=[25,50,100].includes(storedSize),preferredSize=hasPreference?storedSize:25;
  if(hasPreference)rememberSize(preferredSize);
  const saved=(duplicate?structuredClone(states.get(root.dataset.tableWidget)):states.get(root.dataset.tableWidget))||{page:1,size:25,query:'',filters:{}};
  saved.size=preferredSize;
  states.set(root.dataset.tableWidget,saved);
  let draftFilters=structuredClone(saved.filters||{}),draftSession=null;
  const toolbar=card.querySelector(':scope > [data-table-toolbar]')||document.createElement('div');toolbar.className='table-standard-toolbar';
  const container=root.closest('.table-wrap,.receive-table-wrap')||root;
  container.before(toolbar);toolbar.append(host);host.classList.add('table-widget-tools');
  let search=card.querySelector('input[type="search"]:not(.table-column-search input)');
  // Reuse existing search handlers (including catalogue lookups and server queries).
  if(search){
    const form=search.closest('form');
    const searchGroup=form||search.closest('label')||search;
    if(!searchGroup.contains(root)&&!searchGroup.contains(toolbar))toolbar.prepend(searchGroup);
    if(!search.getAttribute('aria-label'))search.setAttribute('aria-label','Search table');
  }else{
    const label=document.createElement('label');label.className='table-standard-search';
    label.innerHTML='<input type="search" aria-label="Search table" placeholder="Search this table">';
    toolbar.prepend(label);search=label.querySelector('input');search.value=saved.query;
    search.addEventListener('input',()=>{saved.query=search.value;saved.page=1;render()});
  }
  if(isServer&&(root.dataset.tableWidget==='orders'||root.dataset.tableLiveSearch==='true'))bindServerSearch(search);
  // One search presentation, retaining the original input and its domain listeners.
  let searchLabel=search.closest('label');
  if(!searchLabel){searchLabel=document.createElement('label');search.before(searchLabel);searchLabel.append(search)}
  searchLabel.classList.add('table-standard-search');
  searchLabel.querySelectorAll(':scope > svg,:scope > span').forEach(icon=>icon.remove());
  searchLabel.insertAdjacentHTML('afterbegin','<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 4.5 4.5"/></svg>');
  toolbar.classList.add('table-widget-tools');
  const filters=document.createElement('details');filters.className='table-filter-control';
  const summary=document.createElement('summary');summary.className='secondary-button compact-button';summary.setAttribute('aria-label','Filters');summary.setAttribute('aria-expanded','false');summary.dataset.tooltip='Filters';summary.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 4h18l-7 8v7l-4 2v-9L3 4"/></svg>';filters.append(summary);
  const panel=document.createElement('div');panel.className='table-filter-panel';filters.append(panel);
  const hint=document.createElement('p');hint.textContent=isServer?'Filters loaded rows. Search above searches all results.':'Match values in one or more columns.';panel.append(hint);
  const filterCols=cols.filter(col=>!['action','actions','record-context','selection','select'].includes(col.id));
  filterCols.forEach(col=>{
    const label=document.createElement('label');label.textContent=col.title;
    const standard=document.body.classList.contains('platform-standard-tables');
    const kind=standard&&/^(quantity|on hand|reserved|physical available|unit cost|current buying cost|freight prefill|default discount|products|expected|received)$/i.test(col.title.trim().replace(/\s+i$/,''))?'number':standard&&/^(expiration|uploaded|date & time)$/i.test(col.title.trim())?'date':'search';
    if(kind!=='search'){
      const range=document.createElement('div');range.className='standard-filter-range';
      ['min','max'].forEach(bound=>{const input=document.createElement('input');input.type=kind;input.dataset.filterColumn=col.id;input.dataset.filterBound=bound;if(kind==='number')input.step='any';input.placeholder=bound==='min'?'From':'To';input.setAttribute('aria-label',col.title+' '+input.placeholder);input.value=draftFilters[col.id]?.[bound]||'';input.oninput=()=>{const current=typeof draftFilters[col.id]==='object'?draftFilters[col.id]:{type:kind,min:'',max:''};current[bound]=input.value;draftFilters[col.id]=current.min||current.max?current:'';draftSession?.update(draftFilters)};range.append(input)});label.append(range);
    }else{
      const input=document.createElement('input');input.type='search';input.dataset.filterColumn=col.id;input.placeholder='Contains…';input.value=draftFilters[col.id]||'';
      input.setAttribute('aria-label','Filter '+col.title);input.addEventListener('input',()=>{draftFilters[col.id]=input.value;draftSession?.update(draftFilters)});label.append(input);
    }panel.append(label);
  });
  const clear=document.createElement('button');clear.type='button';clear.className='secondary-button compact-button';clear.textContent='Clear filters';
  clear.onclick=()=>{if(draftSession)draftSession.clear();else{saved.filters={};panel.querySelectorAll('input').forEach(input=>input.value='');saved.page=1;render()}};panel.append(clear);
  const renderDraft=values=>{draftFilters=structuredClone(values);panel.querySelectorAll('input[data-filter-column]').forEach(input=>{const value=draftFilters[input.dataset.filterColumn];input.value=input.dataset.filterBound?value?.[input.dataset.filterBound]||'':typeof value==='string'?value:'';});};
  draftSession=window.NextAiFilterDrafts?.create({readApplied:()=>saved.filters||{},renderDraft,emptyState:()=>({}),commit:values=>{for(const input of panel.querySelectorAll('input'))if(!input.reportValidity())return false;saved.filters=structuredClone(values);saved.page=1;render();return true;}})||null;
  if(draftSession&&!document.body.classList.contains('platform-standard-tables')){clear.remove();draftSession.mountFooter(panel,{close:()=>{filters.open=false;}});}
  const filterCount=document.createElement('span');filterCount.className='table-filter-count';filterCount.hidden=true;summary.append(filterCount);
  const appliedSummary=document.createElement('span');appliedSummary.className='table-applied-filter-summary';appliedSummary.hidden=true;toolbar.append(appliedSummary);
const filterControl=document.createElement('div');filterControl.className='table-filter-icon-control table-generic-filter-control';filterControl.append(filters);host.prepend(filterControl);
  const clearAll=document.createElement('button');clearAll.type='button';clearAll.className='table-clear-active-filters table-filter-reset';clearAll.hidden=true;clearAll.setAttribute('aria-label','Clear table filters');clearAll.dataset.tooltip='Clear filters';clearAll.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"/></svg>';filterControl.append(clearAll);
  function updateActiveFilterState(){const entries=Object.entries(saved.filters).filter(([,value])=>typeof value==='object'?!!(value.min||value.max):!!value),active=entries.length>0;filterCount.hidden=!active;filterCount.textContent=String(entries.length);appliedSummary.hidden=!active;appliedSummary.textContent=entries.map(([id,value])=>(cols.find(c=>c.id===id)?.title||id)+': '+(typeof value==='object'?[value.min||'Any',value.max||'Any'].join('–'):value)).join(' · ');toolbar.classList.toggle('table-has-active-filters',active);filters.classList.toggle('has-active-filters',active);summary.setAttribute('aria-label',active?'Filters, '+entries.length+' applied':'Filters');summary.dataset.tooltip=active?'Filters, '+entries.length+' applied':'Filters';clearAll.hidden=!active;clearAll.disabled=!active;}
  clearAll.onclick=event=>{event.stopPropagation();event.preventDefault();saved.filters={};draftSession?.open();saved.page=1;render()};
  filters.addEventListener('toggle',()=>{if(filters.open)draftSession?.open();else draftSession?.discard();summary.setAttribute('aria-expanded',String(filters.open));if(filters.open){const box=summary.getBoundingClientRect();panel.style.top=Math.min(box.bottom+8,Math.max(12,innerHeight-360))+'px';panel.style.left=Math.max(12,Math.min(box.left,innerWidth-332))+'px'}});
  const close=event=>{if(!root.isConnected){document.removeEventListener('pointerdown',close);return}if(!filters.contains(event.target))filters.open=false};document.addEventListener('pointerdown',close);
  filters.addEventListener('keydown',event=>{if(event.key==='Escape'){filters.open=false;summary.focus()}});
  if(document.body.classList.contains('platform-standard-tables')){
    const drawer=document.createElement('dialog');drawer.className='standard-filter-drawer';drawer.setAttribute('aria-label','Table filters');
    const head=document.createElement('header');head.innerHTML='<h2>Filters</h2><button type="button" aria-label="Close filters">×</button>';drawer.append(head,panel);
    const foot=document.createElement('footer');const done=document.createElement('button');done.type='button';done.className='primary-button';done.textContent='Apply filters';foot.append(clear,done);drawer.append(foot);document.body.append(drawer);
    const closeDrawer=()=>{draftSession?.discard();drawer.close();summary.setAttribute('aria-expanded','false');summary.focus()};head.querySelector('button').onclick=closeDrawer;if(draftSession){foot.remove();clear.remove();draftSession.mountFooter(drawer,{close:closeDrawer});}else done.onclick=closeDrawer;
    summary.onclick=event=>{event.preventDefault();filters.open=false;draftSession?.open();drawer.showModal();summary.setAttribute('aria-expanded','true')};
    drawer.addEventListener('cancel',event=>{event.preventDefault();closeDrawer()});
  }
  const exportButton=document.createElement('button');exportButton.type='button';exportButton.className='secondary-button compact-button table-export';
  exportButton.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5"/></svg>';
  exportButton.setAttribute('aria-label','Download table as CSV');
  exportButton.title=isServer?'Download all matching pages with your visible columns':'Download all filtered rows with your visible columns';host.append(exportButton);composeTable(card,toolbar,host,search);composeTabs(card);window.NextAiTableComposition?.placeCommands(card);
  const status=document.createElement('span');status.className='table-data-status';status.setAttribute('role','status');toolbar.after(status);
  let footer=null,pageLabel=null,updatePager=null;
  if(isServer){
    serverPager.classList.add('table-footer','table-standard-pagination');
    const oldJump=serverPager.querySelector('[name=goToPage]');
    const page=serverPager.dataset.page!==undefined?Number(serverPager.dataset.page)+1:Number(oldJump?.value)||1;
    const pages=Number(serverPager.dataset.pageMax)||Number(oldJump?.dataset.pageMax)||1;
    const size=Number(serverPager.dataset.pageSize)||Number(new URL(location.href).searchParams.get('size'))||25;
    if(hasPreference&&size!==preferredSize){const url=new URL(location.href);url.searchParams.set('size',String(preferredSize));url.searchParams.set('page','0');url.searchParams.delete('goToPage');location.replace(url);return;}
    const summary=serverPager.firstElementChild,nav=document.createElement('nav');
    if(summary){const label=summary.querySelector('span')||summary;label.textContent=paginationTotal(label.textContent,root.dataset.tableWidget);}
    serverPager.replaceChildren(...(summary?[summary]:[]),nav);
    const navigate=(page,size)=>{const url=new URL(location.href);url.searchParams.set('size',String(size));url.searchParams.set('page',String(page-1));url.searchParams.delete('goToPage');location.assign(url)};
    paginationControls(nav,value=>{
      const anchor=pageSizeAnchor(serverRows()),absolute=(page-1)*size+anchor.index,nextPage=Math.floor(absolute/value)+1;
      const next=new URL(location.href);next.searchParams.set('size',String(value));next.searchParams.set('page',String(nextPage-1));next.searchParams.delete('goToPage');
      try{sessionStorage.setItem(positionKey,JSON.stringify({url:next.href,index:absolute%value,top:anchor.top,at:Date.now()}));}catch{}
      rememberSize(value);navigate(nextPage,value);
    },value=>navigate(value,size))(page,pages,size);
    try{
      const position=JSON.parse(sessionStorage.getItem(positionKey)||'null');
      if(position){sessionStorage.removeItem(positionKey);if(position.url===location.href&&Date.now()-position.at<30000){
        const restore=()=>requestAnimationFrame(()=>requestAnimationFrame(()=>restoreRowOffset(serverRows()[position.index],position.top)));
        if(document.readyState==='complete')restore();else window.addEventListener('load',restore,{once:true});
      }}
    }catch{}
  }else if(!serverPager){
    footer=document.createElement('div');footer.className='table-footer table-pagination table-standard-pagination';container.after(footer);
    pageLabel=document.createElement('span');pageLabel.setAttribute('aria-live','polite');footer.append(pageLabel);
    const nav=document.createElement('nav');footer.append(nav);
    updatePager=paginationControls(nav,size=>{
      const filtered=rows(root).filter(row=>domainVisible(row)&&matches(row)),anchor=pageSizeAnchor(filtered);
      rememberSize(size);saved.size=size;saved.page=Math.floor(anchor.index/size)+1;render();
      requestAnimationFrame(()=>restoreRowOffset(anchor.element,anchor.top));
    },page=>{saved.page=page;render()});
    const previousFooter=card.querySelector('.table-footer:not(.table-pagination)');
    if(previousFooter&&/^Showing\b/.test(normalize(previousFooter.textContent))){previousFooter.querySelectorAll('a').forEach(link=>footer.append(link));previousFooter.hidden=true}
  }
  // Fully loaded catalogue collections only; server sorting/paging stays authoritative.
  if(!isServer&&root.hasAttribute('data-table-client-sort')){
   const collator=new Intl.Collator(undefined,{numeric:true,sensitivity:'base'});
   for(const col of cols){
    if(col.el.dataset.noSort==='true'||col.el.hasAttribute('data-context-actions')||col.id==='record-context'||col.el.querySelector('a,input,button:not(.column-info)'))continue;
    col.el.classList.add('sortable-column');
    const button=document.createElement('button');button.type='button';button.className='table-sort-trigger';button.textContent=col.title.replace(/\s+i$/,'');
    button.title='Sort '+col.title;button.setAttribute('aria-label',button.title);col.el.replaceChildren(button,...col.el.querySelectorAll('.column-info'));
    button.onclick=()=>{
     const descending=col.el.getAttribute('aria-sort')==='ascending';
     for(const other of cols)other.el.removeAttribute('aria-sort');col.el.setAttribute('aria-sort',descending?'descending':'ascending');
     const body=root.tBodies[0];rows(root).sort((a,b)=>collator.compare(textOf(columnCell(root,a,col)),textOf(columnCell(root,b,col)))*(descending?-1:1)).forEach(row=>body.append(row));
     if(!isServer)saved.page=1;render();
    };
   }
  }
  let searchValues=new WeakMap();
  function matches(row){
    // The default view needs no text extraction. Filtered views reuse text until
    // that row changes, instead of cloning every cell on every keystroke.
    if(!saved.query&&!Object.values(saved.filters).some(Boolean))return true;
    let data=searchValues.get(row);if(!data){data=values(root,row,cols);searchValues.set(row,data);}
    return (!saved.query||data.join(' ').toLowerCase().includes(saved.query.toLowerCase()))&&cols.every((col,index)=>filterMatch(data[index],saved.filters[col.id]));
  }
  function domainVisible(row){return !row.hidden&&row.style.display!=='none'&&!row.closest('[hidden]');}
  function render(){
    if(root.dataset.gridRow){const current=new Map(headers(root).map(c=>[c.id,c.el])),byIndex=new Map(cols.map(c=>[c.index,c]));rows(root).forEach(row=>[...row.children].forEach((cell,index)=>{const col=byIndex.get(index);if(!col)return;if(cell.dataset.column!==col.id)cell.dataset.column=col.id;const header=current.get(col.id),hidden=!!header?.hidden,order=header?.style.order||String(index);if(cell.hidden!==hidden)cell.hidden=hidden;if(cell.style.order!==order)cell.style.order=order}))}
    const all=rows(root),filtered=all.filter(row=>domainVisible(row)&&matches(row));
    const pages=Math.max(1,Math.ceil(filtered.length/saved.size));saved.page=Math.min(pages,Math.max(1,saved.page));
    const shown=new Set(footer?filtered.slice((saved.page-1)*saved.size,saved.page*saved.size):filtered);
    all.forEach(row=>row.classList.toggle('table-data-hidden',!shown.has(row)));
    if(!root.matches('table'))root.querySelectorAll('.order-row').forEach(row=>row.classList.toggle('table-data-hidden',![...row.querySelectorAll('.order-item')].some(item=>!item.classList.contains('table-data-hidden'))));
    const count=Object.values(saved.filters).filter(Boolean).length;updateActiveFilterState();
    const orderEmpty=root.querySelector('.order-empty-state');
    if(orderEmpty){const hide=filtered.length>0;if(orderEmpty.hidden!==hide)orderEmpty.hidden=hide;root.classList.toggle('orders-is-empty',!hide);if(serverPager&&serverPager.hidden===hide)serverPager.hidden=!hide;}
    if(!exportButton.disabled)status.textContent=!filtered.length?(orderEmpty||!all.length?'':'Nothing found. Try another search or change your filters.'):count&&isServer?filtered.length+' matches on this page.':'';
    if(footer){footer.dataset.pages=String(pages);updatePager(saved.page,pages,saved.size);pageLabel.textContent='Total: '+filtered.length.toLocaleString()+' results'}
  }
  exportButton.onclick=async()=>{
    exportButton.disabled=true;status.textContent='Preparing CSV…';
    const progress=window.NextAiBackgroundJobs?.start('Download '+root.dataset.tableWidget,'Preparing CSV. Keep this page open until the download starts.');
    try{
      const selected=headers(root).filter(col=>!col.el.hidden&&!['select','action','actions','record-context'].includes(col.id));
      if(root.dataset.tableWidget==='marketplace-skus'||root.dataset.tableWidget==='shopify-marketplace-skus'){
        const result=await marketplaceCsv(selected,saved.filters,root.dataset.tableWidget==='shopify-marketplace-skus');
        status.textContent='CSV prepared: '+result.rows+' rows. Download started.';
        progress?.complete(status.textContent);return;
      }
      const exported=exportColumns(root,selected);
      const exportFilters={...saved.filters};
      let data=[];
      if(isServer){
        // Fetch only the existing authenticated list route. Never call sync/action endpoints.
        let url=new URL(location.href);const exportSize=serverPager.dataset.pageSize||url.searchParams.get('size')||'25';url.searchParams.set('size',exportSize);url.searchParams.set('page','0');url.searchParams.delete('goToPage');const visited=new Set();
        for(let page=0;url;page++){
          if(page>=1000||visited.has(url.href))throw new Error('Too many pages. Narrow the search and try again.');visited.add(url.href);
          const response=await fetch(url,{credentials:'same-origin',headers:{Accept:'text/html'}});if(!response.ok||new URL(response.url).pathname!==location.pathname)throw new Error('Session or page unavailable. Refresh and try again.');
          const doc=new DOMParser().parseFromString(await response.text(),'text/html');
          const source=[...doc.querySelectorAll('[data-table-widget]')].find(el=>el.dataset.tableWidget===root.dataset.tableWidget);
          if(!source)throw new Error('Could not read table data. Please refresh and try again.');
          const sourceCols=headers(source);sourceCols.forEach((col,index)=>{if(!col.el.dataset.column)col.id=cols.find(c=>c.title===col.title)?.id||col.id;col.index=index});
          const mapped=selected.map(col=>({...col,index:sourceCols.find(c=>c.id===col.id)?.index??col.index}));
          await prepareExport(source,mapped);
          for(const row of rows(source)){
            const allValues=values(source,row,sourceCols);
            if(sourceCols.every((col,index)=>filterMatch(allValues[index],exportFilters[col.id])))data.push(exportValues(source,row,exportColumns(source,mapped)));
          }
          const next=[...(source.closest('.data-card')||doc).querySelectorAll('.table-pagination a[href]')].find(link=>normalize(link.textContent).toLowerCase()==='next');
          url=next?new URL(next.getAttribute('href'),location.href):null;
          if(url&&(url.origin!==location.origin||url.pathname!==location.pathname))throw new Error('Unexpected pagination link. Export stopped.');
          if(url){
            // Server pagination markup may omit browser-added smart filters.
            const pageNumber=url.searchParams.get('page');
            url.search=new URL(location.href).search;
            url.searchParams.delete('goToPage');url.searchParams.set('page',pageNumber||String(page+1));url.searchParams.set('size',exportSize);
          }
          status.textContent='Preparing CSV · '+data.length+' rows…';
          progress?.update(status.textContent);
        }
      }else{await prepareExport(root,selected);data=rows(root).filter(row=>domainVisible(row)&&matches(row)).map(row=>exportValues(root,row,exported));}
      const csv='\uFEFF'+[exported.map(col=>col.title),...data].map(row=>row.map((value,index)=>csvCell(value,exported[index]?.numeric===true)).join(',')).join('\r\n');
      const url=URL.createObjectURL(new Blob([csv],{type:'text/csv;charset=utf-8'}));const link=document.createElement('a');link.href=url;link.download=root.dataset.tableWidget+'-'+new Date().toISOString().slice(0,10)+'.csv';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
      status.textContent='Exported '+data.length+' '+(data.length===1?'row.':'rows.');
      progress?.complete(status.textContent);
    }catch(error){status.textContent='Export failed: '+error.message;progress?.fail(status.textContent)}finally{exportButton.disabled=false}
  };
  // Page-specific search/status handlers can still set hidden without fighting pagination.
  let renderQueued=false;
  const scheduleRender=()=>{if(renderQueued)return;renderQueued=true;queueMicrotask(()=>{renderQueued=false;if(root.isConnected)render();});};
  const rowSelector=root.matches('table')?'tbody tr':root.dataset.gridRow||'.order-item';
  const invalidate=target=>{const element=target.nodeType===1?target:target.parentElement;const row=element?.closest(rowSelector);if(row)searchValues.delete(row);else searchValues=new WeakMap();};
  const observer=new MutationObserver(records=>{if(!root.isConnected){observer.disconnect();return}records.forEach(record=>invalidate(record.target));scheduleRender();});
  observer.observe(root,{subtree:true,childList:true,characterData:true,attributes:true,attributeFilter:['hidden','style','data-export-value']});
  for(const event of ['input','change'])root.addEventListener(event,e=>{invalidate(e.target);scheduleRender();});
  search.addEventListener('input',()=>{if(!isServer&&root.hasAttribute('data-table-client-sort'))saved.query=search.value;saved.page=1;updateActiveFilterState();scheduleRender()});
  root.addEventListener('table:filter',()=>{saved.page=1;render()});
  root.addEventListener('table:refresh',()=>render());
  root.addEventListener('table:reset-filters',()=>{saved.filters={};saved.query='';saved.page=1;panel.querySelectorAll('input').forEach(input=>input.value='');render()});
  const sourceQuery=new URL(location.href).searchParams.get('q');
  if(!isServer&&sourceQuery){saved.query=sourceQuery;search.value=sourceQuery;search.dispatchEvent(new Event('input',{bubbles:true}));}
  render();
  card.setAttribute('data-table-composed','true');
  window.NextAiTableFirstPaint?.complete(card);
}
function refresh(){document.querySelectorAll('[data-table-widget-ready]').forEach(init)}
window.NextAiTableDataTools={bindServerSearch,refresh,presentCommands:(card,toolbar,host,search)=>{composeTable(card,toolbar,host,search,true);window.NextAiTableComposition?.placeCommands(card);},csvCell,exportColumns,exportValues,resetFilters:key=>{const saved=states.get(key);if(saved){saved.filters={};saved.query='';saved.page=1;}}};
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',()=>{window.NextAiTableWidget?.refresh();refresh()});else{window.NextAiTableWidget?.refresh();refresh()}
})();
