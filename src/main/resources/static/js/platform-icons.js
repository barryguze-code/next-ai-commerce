(()=>{
 if(window.NextAiIcons)return;
 // Semantic names are the single source for shared action artwork.
 const assets={mapping:'sku-mapped',unmapped:'sku-not-mapped',inventory:'adjust-inventory',price:'sku-set-price',sale:'sku-sale-price',amazon:'amazon.com-logo',collaboration:'collaboration-blue',actions:'actions-ai-human',alert:'actions-ai-human-alert',print:'print-packing-slip-simple',printClicked:'print-packing-clicked',undoShipped:'mark-as-shipped-undo'};
 const version='20260922-26';
 function source(name){return '/images/platform/table/'+(assets[name]||name)+'.png?v='+version;}
 function create(name){const img=document.createElement('img');img.dataset.platformIcon=name;img.src=source(name);img.alt='';img.decoding='async';img.className='platform-icon';return img;}
 function refresh(root=document){const images=[...(root.matches?.('img[data-platform-icon]')?[root]:[]),...root.querySelectorAll('img[data-platform-icon]')];images.forEach(img=>{const src=source(img.dataset.platformIcon);if(img.getAttribute('src')!==src)img.setAttribute('src',src);});}
 window.NextAiIcons=Object.freeze({source,create,refresh});
 // Inspect only newly inserted subtrees, never rescan the entire platform for a chat update.
 new MutationObserver(records=>{const added=new Set();records.forEach(record=>record.addedNodes.forEach(node=>{if(node.nodeType===1)added.add(node);}));for(const node of added){let nested=false;for(let parent=node.parentElement;parent;parent=parent.parentElement){if(added.has(parent)){nested=true;break;}}if(node.isConnected&&!nested)refresh(node);}}).observe(document.documentElement,{childList:true,subtree:true});
 refresh();
})();
