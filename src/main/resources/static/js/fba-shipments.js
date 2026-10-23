(()=>{
 const dialog=document.getElementById('fba-shipment-dialog');if(!dialog)return;
 const find=s=>dialog.querySelector(s),text=(tag,value)=>{const node=document.createElement(tag);node.textContent=value??'';return node;};
 let shipment='',page=0,opener,request,version=0;
 async function load(){
  request?.abort();request=new AbortController();const current=++version;
  find('[data-fba-item-status]').textContent='Loading shipment items…';find('[data-fba-items]').replaceChildren();
  find('[data-fba-shipment-cost]')?.remove();
  find('[data-fba-prev]').disabled=find('[data-fba-next]').disabled=true;
  try{
   const response=await fetch('/app/inventory/fba-shipments/items?'+new URLSearchParams({shipment,q:find('input[type=search]').value,page}),{signal:request.signal,headers:{Accept:'application/json'}});
   if(!response.ok)throw Error('Shipment items could not be loaded. Please retry.');
   const result=await response.json();if(current!==version)return;page=result.page;
   for(const item of result.rows){
    const row=text('tr',''),product=text('td',''),identity=text('div',''),picture=text('span',''),copy=text('div','');
    identity.className='fba-item-identity';picture.className='fba-item-picture';copy.className='fba-item-copy';
    if(item.imageUrl){try{const url=new URL(item.imageUrl,location.origin);if(['https:','http:'].includes(url.protocol)){const img=document.createElement('img');img.src=url.href;img.alt='';img.loading='lazy';img.onerror=()=>img.remove();picture.append(img);}}catch(_){}}
    picture.setAttribute('aria-hidden','true');copy.append(text('strong',item.title||'Product title not supplied'),text('small','SKU · '+item.sku),text('small','FNSKU · '+(item.fnsku||'Not supplied')));
    identity.append(picture,copy);product.append(identity);row.append(product,text('td',item.shipped),text('td',item.received));find('[data-fba-items]').append(row);
   }
   let costPanel=find('[data-fba-shipment-cost]');if(!costPanel){costPanel=text('p','');costPanel.dataset.fbaShipmentCost='';find('[data-fba-item-status]').after(costPanel);}
   const cost=result.costs,amount=value=>value==null||!cost?.currency?'Not imported':new Intl.NumberFormat('en-US',{style:'currency',currency:cost.currency,maximumFractionDigits:4}).format(value);
   costPanel.textContent='Shipping to FBA · '+(cost?.perUnit==null?'Charges incomplete':amount(cost.perUnit)+' / shipped unit');
   costPanel.dataset.tooltip=`Transportation: ${amount(cost?.transportation)} · Placement: ${amount(cost?.placement)} · ${cost?.shippedUnits??0} shipped units. Posted net charges; credits reduce costs. Missing charges are not zero.`;costPanel.tabIndex=0;
   const pages=Math.max(1,Math.ceil(result.total/result.size));find('[data-fba-page]').textContent=`Page ${page+1} of ${pages}`;
   find('[data-fba-item-status]').textContent=result.total?`${result.total} matching SKUs · shipped and Amazon-received units are shown separately.`:'No matching items imported yet.';
   find('[data-fba-prev]').disabled=page===0;find('[data-fba-next]').disabled=page+1>=pages;
  }catch(error){if(error.name!=='AbortError'&&current===version)find('[data-fba-item-status]').textContent=error.message;}
 }
 document.addEventListener('click',event=>{const button=event.target.closest('[data-fba-shipment]');if(!button)return;opener=button;shipment=button.dataset.fbaShipment;page=0;find('input[type=search]').value='';find('#fba-shipment-title').textContent=shipment;dialog.showModal();load();});
 find('[data-fba-item-search]').addEventListener('submit',event=>{event.preventDefault();page=0;load();});
 find('[data-fba-prev]').onclick=()=>{page--;load();};find('[data-fba-next]').onclick=()=>{page++;load();};
 dialog.querySelectorAll('[data-fba-close]').forEach(button=>button.onclick=()=>dialog.close());
 dialog.addEventListener('close',()=>{request?.abort();version++;opener?.focus();});
})();
