/* Layout only: retain the original controls, handlers and scoped requests. */
(()=>{'use strict';
 const workspace=document.querySelector('.orders-workspace');if(!workspace)return;
 let pending=false;const observedCards=new WeakSet();
 const sizes=new ResizeObserver(entries=>entries.forEach(({target})=>{
  const card=target.closest('.order-card');if(!card)return;
  if(target===card){const width=target.clientWidth;card.dataset.ordersDensity=width<=1150?'narrow':width<=1440?'compact':'wide';}
 }));
 function enhance(){
  workspace.querySelectorAll('.order-card').forEach(card=>{
   if(!observedCards.has(card)){observedCards.add(card);sizes.observe(card);const width=card.clientWidth;card.dataset.ordersDensity=width<=1150?'narrow':width<=1440?'compact':'wide';}
   card.querySelectorAll('[data-orders-clear-status]').forEach(link=>{const url=new URL(location.href);url.searchParams.set('status','ALL');url.searchParams.delete('page');url.searchParams.delete('goToPage');link.href=url.href;});
   const toolbar=card.querySelector('.table-standard-toolbar'),tabs=card.querySelector('.order-tab-panel');
   if(toolbar&&tabs&&!card.querySelector('.orders-filter-anchor')){
    const anchor=document.createElement('div');anchor.className='orders-filter-anchor';toolbar.before(anchor);anchor.append(toolbar,tabs);
   }
  });
  workspace.querySelectorAll('.order-identifier-line .order-copy-value,.order-identifier-line .order-sku-copy,.item-product-copy>strong').forEach(el=>{if(!el.title)el.title=el.textContent.trim();});
 }
 new MutationObserver(()=>{if(!pending){pending=true;queueMicrotask(()=>{pending=false;enhance();});}}).observe(workspace,{childList:true,subtree:true});
 enhance();
})();
