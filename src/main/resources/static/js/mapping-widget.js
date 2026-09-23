/* Shared mapping presentation for Orders and Marketplace SKUs. */
(()=>{'use strict';
const endpoint=id=>'/app/catalog/products/'+encodeURIComponent(id)+'/image';
function thumbnail(id,source){const img=document.createElement('img');img.alt='';img.loading='lazy';img.decoding='async';img.src=source||endpoint(id);img.onerror=()=>{img.hidden=true};return img}
function picture(line,product){
 const id=product?.id||product?.itemId||line.querySelector('[name=productId]')?.value;
 let holder=line.querySelector('.mapping-component-picture');if(!holder){holder=document.createElement('span');holder.className='mapping-component-picture';line.prepend(holder)}
 holder.style.backgroundImage='none';holder.replaceChildren();holder.classList.remove('has-image');
 if(!id){holder.textContent='＋';return}
 holder.append(thumbnail(id,product?.imageUrl));
 if(line.closest('dialog').dataset.canEdit!=='true')return;
 attachEditor(holder,id);
}
function attachEditor(holder,productId,sku){
 if(!sku&&dialog?.dataset.canEdit!=='true')return;
 if(holder.querySelector('.mw-picture-edit'))return;
 const edit=node('button','mw-picture-edit');edit.type='button';edit.title='Edit SKU picture';edit.setAttribute('aria-label',edit.title);
 edit.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 16-1 4 4-1L20 7l-3-3Z M14 7l3 3"/></svg>';
 holder.append(edit);edit.onclick=()=>pictureMenu(edit,holder,productId,sku);
}
function pictureMenu(trigger,holder,productId,sku){
 const menu=node('dialog','order-picture-menu mw-picture-menu');
 menu.innerHTML='<header><strong>Edit SKU picture</strong><button type="button" aria-label="Close">×</button></header>';
 const feedback=node('p','mw-picture-status');feedback.setAttribute('role','status');
 const close=()=>{menu.close();menu.remove();trigger.focus()};
 menu.querySelector('button').onclick=close;menu.addEventListener('cancel',e=>{e.preventDefault();close()});
 menu.addEventListener('click',e=>{if(e.target===menu){const r=menu.getBoundingClientRect();if(e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom)close()}});
 const entry=(icon,title,description,action)=>{const b=node('button','order-picture-entry');b.type='button';b.innerHTML=icon;const copy=node('span');copy.append(node('strong','',title),node('small','',description));b.append(copy);b.onclick=action;menu.append(b)};
 const save=async(file,amazon)=>{
  menu.querySelectorAll('.order-picture-entry').forEach(b=>b.disabled=true);feedback.textContent=amazon?'Syncing…':'Uploading…';
  const data=new FormData();data.append('sku',sku||dialog.querySelector('[name=sellerSku]').value);if(productId)data.append('productId',productId);if(file)data.append('image',file);data.append('amazon',String(amazon));
  const csrf=document.getElementById('buy-shipping-csrf'),headers={Accept:'application/json'};if(csrf)headers[csrf.dataset.header]=csrf.value;
  try{const r=await fetch('/app/marketplace-skus/mapping-picture',{method:'POST',body:data,headers});const result=await r.json();if(!r.ok)throw Error(result.error||'Picture update failed.');
   let img=holder.querySelector('img');if(!img){img=node('img');img.alt='';holder.prepend(img)}holder.querySelectorAll('b').forEach(b=>b.hidden=true);img.hidden=false;img.src=result.imageUrl+(result.imageUrl.includes('?')?'&':'?')+'v='+Date.now();
   if(!productId){const sku=data.get('sku');document.querySelectorAll('[data-seller-sku]').forEach(el=>{if(el.dataset.sellerSku===sku){el.dataset.image=img.src;const tile=el.matches('[data-picture-actions]')?el.querySelector('img'):null;if(tile)tile.src=img.src}})}
   close();
  }catch(e){feedback.textContent=e.message;menu.querySelectorAll('.order-picture-entry').forEach(b=>b.disabled=false)}
 };
 entry('<span class="order-picture-symbol"><svg viewBox="0 0 24 24"><path d="M12 16V3m-5 5 5-5 5 5M4 15v6h16v-6"/></svg></span>','Upload from device','JPG, PNG up to 5 MB',()=>{const input=node('input');input.type='file';input.accept='image/jpeg,image/png';input.onchange=()=>{const file=input.files[0];if(!file)return;if(file.size>5000000||!['image/jpeg','image/png'].includes(file.type)){feedback.textContent='Choose a JPG or PNG up to 5 MB.';return}save(file,false)};input.click()});
 entry('<span class="order-picture-symbol order-picture-amazon">a</span>','Sync from Amazon','Use the latest image for this SKU’s ASIN',()=>save(null,true));
 menu.append(feedback);document.body.append(menu);menu.showModal();
 const r=trigger.getBoundingClientRect(),m=menu.getBoundingClientRect();menu.style.left=Math.max(12,Math.min(r.left,innerWidth-m.width-12))+'px';menu.style.top=Math.max(12,Math.min(r.bottom+10,innerHeight-m.height-12))+'px';
}
function option(button,product){button.prepend(thumbnail(product.id,product.imageUrl));button.classList.add('mapping-picture-option')}

let dialog,opener,requestNumber=0;
const node=(tag,cls,text)=>{const e=document.createElement(tag);if(cls)e.className=cls;if(text)e.textContent=text;return e};
function shell(){
 if(dialog)return dialog;
 dialog=node('dialog','mapping-widget');dialog.setAttribute('aria-labelledby','mapping-widget-title');
 dialog.innerHTML='<form method="post" action="/app/marketplace-skus/mappings"><header><div><span class="mw-eyebrow">Catalogue mapping</span><h2 id="mapping-widget-title"></h2><p data-title></p></div><button type="button" class="mw-close" aria-label="Close mapping">×</button></header><section class="mw-body"><div class="mw-intro"><span class="mw-product-image"></span><div><strong>What does one marketplace unit contain?</strong><small>Choose one product, or add products for a bundle.</small></div></div><p class="mw-feedback" role="status"></p><div class="mw-lines"></div><button type="button" class="mw-add"><span>＋</span> Add another product</button><div class="mw-help"><strong>Single product or bundle</strong><small>The quantity is how many eaches are included in one sold marketplace SKU.</small></div></section><footer><button type="submit" class="mw-clear" formaction="/app/marketplace-skus/mappings/clear" formnovalidate>Clear mapping</button><button type="button" class="secondary-button mw-cancel">Cancel</button><button type="submit" class="primary-button mw-save">Save mapping</button></footer></form>';
 document.body.append(dialog);
 const close=()=>{dialog.close();opener?.focus()};
 dialog.querySelector('.mw-close').onclick=close;dialog.querySelector('.mw-cancel').onclick=close;
 dialog.addEventListener('click',e=>{if(e.target===dialog)close()});
 dialog.querySelector('.mw-add').onclick=()=>{if(valid()){line().querySelector('input[type=search]').focus();update()}};
 dialog.querySelector('form').onsubmit=e=>{if(e.submitter?.classList.contains('mw-clear'))return;if(!valid()){e.preventDefault();dialog.querySelector('input[type=search]')?.focus()}};
 dialog.addEventListener('input',update);
 return dialog;
}
function valid(){return [...dialog.querySelectorAll('.mw-line')].length>0&&[...dialog.querySelectorAll('.mw-line')].every(l=>l.querySelector('[name=productId]').value&&l.querySelector('[name=quantity]').checkValidity())}
function update(){const disabled=dialog.dataset.canEdit!=='true'||!valid();dialog.querySelector('.mw-add').disabled=disabled;dialog.querySelector('.mw-save').disabled=disabled}
function line(product){
 const row=node('div','mw-line');row.innerHTML='<label><span>Account catalogue product</span><input type="search" placeholder="Search product, UPC, or vendor item code" autocomplete="off" required><input type="hidden" name="productId"><div class="mw-results"></div></label><label><span>Quantity</span><input type="number" name="quantity" min="1" max="10000" step="1" value="1" required></label><button type="button" class="mw-remove" aria-label="Remove product">−</button>';
 dialog.querySelector('.mw-lines').append(row);
 const input=row.querySelector('[type=search]'),id=row.querySelector('[name=productId]'),results=row.querySelector('.mw-results');
 if(product){input.value=[product.vendorItemCode,product.productName||product.name,product.accountSku].filter(Boolean).join(' · ');id.value=product.itemId||product.id;row.querySelector('[name=quantity]').value=product.quantity||1}
 picture(row,product);
 let timer,controller;
 input.oninput=()=>{id.value='';picture(row);update();clearTimeout(timer);controller?.abort();results.replaceChildren();const q=input.value.trim();if(q.length<2)return;timer=setTimeout(async()=>{controller=new AbortController();try{const response=await fetch('/app/catalog/product-search?q='+encodeURIComponent(q),{signal:controller.signal});if(!response.ok)throw Error();const products=await response.json();if(!input.isConnected||input.value.trim()!==q)return;results.replaceChildren();products.forEach(p=>{const b=node('button','mw-option');b.type='button';const copy=node('span');copy.append(node('strong','',p.name),node('small','',[p.brand,p.vendorItemCode,p.identifier].filter(Boolean).join(' · ')));b.append(thumbnail(p.id,p.imageUrl),copy);b.onclick=()=>{input.value=[p.vendorItemCode,p.name,p.accountSku].filter(Boolean).join(' · ');id.value=p.id;picture(row,p);results.replaceChildren();update()};results.append(b)});if(!products.length){const link=node('a','','No match — add a catalogue product ↗');link.href='/app/catalog?addProduct=true';link.target='_blank';link.rel='noopener';results.append(link)}}catch(e){if(e.name!=='AbortError')results.textContent='Search unavailable. Please try again.'}},200)};
 row.querySelector('.mw-remove').onclick=()=>{controller?.abort();if(dialog.querySelectorAll('.mw-line').length===1){input.value='';id.value='';picture(row);results.replaceChildren()}else row.remove();update()};
 if(dialog.dataset.canEdit!=='true')row.querySelectorAll('input,button').forEach(e=>e.disabled=true);
 return row;
}
async function open(trigger){
 opener=trigger;const d=shell(),version=++requestNumber;
 const record=trigger.dataset.target?document.getElementById(trigger.dataset.target):trigger;
 const stage=trigger.closest('.order-item')?.querySelector('[data-picture-actions]');
 const data=record?.dataset||{};
 d.dataset.canEdit=String((data.canEdit||stage?.dataset.canEdit||'true')==='true');
 d.querySelector('h2').textContent=data.sellerSku||stage?.dataset.sellerSku||'';
 d.querySelector('[data-title]').textContent=data.productTitle||stage?.dataset.productTitle||'';
 const photo=d.querySelector('.mw-product-image');photo.replaceChildren();const image=data.image||stage?.querySelector('img')?.src;
 if(image){const img=node('img');img.src=image;img.alt='';photo.append(img)}else photo.textContent='▦';
 attachEditor(photo);
 d.querySelectorAll('form > input[type=hidden]').forEach(e=>e.remove());
 const hidden=(name,value)=>{const e=node('input');e.type='hidden';e.name=name;e.value=value;d.querySelector('form').append(e)};
 hidden('sellerSku',d.querySelector('h2').textContent);hidden('returnTo',location.pathname+location.search);
 const csrf=document.getElementById('buy-shipping-csrf');if(csrf)hidden('_csrf',csrf.value);
 d.querySelector('.mw-lines').replaceChildren();d.querySelector('.mw-feedback').textContent='Loading mapping…';
 d.querySelectorAll('.mw-save,.mw-add,.mw-clear').forEach(b=>b.disabled=true);
 d.querySelector('.mw-clear').hidden=d.dataset.canEdit!=='true';
 d.showModal();
 try{const r=await fetch('/app/marketplace-skus/mappings/components?sku='+encodeURIComponent(d.querySelector('h2').textContent));if(!r.ok)throw Error();const products=await r.json();if(version!==requestNumber||!d.open)return;(products.length?products:[null]).forEach(line);d.querySelector('.mw-feedback').textContent='';d.querySelector('.mw-clear').disabled=!products.length;update()}catch{if(version===requestNumber)d.querySelector('.mw-feedback').textContent='Could not load mapping. Close and try again; nothing has changed.'}
}
window.NextAiMappingWidget={picture,option,open,attachEditor};
window.openOrderMapping=open;
})();
