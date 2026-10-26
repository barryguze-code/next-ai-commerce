/* Presentation adapters only: existing domain handlers retain ownership of actions. */
(()=>{
 'use strict';
 if(!document.body?.classList.contains('platform-standard-tables')&&!['/app/inventory','/app/inventory/ledger','/app/catalog','/app/vendors','/app/receiving'].includes(location.pathname))return;
 const node=(tag,cls,text)=>{const el=document.createElement(tag);el.className=cls||'';if(text)el.textContent=text;return el};
 const artwork=label=>/price|cost/i.test(label)?'price':/sale/i.test(label)?'sale':/adjust|stock|hold|remov/i.test(label)?'inventory':/sku|mapping/i.test(label)?'mapping':/close/i.test(label)?'shipped':/receiv|document|invoice/i.test(label)?'warehouse':'edit';
 function imageMenu(trigger,holder,url,product){
  const shared=url.startsWith("/app/platform/catalog/products/"),catalogue=product&&Boolean(holder.closest("tr[data-picture-item],tr[data-picture-global]"));
  const menu=node('dialog','order-picture-menu standard-picture-menu');menu.setAttribute('aria-label','Edit picture');
  const head=node('header');head.append(node('strong','',shared?'Shared Global Catalogue picture':product?'Edit product picture':'Edit picture'));const exit=node('button','','×');exit.type='button';exit.setAttribute('aria-label','Close');head.append(exit);menu.append(head);
  const close=()=>{window.removeEventListener('resize',place);menu.close();menu.remove();trigger.focus()};exit.onclick=close;menu.oncancel=e=>{e.preventDefault();close()};
  const status=node('p','order-picture-status');status.setAttribute('role','status');
  const entry=(icon,title,copy,fn)=>{const b=node('button','order-picture-entry');b.type='button';b.innerHTML=icon;const label=node('span');label.append(node('strong','',title),node('small','',copy));b.append(label);b.onclick=fn;menu.append(b);return b};
  const save=async(file,sync,candidate)=>{
   if(shared&&!window.confirm('Change the shared Global Catalogue picture for every linked account? Private account overrides will be kept.'))return;
   const buttons=[...menu.querySelectorAll('button')].filter(b=>b!==exit),disabled=buttons.map(b=>b.disabled);buttons.forEach(b=>b.disabled=true);status.textContent=sync?'Syncing…':candidate?'Saving chosen picture…':'Uploading…';
   const data=new FormData();if(file)data.append('image',file);if(candidate)data.append('candidate',candidate);if(shared)data.append('confirmShared','true');
   const token=document.querySelector('input[name=_csrf]'),headers={Accept:'application/json'};
   if(token)data.append('_csrf',token.value);
   try{const response=await fetch(url+(candidate?'/choose':sync?'/amazon':product?'/upload':''),{method:'POST',body:data,headers});const result=await response.json();if(!response.ok)throw Error(result.error||'Picture could not be saved.');
    document.querySelectorAll('[data-standard-picture-url]').forEach(tile=>{if(tile.dataset.standardPictureUrl!==url)return;let img=tile.querySelector('img');if(!img){img=node('img');img.alt='';tile.prepend(img)}img.hidden=false;img.src=result.imageUrl+(result.imageUrl.includes('?')?'&':'?')+'v='+Date.now();tile.querySelectorAll('b').forEach(b=>b.hidden=true)});
    if(url.includes('/record-pictures/USER/'))window.dispatchEvent(new Event('user-profile-picture-updated'));
    close();
   }catch(e){status.textContent=e.message;buttons.forEach((b,i)=>b.disabled=disabled[i])}
  };
  entry('<span class="order-picture-symbol"><svg viewBox="0 0 24 24"><path d="M12 16V3m-5 5 5-5 5 5M4 15v6h16v-6"/></svg></span>','Upload from device','JPG, PNG up to 5 MB',()=>{
   const input=node('input');input.type='file';input.accept='image/png,image/jpeg';input.onchange=()=>{const f=input.files[0];if(!f)return;if(f.size>5000000||!['image/png','image/jpeg'].includes(f.type)){status.textContent='Choose a JPG or PNG up to 5 MB.';return}save(f,false)};input.click();
  });
  if(product&&!catalogue)entry('<span class="order-picture-symbol order-picture-amazon">a</span>','Sync from Amazon','Use an Amazon SKU mapped to this product',()=>save(null,true));
  if(catalogue){
   const help=node('p','order-picture-status',shared?'Shared across linked accounts. Private account pictures are preserved.':'This choice updates only the selected account’s picture.');menu.append(help);
   const choices=node('div','catalog-picture-choices');choices.setAttribute('role','group');choices.setAttribute('aria-label','Matched SKU pictures');menu.append(choices);
   let selected=null;
   const use=entry('<span class="order-picture-symbol">✓</span>','Use picture','Choose a saved matched-SKU image',()=>save(null,false,selected));use.disabled=true;
   const sync=entry('<span class="order-picture-symbol order-picture-amazon">a</span>','Sync from Amazon','Checking availability…',()=>save(null,true));sync.disabled=true;
   fetch(url+'/candidates',{headers:{Accept:'application/json'}}).then(async r=>{if(!r.ok)throw Error('Matched pictures could not be loaded.');return r.json()}).then(result=>{
    for(const candidate of result.candidates){const option=node('button','catalog-picture-choice');option.type='button';option.setAttribute('aria-pressed','false');const image=node('img');image.src=candidate.imageUrl;image.alt='';image.loading='lazy';const copy=node('span');copy.append(node('strong','',candidate.sku),node('small','',candidate.asin+(candidate.mixedBundle?' · Mixed-product bundle — review before choosing':'')));option.append(image,copy);option.onclick=()=>{selected=candidate.id;choices.querySelectorAll('button').forEach(b=>b.setAttribute('aria-pressed',String(b===option)));use.disabled=false;};choices.append(option);}
    if(!result.candidates.length)choices.append(node('p','order-picture-status','No saved public Amazon pictures are linked to this product. Upload a picture or check its SKU mapping.'));
    sync.disabled=!result.canSync;sync.querySelector('small').textContent=result.canSync?'Fetch the latest main image for a mapped SKU':result.syncUnavailable;place();
   }).catch(e=>status.textContent=e.message);
  }
  function place(){if(!menu.isConnected)return;const r=trigger.getBoundingClientRect(),m=menu.getBoundingClientRect();menu.style.left=Math.max(12,Math.min(r.left,innerWidth-m.width-12))+'px';menu.style.top=Math.max(12,Math.min(r.bottom+10,innerHeight-m.height-12))+'px';}
  menu.append(status);document.body.append(menu);menu.showModal();place();window.addEventListener('resize',place);
 }
 function enhance(){
  if(!document.body.classList.contains('platform-standard-tables'))return;
  document.querySelectorAll('.table-widget tbody tr').forEach(row=>{
   const holder=row.querySelector('.table-overview-picture');if(!holder||holder.dataset.standardReady)return;
   holder.dataset.standardReady='true';
   const form=holder.matches('form')?holder:null;
   const global=row.dataset.pictureGlobal;
   const product=row.dataset.pictureItem||form?.action.match(/products\/([^/]+)\/image/)?.[1];
   const url=global?'/app/platform/catalog/products/'+encodeURIComponent(global)+'/image':product?'/app/catalog/products/'+encodeURIComponent(product)+'/image':row.dataset.pictureId?'/app/catalog/record-pictures/'+row.dataset.pictureType+'/'+row.dataset.pictureId:null;
   if(product||global)holder.classList.add('product-thumbnail-slot');
   if(url){
    holder.dataset.standardPictureUrl=url;
    if(!holder.querySelector('img')){const img=node('img');img.alt='';img.loading='lazy';img.decoding='async';img.src=row.dataset.image||url;img.onerror=()=>{img.hidden=true};img.onload=()=>holder.querySelectorAll('b').forEach(b=>b.hidden=true);holder.prepend(img)}
    if(form||row.dataset.canEditPicture==='true'){
     if(form){form.addEventListener('click',e=>{if(e.target.closest('label'))e.preventDefault()});form.querySelectorAll('input[type=file]').forEach(el=>el.disabled=true);}
     if(form){form.querySelectorAll('i').forEach(el=>el.hidden=true)}
     const button=node('button','standard-picture-edit');button.type='button';button.setAttribute('aria-label','Edit picture');button.title='Edit picture';button.innerHTML='<svg viewBox="0 0 24 24"><path d="m5 16-1 4 4-1L20 7l-3-3Z M14 7l3 3"/></svg>';holder.append(button);holder.oncontextmenu=e=>{e.preventDefault();e.stopPropagation();imageMenu(button,holder,url,Boolean(product||global))};button.onclick=e=>{e.stopPropagation();imageMenu(button,holder,url,Boolean(product||global))};
    }
   }
   row.querySelectorAll('td').forEach(cell=>{
    if(/cost|quantity|available|reserved|on-hand|freight|discount|expected|received|products/.test(cell.dataset.column||'')){cell.classList.add('standard-number');cell.querySelectorAll('strong').forEach(el=>el.classList.add('standard-number'))}
    if(cell.dataset.column==='record-context')return;
    const walker=document.createTreeWalker(cell,NodeFilter.SHOW_TEXT);let text;while((text=walker.nextNode())){if(text.parentElement.closest('input,select,button,script,style'))continue;const value=text.nodeValue.replace(/\bUSD\s+(?=[\d-])/g,'$').replace(/^USD$/,'$');if(value!==text.nodeValue)text.nodeValue=value}
   });
  });
  document.querySelectorAll('.table-action-icon:not([data-art-ready])').forEach(icon=>{icon.dataset.artReady='true';icon.replaceChildren(window.NextAiIcons.create(artwork(icon.parentElement.textContent)));});
  document.querySelectorAll('#inventory-action-menu button:not([data-suggest-action])>span,#inventory-action-menu a>span').forEach(icon=>{if(icon.dataset.artReady)return;icon.dataset.artReady='true';icon.replaceChildren(window.NextAiIcons.create(artwork(icon.parentElement.textContent)))});
 }
 window.NextAiStandardTables={enhance,editPicture:(trigger,holder,url)=>imageMenu(trigger,holder,url,true)};
 document.addEventListener('DOMContentLoaded',()=>requestAnimationFrame(enhance));
})();
