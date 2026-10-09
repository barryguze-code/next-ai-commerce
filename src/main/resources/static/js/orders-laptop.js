/* Layout only: retain the original controls, handlers and scoped requests. */
(()=>{'use strict';
 const workspace=document.querySelector('.orders-workspace');if(!workspace)return;
 let pending=false;
 const sizes=new ResizeObserver(entries=>entries.forEach(({target})=>{
  const card=target.closest('.order-card');if(card)card.style.setProperty('--orders-filter-height',Math.ceil(target.getBoundingClientRect().height)+'px');
 }));
 function enhance(){
  workspace.querySelectorAll('.order-card').forEach(card=>{
   const toolbar=card.querySelector('.table-standard-toolbar'),tabs=card.querySelector('.order-tab-panel');
   if(toolbar&&tabs&&!card.querySelector('.orders-filter-anchor')){
    const anchor=document.createElement('div');anchor.className='orders-filter-anchor';toolbar.before(anchor);anchor.append(toolbar,tabs);sizes.observe(anchor);
   }
  });
  workspace.querySelectorAll('.order-identifier-line .order-copy-value,.order-identifier-line .order-sku-copy,.item-product-copy>strong').forEach(el=>{if(!el.title)el.title=el.textContent.trim();});
 }
 new MutationObserver(()=>{if(!pending){pending=true;queueMicrotask(()=>{pending=false;enhance();});}}).observe(workspace,{childList:true,subtree:true});
 enhance();
})();
