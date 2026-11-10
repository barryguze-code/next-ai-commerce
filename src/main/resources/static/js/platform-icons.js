(()=>{
 if(window.NextAiIcons)return;
 // Semantic names are the single source for shared action artwork.
 const assets={"mapping":"sku-mapped","unmapped":"sku-not-mapped","sku-mapping":"sku-mapped","inventory":"adjust-inventory","price":"change-sku-price","sku-set-price":"change-sku-price","sale":"send-sales-price","sku-sale-price":"send-sales-price","collaboration":"collaboration-default","collaboration-blue":"collaboration-default","collaboration-assigned-dots":"collaboration-assigned-red","collaboration-create-lightning":"collaboration-new","collaboration-online":"huddle","collaboration-original-redirect":"collaboration-open-original-record","print":"print-packing-slip","printClicked":"print-packing-slip","print-packing-slip-simple":"print-packing-slip","undoShipped":"undo-mark-as-shipped","mark-as-shipped-undo":"undo-mark-as-shipped","pending-final":"pending","unshipped-final":"unshipped","waiting-for-pickup-truck":"waiting-for-pickup","po-remove":"remove-from-basket","po-update":"update-basket-quantity","replenishment-add-cart":"add-to-basket","actions":"ai-action-required","alert":"ai-action-required","actions-ai-human":"ai-action-required","actions-ai-human-alert":"ai-action-required","actionable-items":"ai-action-required","table-actions":"ai-action-required","table-columns":"columns"};
 const optimized=new Set(["ibcore-logo","unshipped","shipping-truck","huddle","mark-as-shipped","sku-not-mapped","collaboration-assigned-red","print-packing-slip","collaboration-default","change-sku-price","delivery-exception","sku-mapped","remove-from-basket","columns","undo-mark-as-shipped","ai-action-required","collaboration-no-message-gray","delivered","collaboration-open-original-record","profit-calculator","waiting-for-pickup","amazon-account-manager-agent","human-ai","collaboration-new","inventory-transfer","edit-image","update-basket-quantity","adjust-inventory","collaboration-complete","send-sales-price","add-to-basket","pending","collaboration-unread-blue"]);
 const legacy={amazon:'amazon.com-logo',printClicked:'print-packing-clicked'};
 const version='20261109-icons32-1';
 function source(name){if(name==='print'||name==='printClicked'||name==='print-packing-slip'||name==='print-packing-slip-simple')return '/images/platform/optimized32/print-packing-slip-owner.png?v=20261109-printer';const file=assets[name]||legacy[name]||name;return '/images/platform/'+(optimized.has(file)?'optimized32/':'table/')+file+'.png?v='+version;}
 function create(name){if(name==='columns'||name==='table-columns'){const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.setAttribute('class','platform-icon table-columns-art');svg.setAttribute('viewBox','0 0 24 24');svg.setAttribute('fill','none');svg.setAttribute('stroke','currentColor');svg.setAttribute('stroke-width','1.7');svg.setAttribute('stroke-linecap','round');svg.setAttribute('stroke-linejoin','round');svg.setAttribute('aria-hidden','true');svg.innerHTML='<rect x="3" y="4" width="18" height="16" rx="2"></rect><path d="M9 4v16M15 4v16"></path>';return svg;}const img=document.createElement('img');img.dataset.platformIcon=name;img.src=source(name);img.alt='';img.decoding='async';img.className='platform-icon';return img;}
 function center(img){
  if(!img.getAttribute('src')?.includes('/images/platform/optimized32/'))return;
  const parent=img.parentElement;if(!parent)return;
  if(parent.matches('.table-action-icon,.order-picture-symbol,.order-status-art,.sku-identifier-icon,.order-identifier-market'))parent.classList.add('platform-art-slot');
  if(parent.matches('button,summary,a')){
   const text=[...parent.childNodes].filter(n=>n!==img&&!(n.nodeType===1&&n.matches('svg,b,.collaboration-message-count,.collaboration-direct-count,.collaboration-indirect-count'))).map(n=>n.textContent).join('').trim();
   parent.classList.toggle('platform-art-only',!text);
  }
 }
 function refresh(root=document){
  const selector='img[data-platform-icon],img[src*="/images/platform/optimized32/"]';
  const images=[...(root.matches?.(selector)?[root]:[]),...root.querySelectorAll(selector)];
  images.forEach(img=>{if(img.dataset.platformIcon){const src=source(img.dataset.platformIcon);if(img.getAttribute('src')!==src)img.setAttribute('src',src);}center(img);});
 }
 window.NextAiIcons=Object.freeze({source,create,refresh});
 // Inspect only newly inserted subtrees, never rescan the entire platform for a chat update.
 new MutationObserver(records=>{const added=new Set();records.forEach(record=>record.addedNodes.forEach(node=>{if(node.nodeType===1)added.add(node);}));for(const node of added){let nested=false;for(let parent=node.parentElement;parent;parent=parent.parentElement){if(added.has(parent)){nested=true;break;}}if(node.isConnected&&!nested)refresh(node);}}).observe(document.documentElement,{childList:true,subtree:true});
 refresh();
})();
