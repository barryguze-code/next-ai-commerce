/* On-demand, read-only evidence using the existing tenant/store-scoped Orders
 * view. No polling, Amazon requests, new aggregates, or HTML injection. */
(()=>{'use strict';
 if(window.NextAiMetricDrilldown)return;
 const dialog=document.createElement('dialog');dialog.className='metric-drilldown';
 dialog.innerHTML='<section><header><div><h2>Order breakdown</h2><p data-metric-context></p></div></header><div data-metric-body role="status"></div><footer><button type="button" class="secondary-button" data-metric-prev>Previous</button><span data-metric-page></span><button type="button" class="secondary-button" data-metric-next>Next</button></footer></section>';
 document.body.append(dialog);window.NextAiDialogs?.enhance(dialog);
 const body=dialog.querySelector('[data-metric-body]'),prev=dialog.querySelector('[data-metric-prev]'),next=dialog.querySelector('[data-metric-next]');
 let request,sequence=0,page=0,scope,trigger;
 const node=(tag,text)=>{const n=document.createElement(tag);n.textContent=text;return n;};
 async function load(){
  request?.abort();request=new AbortController();const current=++sequence;
  prev.disabled=next.disabled=true;body.replaceChildren(node('p','Loading order breakdown…'));
  const url=new URL(scope);url.searchParams.set('page',String(page));url.searchParams.set('size','25');url.searchParams.delete('goToPage');
  try{
   const response=await fetch(url,{signal:request.signal,credentials:'same-origin',headers:{'X-Order-Stream':'metric-drilldown'}});
   if(!response.ok||new URL(response.url||url).pathname!==url.pathname)throw Error();
   const doc=new DOMParser().parseFromString(await response.text(),'text/html'),stream=doc.querySelector('[data-order-stream]');
   if(!stream)throw Error();if(sequence!==current||!dialog.open)return;body.replaceChildren();
   for(const row of stream.querySelectorAll('.order-row')){
    const card=node('article','');card.className='metric-record';
    const reference=row.querySelector('.order-number');card.append(node('h3',reference?.textContent.trim()||'Order'));
    const time=row.querySelector('time,.order-date,.item-order-reference>small');if(time)card.append(node('p',time.textContent.trim()));
    for(const item of row.querySelectorAll('.order-item')){
     const details=node('dl','');
     for(const [selector,label] of [['.item-product','Product / SKU'],['.item-number:not(.item-sales)','Quantity'],['.item-sales','Item sales'],['.item-profit','Estimated profit']]){
      const value=item.querySelector(selector);if(value){const clean=value.cloneNode(true);clean.querySelectorAll('details,nav,small,.item-buy-box,.order-customer-shipping').forEach(n=>n.remove());const text=node('dd',clean.textContent.replace(/\s+/g,' ').trim());
       if(selector==='.item-profit'){const original=value.querySelector('[data-profit-key]');if(original){const button=node('button','Pending');button.type='button';button.className='profit-open';button.dataset.profitKind='ORDER';button.dataset.profitKey=original.dataset.profitKey;button.dataset.profitSku=original.dataset.profitSku||'';text.replaceChildren(button);}}
       details.append(node('dt',label),text);
      }
     }
     card.append(details);
    }
    if(reference){const link=node('a','Review order');const target=new URL('/app/orders',location.origin);target.searchParams.set('status','ALL');target.searchParams.set('f_channel','');target.searchParams.set('q',reference.textContent.trim());link.href=target;card.append(link);}
    body.append(card);
   }
   if(!body.children.length)body.append(node('p','No orders in this period.'));
   const pager=doc.querySelector('.table-pagination'),pages=Math.max(1,Number(pager?.dataset.pageMax)||1);
   dialog.querySelector('[data-metric-page]').textContent='Page '+(page+1)+' of '+pages;prev.disabled=page===0;next.disabled=page+1>=pages;
  }catch(error){if(error.name==='AbortError'||sequence!==current)return;body.replaceChildren(node('p','Could not load this breakdown. Please try again.'));const retry=node('button','Try again');retry.type='button';retry.className='secondary-button';retry.onclick=load;body.append(retry);}
 }
 function open(kind,source){trigger=source;page=0;scope=new URL(kind==='filtered'?location.href:'/app/orders',location.origin);scope.searchParams.set('f_channel',kind==='filtered'?(scope.searchParams.get('f_channel')||''):'');
  if(kind!=='filtered'){scope.searchParams.set('status','ALL');if(kind==='today')scope.searchParams.set('f_date_preset','today');else scope.searchParams.set('f_date_min',new Date(Date.now()-30*86400000).toISOString());}
  dialog.querySelector('[data-metric-context]').textContent=(kind==='today'?'Today':kind==='filtered'?'Current table filters':'Last 30 days')+' · Order purchase dates and SKU associations. Profit estimates are before refunds; unavailable costs remain pending.';
  if(!dialog.open)dialog.showModal();load();
 }
 dialog.addEventListener('close',()=>{request?.abort();sequence++;trigger?.focus();});prev.onclick=()=>{if(page){page--;load();}};next.onclick=()=>{page++;load();};
 function bind(el,kind){if(el.dataset.metricBound)return;el.dataset.metricBound='true';el.classList.add('platform-metric-trigger');el.tabIndex=0;el.setAttribute('role','button');el.setAttribute('aria-haspopup','dialog');
  el.addEventListener('click',e=>{if(e.target.closest('button,a,[data-period-refunds],.platform-metric-trigger')!==el)return;open(kind,el);});
  el.addEventListener('keydown',e=>{if(e.target===el&&(e.key==='Enter'||e.key===' ')){e.preventDefault();open(kind,el);}});
 }
 function enhance(){document.querySelectorAll('.order-summary>article').forEach((card,index)=>{bind(card,index===2?'thirty':'today');card.querySelectorAll('.summary-filtered').forEach(part=>bind(part,'filtered'));});}
 window.NextAiMetricDrilldown={open};enhance();
 const summary=document.querySelector('.order-summary');if(summary)new MutationObserver(enhance).observe(summary,{childList:true,subtree:true});
})();
