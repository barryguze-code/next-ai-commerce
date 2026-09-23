/* Presentation adapters only: existing domain handlers retain ownership of actions. */
(()=>{
 'use strict';
 if(!document.body?.classList.contains('platform-standard-tables')&&!['/app/inventory','/app/inventory/ledger','/app/catalog','/app/vendors','/app/receiving'].includes(location.pathname))return;
 const node=(tag,cls,text)=>{const el=document.createElement(tag);el.className=cls||'';if(text)el.textContent=text;return el};
 const artwork=label=>/price|cost/i.test(label)?'price':/sale/i.test(label)?'sale':/adjust|stock|hold|remov/i.test(label)?'inventory':/sku|mapping/i.test(label)?'mapping':/close/i.test(label)?'shipped':/receiv|document|invoice/i.test(label)?'warehouse':'edit';
 function imageMenu(trigger,holder,url,product){
  const menu=node('dialog','order-picture-menu standard-picture-menu');menu.setAttribute('aria-label','Edit picture');
  const head=node('header');head.append(node('strong','',product?'Edit product picture':'Edit picture'));const exit=node('button','','×');exit.type='button';exit.setAttribute('aria-label','Close');head.append(exit);menu.append(head);
  const close=()=>{menu.close();menu.remove();trigger.focus()};exit.onclick=close;menu.oncancel=e=>{e.preventDefault();close()};
  const status=node('p','order-picture-status');status.setAttribute('role','status');
  const entry=(icon,title,copy,fn)=>{const b=node('button','order-picture-entry');b.type='button';b.innerHTML=icon;const label=node('span');label.append(node('strong','',title),node('small','',copy));b.append(label);b.onclick=fn;menu.append(b)};
  const save=async(file,sync)=>{
   menu.querySelectorAll('.order-picture-entry').forEach(b=>b.disabled=true);status.textContent=sync?'Syncing…':'Uploading…';
   const data=new FormData();if(file)data.append('image',file);
   const token=document.querySelector('input[name=_csrf]'),headers={Accept:'application/json'};
   if(token)data.append('_csrf',token.value);
   try{const response=await fetch(url+(sync?'/amazon':product?'/upload':''),{method:'POST',body:data,headers});const result=await response.json();if(!response.ok)throw Error(result.error||'Picture could not be saved.');
    document.querySelectorAll('[data-standard-picture-url]').forEach(tile=>{if(tile.dataset.standardPictureUrl!==url)return;let img=tile.querySelector('img');if(!img){img=node('img');img.alt='';tile.prepend(img)}img.hidden=false;img.src=result.imageUrl+(result.imageUrl.includes('?')?'&':'?')+'v='+Date.now();tile.querySelectorAll('b').forEach(b=>b.hidden=true)});
    close();
   }catch(e){status.textContent=e.message;menu.querySelectorAll('.order-picture-entry').forEach(b=>b.disabled=false)}
  };
  entry('<span class="order-picture-symbol"><svg viewBox="0 0 24 24"><path d="M12 16V3m-5 5 5-5 5 5M4 15v6h16v-6"/></svg></span>','Upload from device','JPG, PNG up to 5 MB',()=>{
   const input=node('input');input.type='file';input.accept='image/png,image/jpeg';input.onchange=()=>{const f=input.files[0];if(!f)return;if(f.size>5000000||!['image/png','image/jpeg'].includes(f.type)){status.textContent='Choose a JPG or PNG up to 5 MB.';return}save(f,false)};input.click();
  });
  if(product)entry('<span class="order-picture-symbol order-picture-amazon">a</span>','Sync from Amazon','Use an Amazon SKU mapped to this product',()=>save(null,true));
  menu.append(status);document.body.append(menu);menu.showModal();const r=trigger.getBoundingClientRect(),m=menu.getBoundingClientRect();menu.style.left=Math.max(12,Math.min(r.left,innerWidth-m.width-12))+'px';menu.style.top=Math.max(12,Math.min(r.bottom+10,innerHeight-m.height-12))+'px';
 }
 function enhance(){
  if(!document.body.classList.contains('platform-standard-tables'))return;
  document.querySelectorAll('.table-widget tbody tr').forEach(row=>{
   const holder=row.querySelector('.table-overview-picture');if(!holder||holder.dataset.standardReady)return;
   holder.dataset.standardReady='true';
   const form=holder.matches('form')?holder:null;
   const product=row.dataset.pictureItem||form?.action.match(/products\/([^/]+)\/image/)?.[1];
   const url=product?'/app/catalog/products/'+encodeURIComponent(product)+'/image':row.dataset.pictureId?'/app/catalog/record-pictures/'+row.dataset.pictureType+'/'+row.dataset.pictureId:null;
   if(url){
    holder.dataset.standardPictureUrl=url;
    if(!holder.querySelector('img')){const img=node('img');img.alt='';img.loading='lazy';img.decoding='async';img.src=row.dataset.image||url;img.onerror=()=>{img.hidden=true};img.onload=()=>holder.querySelectorAll('b').forEach(b=>b.hidden=true);holder.prepend(img)}
    if(form||row.dataset.canEditPicture==='true'){
     if(form){form.querySelectorAll('i').forEach(el=>el.hidden=true)}
     const button=node('button','standard-picture-edit');button.type='button';button.setAttribute('aria-label','Edit picture');button.title='Edit picture';button.innerHTML='<svg viewBox="0 0 24 24"><path d="m5 16-1 4 4-1L20 7l-3-3Z M14 7l3 3"/></svg>';holder.append(button);holder.oncontextmenu=e=>{e.preventDefault();e.stopPropagation();imageMenu(button,holder,url,Boolean(product))};button.onclick=e=>{e.stopPropagation();imageMenu(button,holder,url,Boolean(product))};
    }
   }
   row.querySelectorAll('td').forEach(cell=>{
    if(/cost|quantity|available|reserved|on-hand|freight|discount|expected|received|products/.test(cell.dataset.column||'')){cell.classList.add('standard-number');cell.querySelectorAll('strong').forEach(el=>el.classList.add('standard-number'))}
    if(cell.dataset.column==='record-context')return;
    const walker=document.createTreeWalker(cell,NodeFilter.SHOW_TEXT);let text;while((text=walker.nextNode())){if(text.parentElement.closest('input,select,button,script,style'))continue;text.nodeValue=text.nodeValue.replace(/\bUSD\s+(?=[\d-])/g,'$').replace(/^USD$/,'$')}
   });
  });
  document.querySelectorAll('.table-action-icon:not([data-art-ready])').forEach(icon=>{icon.dataset.artReady='true';icon.replaceChildren(window.NextAiIcons.create(artwork(icon.parentElement.textContent)));});
  document.querySelectorAll('#inventory-action-menu button>span,#inventory-action-menu a>span').forEach(icon=>{if(icon.dataset.artReady)return;icon.dataset.artReady='true';icon.replaceChildren(window.NextAiIcons.create(artwork(icon.parentElement.textContent)))});
 }
 window.NextAiStandardTables={enhance,editPicture:(trigger,holder,url)=>imageMenu(trigger,holder,url,true)};
 document.addEventListener('DOMContentLoaded',()=>requestAnimationFrame(enhance));
})();
