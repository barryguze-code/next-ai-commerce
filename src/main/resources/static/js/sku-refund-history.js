/* Shared SKU history: posted-date item-price refunds, never inventory returns. */
(()=>{'use strict';
 function render(){document.querySelectorAll('[data-sku-refunds]:not([data-refunds-ready])').forEach(cell=>{
  cell.dataset.refundsReady='true';
  let entries;try{entries=JSON.parse(cell.dataset.skuRefunds||'[]')}catch{return;}
  const product=cell.hasAttribute?.('data-refund-product')||false,shared=entries.some(e=>e.sharedRefunds>0);
  if(!entries.length){cell.title='Refund data unavailable';return;}
  const amounts=entries.filter(e=>e.amount!==null&&e.currency),unknown=entries.some(e=>e.unknownAmounts||!e.currency);
  const currencies=[...new Set(amounts.map(e=>e.currency))];
  const total=currency=>amounts.filter(e=>e.currency===currency).reduce((n,e)=>n+Number(e.amount),0);
  const money=(n,c)=>{try{return new Intl.NumberFormat('en-US',{style:'currency',currency:c}).format(n)}catch{return c+' '+n.toFixed(2)}};
  const line=document.createElement('button');line.type='button';line.className='sku-refund-total';line.tabIndex=0;
  const scope=product?{product:cell.dataset.refundProduct,productName:cell.dataset.refundProductName,account:cell.dataset.refundAccount}:{sku:cell.dataset.refundSku};
  line.onclick=()=>window.NextAiRefundOrders?.(scope,line);
  const orders=entries[0].totalOrders,missingOrders=entries[0].unknownOrders;
  const orderLabel=orders===undefined?'':orders===0&&missingOrders?' · Orders pending':' · '+orders+(missingOrders?' known':'')+(orders===1?' order':' orders');
  line.textContent='↩ '+(currencies.map(c=>money(-total(c),c)).join(' / ')||(shared?'Shared bundle refund':'Amount pending'))+(unknown&&currencies.length?' + pending':'')+(shared&&currencies.length?' + shared bundle refund':'')+orderLabel;
  line.title='See Refunded Orders';line.setAttribute('aria-label','See Refunded Orders: '+line.textContent);
  const bars=document.createElement('span');bars.className='sku-refund-bars';bars.setAttribute('aria-label','Weekly refund amounts');
  const max=Math.max(1,...amounts.map(e=>Math.abs(Number(e.amount))));
  for(let week=0;week<4;week++){
   const list=entries.filter(e=>e.week===week),bar=document.createElement('button');bar.type='button';
   bar.onclick=()=>window.NextAiRefundOrders?.({...scope,week},bar);
   const value=list.filter(e=>e.amount!==null).reduce((n,e)=>n+Math.abs(Number(e.amount)),0);
   // Mixed currencies are shown as presence markers, never summed for scale.
   bar.style.height=(list.length?(currencies.length>1?4:Math.max(2,Math.min(10,10*value/max))):0)+'px';
   const end=new Date(Date.now()-(3-week)*7*86400000),start=new Date(end.getTime()-7*86400000);
   bar.title=start.toLocaleDateString()+' – '+end.toLocaleDateString()+': '+(list.length?list.map(e=>(e.sharedRefunds>0&&e.amount===null?'Shared bundle refund (amount not allocated)':e.amount===null?'Refund amount pending':money(-Number(e.amount),e.currency||'USD'))+(e.orders===undefined?'':' · '+e.orders+(e.orders===1?' order':' orders'))).join(' / '):'No refund records imported for this week');
   const weekDescription=bar.title;bar.title=list.length?'See Refunded Orders':'No refund data';
   bar.tabIndex=list.length?0:-1;bar.disabled=!list.length;bar.setAttribute('aria-label',weekDescription);bars.append(bar);
  }
  cell.classList.add('has-sku-refunds');cell.append(bars,line);cell.title='';
 });}
 let queued=false;new MutationObserver(()=>{if(!queued){queued=true;queueMicrotask(()=>{queued=false;render()})}}).observe(document.body,{childList:true,subtree:true});render();
})();
