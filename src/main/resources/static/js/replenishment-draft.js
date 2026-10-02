/* Local design preview: intentionally no purchase-order or vendor write requests. */
(()=>{
 'use strict';
 if(typeof document==='undefined')return;
 const rows=[...document.querySelectorAll('[data-replenishment-row]')],basket=new Map();
 const $=s=>document.querySelector(s),text=(tag,value)=>{const n=document.createElement(tag);n.textContent=value;return n;};
 const num=input=>Math.max(0,Number(input?.value)||0);
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
 function settings(section){$('#rp-settings').showModal();document.getElementById('rp-'+section)?.scrollIntoView({block:'start'});}
 function basketView(po=false){
  const host=$('[data-basket-body]');host.replaceChildren();
  if(!basket.size)host.append(text('p','Your preview basket is empty.'));
  const groups=new Map();basket.forEach(item=>{const key=item.vendor+' · '+(item.dc?'DC '+item.dc:'No DC');if(!groups.has(key))groups.set(key,[]);groups.get(key).push(item);});
  groups.forEach((items,vendor)=>{
   host.append(text('h3',(po?'Draft PO preview · ':'')+vendor));
   items.forEach(item=>{
    const line=text('div','');line.className='rp-basket-line';const name=text('div',item.name);name.append(text('small',item.pack+' units per case'));line.append(name);
    const input=document.createElement('input');input.type='number';input.min='1';input.step='1';input.value=item.cases;input.setAttribute('aria-label','Cases for '+item.name);line.append(input);
    const total=text('strong',(item.cases*item.pack)+' each');line.append(total);
    input.onchange=()=>{item.cases=Math.max(1,Math.floor(num(input)));input.value=item.cases;total.textContent=(item.cases*item.pack)+' each';};
    const remove=text('button','Remove');remove.type='button';remove.className='rp-text-button';remove.onclick=()=>{basket.delete(item.id);basketView(po);};line.append(remove);host.append(line);
   });
  });
  if(po&&basket.size)host.append(text('p','Preview only — creating or adding to an actual PO, pricing, incoming supply and vendor submission are not enabled in this design draft.'));
  $('[data-basket-count]').textContent=basket.size;$('[data-po-preview]').disabled=!basket.size;
 }
 document.addEventListener('click',event=>{
  const open=event.target.closest('[data-skus-open]');if(open){toggleSkus(open.dataset.skusOpen);return;}
  const skuTable=event.target.closest('.rp-sku-details');
  if(skuTable&&!event.target.closest('a,button,input,select,textarea')){toggleSkus(skuTable.id.slice('rp-skus-'.length));return;}
  if(event.target.closest('[data-close-dialog]'))event.target.closest('dialog')?.close();
  const setting=event.target.closest('[data-settings-open]');if(setting)settings(setting.dataset.settingsOpen);
  if(event.target.closest('[data-add-basket]')){const row=event.target.closest('[data-replenishment-row]'),d=row.dataset;if(+d.cases>0)basket.set(d.item,{id:d.item,name:d.name,vendor:d.vendor,dc:d.dc,pack:+d.pack,cases:+d.cases});basketView();}
  if(event.target.closest('[data-basket-open]')){basketView();$('#rp-basket').showModal();}
  if(event.target.closest('[data-po-preview]'))basketView(true);
 });
 document.addEventListener('keydown',event=>{
  const header=event.target.closest('.rp-sku-head[data-skus-open]');
  if(header&&(event.key==='Enter'||event.key===' ')){event.preventDefault();toggleSkus(header.dataset.skusOpen);}
 });
 if(location.hash==='#vendors')settings('vendors');
 // Wait for shared table setup, then show every item's picture-free SKU lines.
 const openAll=()=>rows.forEach(row=>{if(document.getElementById('rp-skus-'+row.dataset.item)?.hidden)toggleSkus(row.dataset.item);});
 if(document.readyState==='complete')openAll();else window.addEventListener('load',openAll,{once:true});
})();
