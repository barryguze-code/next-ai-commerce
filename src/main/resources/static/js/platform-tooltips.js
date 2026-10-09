/* One text-tooltip owner, including dynamically streamed table rows. */
(()=>{'use strict';
 if(window.NextAiTooltips)return;
 const tip=document.createElement('div');tip.id='platform-tooltip';tip.className='platform-tooltip dark-pill';tip.role='tooltip';tip.hidden=true;document.body.append(tip);
 let anchor;
 function migrate(node){
  if(node.nodeType!==1)return;
  for(const el of [node,...node.querySelectorAll('[title]')])if(el.hasAttribute('title')){
   const text=el.getAttribute('title');if(text){el.dataset.tooltip=text;if(el.matches('button,a')&&!el.textContent.trim()&&!el.hasAttribute('aria-label'))el.setAttribute('aria-label',text);}el.removeAttribute('title');
  }
 }
 function hide(){if(anchor){const ids=(anchor.getAttribute('aria-describedby')||'').split(/\s+/).filter(id=>id&&id!==tip.id);if(ids.length)anchor.setAttribute('aria-describedby',ids.join(' '));else anchor.removeAttribute('aria-describedby');}anchor=null;tip.hidden=true;}
 function show(event){
  const target=event.target.closest?.('[data-tooltip],[data-copy-sku],[data-order-copy],[data-reference-tip]');
  if(!target||target.closest('[data-profit-key]')?.querySelector('.profit-calculator-art')){hide();return;}
  if(target===anchor)return;hide();
  const text=target.dataset.copySku||target.dataset.orderCopy||target.dataset.tooltip||target.dataset.referenceTip;if(!text)return;
  document.dispatchEvent(new Event('platform-text-tooltip'));anchor=target;(target.closest('dialog[open]')||document.body).append(tip);tip.textContent=text;tip.hidden=false;
  target.setAttribute('aria-describedby',((target.getAttribute('aria-describedby')||'')+' '+tip.id).trim());
  const r=target.getBoundingClientRect();tip.style.left=Math.max(8,Math.min(r.left,innerWidth-tip.offsetWidth-8))+'px';tip.style.top=Math.max(8,Math.min(r.bottom+7,innerHeight-tip.offsetHeight-8))+'px';
 }
 window.NextAiTooltips={hide};
 // Run after the view's synchronous enhancements, preserving their title-based lookups.
 migrate(document.body);
 new MutationObserver(records=>{for(const r of records)if(r.type==='attributes')migrate(r.target);else for(const n of r.addedNodes)migrate(n);}).observe(document.body,{subtree:true,childList:true,attributes:true,attributeFilter:['title']});
 document.addEventListener('mouseover',show);document.addEventListener('focusin',show);
 document.addEventListener('mouseout',e=>{if(anchor&&!anchor.contains(e.relatedTarget))hide();});document.addEventListener('focusout',hide);
 document.addEventListener('click',hide);document.addEventListener('keydown',e=>{if(e.key==='Escape')hide();});window.addEventListener('scroll',hide,true);window.addEventListener('blur',hide);
})();
