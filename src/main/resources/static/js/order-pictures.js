(()=>{
 const icons={edit:'<path d="m5 16-1 4 4-1L20 7l-3-3Z M14 7l3 3"/>',spark:'<path d="m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5Z"/>',upload:'<path d="M12 16V3m-5 5 5-5 5 5M4 14v6h16v-6"/>',map:'<path d="M3 8h6v8H3zM16 3h5v5h-5zM16 16h5v5h-5zM9 12h4V5h3M13 12v6h3"/>'};
 const svg=name=>'<svg viewBox="0 0 24 24" aria-hidden="true">'+icons[name]+'</svg>';
 let menu,owner;
 function close(restore=true){menu?.remove();menu=null;owner?.setAttribute('aria-expanded','false');if(restore&&owner?.isConnected)owner.focus();owner=null;}
 function init(){document.querySelectorAll('[data-picture-actions]:not([data-picture-ready])').forEach(stage=>{
  stage.dataset.pictureReady='true';
  // Keep existing fallback markup, but expose one consistent actions launcher.
  stage.querySelectorAll('.order-stage-actions').forEach(el=>el.hidden=true);
  for(const [action,label,icon] of [['edit','Edit SKU picture','edit'],['more','More product actions','spark']]){
   if(action==='edit'&&stage.dataset.canEdit!=='true')continue;
   const button=document.createElement('button');button.type='button';button.className='order-picture-'+action;button.dataset.pictureMenu=action;button.setAttribute('aria-label',label);button.setAttribute('aria-haspopup','dialog');button.setAttribute('aria-expanded','false');button.title=label;button.innerHTML=svg(icon);stage.append(button);
  }
 });}
 function open(trigger){
  if(owner===trigger){close();return;}close(false);owner=trigger;trigger.setAttribute('aria-expanded','true');
  const stage=trigger.closest('[data-picture-actions]');stage.dataset.image=stage.querySelector(':scope > img')?.src||'';menu=document.createElement('section');menu.className='order-picture-menu';menu.setAttribute('role','dialog');menu.setAttribute('aria-label','SKU picture and product actions');
  const heading=document.createElement('header'),title=document.createElement('strong'),dismiss=document.createElement('button');title.textContent=stage.dataset.productTitle;dismiss.type='button';dismiss.textContent='×';dismiss.setAttribute('aria-label','Close product actions');dismiss.onclick=()=>close();heading.append(title,dismiss);menu.append(heading);
  const status=document.createElement('p');status.className='order-picture-status';status.setAttribute('role','status');
  function entry(label,help,icon,action){const b=document.createElement('button');b.type='button';b.className='order-picture-entry';const mark=document.createElement('span');mark.className='order-picture-symbol';mark.innerHTML=svg(icon);const copy=document.createElement('span'),strong=document.createElement('strong'),small=document.createElement('small');strong.textContent=label;small.textContent=help;copy.append(strong,small);b.append(mark,copy);b.onclick=action;menu.append(b);return b;}
  async function save(suffix,file){
   const panel=menu;panel.querySelectorAll('.order-picture-entry').forEach(b=>b.disabled=true);status.textContent=file?'Uploading picture…':'Getting the latest Amazon image…';
   const csrf=document.getElementById('buy-shipping-csrf'),headers={Accept:'application/json'};if(csrf)headers[csrf.dataset.header]=csrf.value;
   const body=new FormData();if(file)body.append('image',file);
   try{const r=await fetch('/app/orders/items/'+encodeURIComponent(stage.dataset.itemId)+'/picture'+suffix,{method:'POST',headers,body});if(r.redirected&&new URL(r.url).pathname==='/login')throw new Error('Your session expired. Refresh and sign in again.');const result=await r.json();if(!r.ok)throw new Error(result.error||'The picture could not be saved.');
    document.querySelectorAll('[data-picture-actions]').forEach(tile=>{if(tile.dataset.sellerSku!==stage.dataset.sellerSku)return;let img=tile.querySelector(':scope > img');if(!img){img=document.createElement('img');img.alt='';tile.prepend(img);tile.querySelector(':scope > span')?.remove();}img.src=result.imageUrl+(result.imageUrl.includes('?')?'&':'?')+'v='+Date.now();});status.textContent='SKU picture updated. Catalogue products were not changed.';
   }catch(error){status.textContent=error.message;}finally{panel.querySelectorAll('.order-picture-entry').forEach(b=>b.disabled=false);}
  }
  if(stage.dataset.canEdit==='true'){
   entry('Upload from device','JPG, PNG up to 5 MB','upload',()=>{const input=document.createElement('input');input.type='file';input.accept='image/png,image/jpeg';input.onchange=()=>{const file=input.files[0];if(!file)return;if(file.size>5_000_000||!['image/png','image/jpeg'].includes(file.type)){status.textContent='Choose a JPG or PNG up to 5 MB.';return;}save('',file);};input.click();});
   const amazon=entry('Sync from Amazon','Use the latest image for this SKU’s ASIN','spark',()=>save('/amazon'));amazon.querySelector('.order-picture-symbol').textContent='a';amazon.querySelector('.order-picture-symbol').classList.add('order-picture-amazon');
  }
  const actions=[];
  const more=entry('More actions','Inventory, mapping, product details','spark',()=>{const expanded=more.getAttribute('aria-expanded')!=='true';more.setAttribute('aria-expanded',String(expanded));actions.forEach(action=>action.hidden=!expanded);place();if(expanded)actions[0]?.focus();});more.setAttribute('aria-expanded','false');
  if(stage.dataset.canEdit==='true'){
   actions.push(entry('SKU mapping',stage.dataset.mapping==='MAPPED'?'Review catalogue products and quantities':'Choose catalogue products','map',()=>{close(false);window.openOrderMapping(stage);}));
   if(stage.dataset.mapping==='MAPPED')actions.push(entry('Adjust inventory','Review mapped stock for this order','map',()=>{close(false);window.openOrderInventoryAdjustment(stage);}));
  }
  actions.push(entry('Product details','Open the Amazon product in a new tab','map',()=>{if(stage.dataset.productUrl)window.open(stage.dataset.productUrl,'_blank','noopener,noreferrer');else status.textContent='This item has no Amazon product link.';}));actions.forEach(action=>action.hidden=true);
  function place(){const r=trigger.getBoundingClientRect();menu.style.left=Math.max(12,Math.min(r.left,innerWidth-menu.offsetWidth-12))+'px';menu.style.top=Math.max(12,Math.min(r.bottom+10,innerHeight-menu.offsetHeight-12))+'px';}
  menu.append(status);document.body.append(menu);place();menu.querySelector('button').focus();
 }
 document.addEventListener('click',e=>{const trigger=e.target.closest('[data-picture-menu]');if(trigger){open(trigger);return;}if(menu&&!menu.contains(e.target))close(false);});
 document.addEventListener('keydown',e=>{if(!menu)return;if(e.key==='Escape'){e.preventDefault();close();}if(e.key==='Tab'){const buttons=[...menu.querySelectorAll('button:not(:disabled):not([hidden])')],first=buttons[0],last=buttons.at(-1);if(e.shiftKey&&document.activeElement===first){e.preventDefault();last.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first.focus();}}});
 window.addEventListener('resize',()=>close(false));window.addEventListener('scroll',e=>{if(menu&&!menu.contains(e.target))close(false);},true);
 new MutationObserver(()=>{if(owner&&!owner.isConnected)close(false);init();}).observe(document.querySelector('[data-order-stream]')?.parentElement||document.body,{childList:true,subtree:true});init();
})();
