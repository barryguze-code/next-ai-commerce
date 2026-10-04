/* Purchasing calculations do not submit purchase orders or vendor requests. */
(()=>{
 'use strict';
 if(typeof document==='undefined')return;
 const rows=[...document.querySelectorAll('[data-replenishment-row]')],basket=new Map();
 const $=s=>document.querySelector(s),text=(tag,value)=>{const n=document.createElement(tag);n.textContent=value;return n;};
 const formatCases=value=>Number.isInteger(value)?String(value):value.toFixed(2);
 const money=value=>new Intl.NumberFormat('en-US',{style:'currency',currency:'USD'}).format(value);
 let toastTimer;
 function notifyAdded(item,existing){
  let toast=$('.rp-basket-toast');if(!toast){toast=text('div','');toast.className='rp-basket-toast';toast.setAttribute('role','status');toast.setAttribute('aria-live','polite');document.body.append(toast);}
  toast.replaceChildren();const image=document.createElement('img');image.src=item.image;image.alt='';toast.append(image);
  const copy=text('div','');copy.append(text('strong',existing?'Already in basket':'Added to basket'),text('span',item.name),text('small',item.each+' each · '+formatCases(item.each/item.pack)+' cases'));toast.append(copy);
  const view=text('button','View basket');view.type='button';view.className='secondary-button';view.onclick=()=>{basketView();$('#rp-basket').showModal();};toast.append(view);
  toast.hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>{toast.hidden=true;},6500);
 }
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
  if(!basket.size)host.append(text('p','Your basket is empty.'));
  const summary=text('p','');summary.className='rp-basket-total';
  const updateSummary=()=>{
   let cents=0,missing=0;basket.forEach(item=>{if(item.cost==null)missing++;else cents+=Math.round(item.each*item.cost*100);});
   summary.textContent=(missing?'Known subtotal: ':'Estimated total: ')+money(cents/100)+(missing?' · '+missing+' item(s) missing USD cost':'')+' · Catalogue costs, before tax and freight';
  };
  const groups=new Map();basket.forEach(item=>{const key=item.vendor+' · '+(item.dc?'DC '+item.dc:'No DC');if(!groups.has(key))groups.set(key,[]);groups.get(key).push(item);});
  groups.forEach((items,vendor)=>{
   host.append(text('h3',(po?'Vendor summary · ':'')+vendor));
   items.forEach(item=>{
    const line=text('div','');line.className='rp-basket-line';
    const image=document.createElement('img');image.src=item.image;image.alt='';image.loading='lazy';line.append(image);
    const name=text('div',item.name);name.className='rp-basket-product';name.append(text('small',item.code+' · '+item.pack+' / case'));line.append(name);
    const field=(label,step)=>{const wrap=text('label',label),input=document.createElement('input');input.type='number';input.min='0';input.max='100000000';input.step=step;input.setAttribute('aria-label',label+' for '+item.name);wrap.append(input);line.append(wrap);return input;};
    const cases=field('Cases','0.01'),each=field('Each','1'),cost=text('strong','');line.append(cost);
    const sync=()=>{cases.value=formatCases(item.each/item.pack);each.value=item.each;cost.textContent=item.cost==null?'Cost pending':money(Math.round(item.each*item.cost*100)/100);updateSummary();};sync();
    const change=(input,isCases)=>{const value=Number(input.value);if(input.value===''||!Number.isFinite(value)||value<0||value>100000000||(!isCases&&!Number.isInteger(value))){input.setCustomValidity('Enter a valid '+(isCases?'case quantity':'whole number of each')+'.');input.reportValidity();return;}input.setCustomValidity('');item.each=isCases?Math.round(value*item.pack):value;sync();};
    cases.onchange=()=>change(cases,true);each.onchange=()=>change(each,false);
    const remove=text('button','Remove');remove.type='button';remove.className='rp-text-button';remove.setAttribute('aria-label','Remove '+item.name);remove.onclick=()=>{basket.delete(item.id);basketView(po);};line.append(remove);host.append(line);
   });
  });
  if(basket.size){updateSummary();host.append(summary,text('small','Each is a whole unit; fractional cases display to 2 decimals. Case edits round to the nearest whole each.'));}
  if(po&&basket.size)host.append(text('p','Preview only — nothing is ordered or sent to a vendor.'));
  $('[data-basket-count]').textContent=basket.size;$('[data-po-preview]').disabled=!basket.size;
 }
 document.addEventListener('click',event=>{
  const open=event.target.closest('[data-skus-open]');if(open){toggleSkus(open.dataset.skusOpen);return;}
  const skuTable=event.target.closest('.rp-sku-details');
  if(skuTable&&!event.target.closest('a,button,input,select,textarea')){toggleSkus(skuTable.id.slice('rp-skus-'.length));return;}
  if(event.target.closest('[data-close-dialog]'))event.target.closest('dialog')?.close();
  const setting=event.target.closest('[data-settings-open]');if(setting)settings(setting.dataset.settingsOpen);
  if(event.target.closest('[data-add-basket]')){const d=event.target.closest('[data-replenishment-row]').dataset;if(+d.cases>0&&+d.pack>0){const existing=basket.has(d.item);if(!existing)basket.set(d.item,{id:d.item,name:d.name,code:d.code||'',image:d.image,vendor:d.vendor,dc:d.dc,pack:+d.pack,each:Math.round(+d.cases*+d.pack),cost:d.cost!=null&&d.cost!==''&&Number.isFinite(+d.cost)&&+d.cost>=0?+d.cost:null});basketView();notifyAdded(basket.get(d.item),existing);}}
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
