/* Read-only, paged refund evidence. Never calls Amazon or changes order state. */
(()=>{'use strict';
 const dialog=document.createElement('dialog');dialog.className='refund-order-drawer';dialog.setAttribute('aria-labelledby','refund-orders-title');
 dialog.innerHTML='<header><img src="/images/channels/amazon-seller.png" alt="Amazon"><div><small>Amazon order history</small><h2 id="refund-orders-title">Refunded orders</h2><p data-context></p></div><button type="button" class="dialog-close" aria-label="Close refunded orders">×</button></header><p class="refund-scope-note">Refund posting dates · Item amounts only, excluding tax, shipping and fees. A refund does not mean stock was returned.</p><div class="refund-order-list" role="status"></div><footer><button type="button" class="secondary-button" data-prev>Previous</button><span data-page></span><button type="button" class="secondary-button" data-next>Next</button></footer>';
 document.body.append(dialog);let request,sequence=0,scope={},page=0,trigger;
 const list=dialog.querySelector('.refund-order-list'),prev=dialog.querySelector('[data-prev]'),next=dialog.querySelector('[data-next]');
 const node=(tag,text,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;if(cls)n.className=cls;return n};
 const money=(value,currency)=>{if(value==null||!currency)return 'Amount pending';try{return new Intl.NumberFormat('en-US',{style:'currency',currency}).format(-Number(value))}catch{return currency+' '+(-Number(value)).toFixed(2)}};
 async function load(){
  request?.abort();request=new AbortController();const current=++sequence;prev.disabled=next.disabled=true;list.replaceChildren(node('p','Loading refunded orders…'));dialog.querySelector('[data-page]').textContent='Page '+(page+1);
  try{const response=await fetch('/app/orders/refund-history?'+new URLSearchParams({...scope,page}),{signal:request.signal,headers:{Accept:'application/json'}});if(!response.ok)throw Error();const data=await response.json();if(current!==sequence||!dialog.open)return;scope.asOf=data.asOf;list.replaceChildren();
   for(const row of data.rows){const card=node('article'),picture=node('img');picture.alt='';picture.src=row.imageUrl&&/^https?:\/\//i.test(row.imageUrl)?row.imageUrl:'/images/channels/amazon-seller.png';picture.loading='lazy';
    const details=node('div',null,'refund-order-copy');details.append(node('strong',row.title||row.sellerSku||'Amazon order'),node('small',row.sellerSku||'SKU unavailable'));
    if(row.orderId){const link=node('a',row.orderId);link.href='/app/orders?status=ALL&f_channel=&q='+encodeURIComponent(row.orderId);link.target='_blank';link.rel='noopener';link.title='Open this order without losing your table position';details.append(link);}else details.append(node('span','Order ID not supplied'));
    details.append(node('small',(row.status||'Order status unavailable')+' · Refunded '+new Date(row.postedDate).toLocaleDateString()));
    const amount=node('div',null,'refund-order-amount');amount.append(node('strong','↩ '+money(row.amount,row.currency)),node('small',row.units==null?'Refunded units pending':row.units+' refunded units'));if(row.pendingAmounts)amount.append(node('small','Includes pending amounts'));
    card.append(picture,details,amount);list.append(card);
   }
   if(!data.rows.length)list.append(node('p','No imported refunds in this period.'));prev.disabled=page===0;next.disabled=!data.hasMore;
  }catch(error){if(error.name==='AbortError'||current!==sequence)return;list.replaceChildren(node('p','Refunded orders could not be loaded.'));const retry=node('button','Try again','secondary-button');retry.type='button';retry.onclick=load;list.append(retry);}
 }
 window.NextAiRefundOrders=(options,source)=>{trigger=source;scope={sku:options.sku||'',days:options.days||28,week:options.week??-1};page=0;dialog.querySelector('[data-context]').textContent=(options.sku||'All SKUs in this store')+' · '+(scope.week<0?'Last '+scope.days+' days':'Selected refund week');if(!dialog.open)dialog.showModal();load();};
 dialog.querySelector('header button').onclick=()=>dialog.close();dialog.addEventListener('click',e=>{if(e.target===dialog){const r=dialog.getBoundingClientRect();if(e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom)dialog.close();}});
 dialog.addEventListener('close',()=>{request?.abort();sequence++;trigger?.focus();});prev.onclick=()=>{if(page>0){page--;load();}};next.onclick=()=>{page++;load();};
})();
