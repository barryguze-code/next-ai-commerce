(()=>{
 if(window.NextAiIcons)return;
 // Semantic names are the single source for shared action artwork.
 const assets={mapping:'sku-mapped',unmapped:'sku-not-mapped',inventory:'adjust-inventory',price:'sku-set-price',sale:'sku-sale-price',amazon:'amazon.com-logo',collaboration:'collaboration-blue',actions:'actions-ai-human',alert:'actions-ai-human-alert',print:'print-packing-slip-simple',printClicked:'print-packing-clicked',undoShipped:'mark-as-shipped-undo'};
 const version='20260922-26';
 function source(name){return '/images/platform/table/'+(assets[name]||name)+'.png?v='+version;}
 function create(name){const img=document.createElement('img');img.dataset.platformIcon=name;img.src=source(name);img.alt='';img.decoding='async';img.className='platform-icon';return img;}
 function refresh(){document.querySelectorAll('img[data-platform-icon]').forEach(img=>{const src=source(img.dataset.platformIcon);if(img.getAttribute('src')!==src)img.setAttribute('src',src);});}
 window.NextAiIcons=Object.freeze({source,create,refresh});
 new MutationObserver(records=>{if(records.some(record=>record.addedNodes.length))refresh();}).observe(document.documentElement,{childList:true,subtree:true});
 refresh();
})();
