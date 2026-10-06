/* Shared SKU history: posted-date item-price refunds, never inventory returns. */
(()=>{'use strict';
 function render(){document.querySelectorAll('[data-sku-refunds]:not([data-refunds-ready])').forEach(cell=>{
  cell.dataset.refundsReady='true';
  let entries;try{entries=JSON.parse(cell.dataset.skuRefunds||'[]')}catch{return;}
  const notice='SKU refund history by refund posting date. Item price only; excludes tax, shipping and Amazon fees. Imported records may be incomplete or delayed; refunded units do not imply stock returned.';
  if(!entries.length){cell.title='Refund data unavailable or no refunds in imported records. '+notice;return;}
  const amounts=entries.filter(e=>e.amount!==null&&e.currency),unknown=entries.some(e=>e.unknownAmounts||!e.currency);
  const currencies=[...new Set(amounts.map(e=>e.currency))];
  const total=currency=>amounts.filter(e=>e.currency===currency).reduce((n,e)=>n+Number(e.amount),0);
  const money=(n,c)=>{try{return new Intl.NumberFormat('en-US',{style:'currency',currency:c}).format(n)}catch{return c+' '+n.toFixed(2)}};
  const line=document.createElement('span');line.className='sku-refund-total';line.tabIndex=0;
  const knownUnits=entries.every(e=>!e.unknownUnits&&e.units!==null),units=entries.reduce((n,e)=>n+Number(e.units||0),0);
  const orders=entries[0].totalOrders,missingOrders=entries[0].unknownOrders;
  const orderLabel=orders===undefined?'':orders===0&&missingOrders?' · Orders pending':' · '+orders+(missingOrders?' known':'')+(orders===1?' order':' orders');
  line.textContent='↩ '+(currencies.map(c=>money(-total(c),c)).join(' / ')||'Amount pending')+(unknown&&currencies.length?' + pending':'')+orderLabel;
  line.title=notice+' Distinct refunded orders across all four weeks; repeat refunds on one order count once.'+(knownUnits?' Refunded units: '+units+'.':' Amazon has not supplied confirmed refunded-unit counts.');line.setAttribute('aria-label',line.textContent+'. '+line.title);
  const bars=document.createElement('span');bars.className='sku-refund-bars';bars.setAttribute('aria-label','Weekly refund amounts');
  const max=Math.max(1,...amounts.map(e=>Math.abs(Number(e.amount))));
  for(let week=0;week<4;week++){
   const list=entries.filter(e=>e.week===week),bar=document.createElement('span');
   const value=list.filter(e=>e.amount!==null).reduce((n,e)=>n+Math.abs(Number(e.amount)),0);
   // Mixed currencies are shown as presence markers, never summed for scale.
   bar.style.height=(list.length?(currencies.length>1?4:Math.max(2,Math.min(10,10*value/max))):0)+'px';
   const end=new Date(Date.now()-(3-week)*7*86400000),start=new Date(end.getTime()-7*86400000);
   bar.title=start.toLocaleDateString()+' – '+end.toLocaleDateString()+': '+(list.length?list.map(e=>(e.amount===null?'Refund amount pending':money(-Number(e.amount),e.currency||'USD'))+(e.orders===undefined?'':' · '+e.orders+(e.orders===1?' order':' orders'))).join(' / '):'No refund records imported for this week')+'. '+notice;
   bar.tabIndex=0;bar.setAttribute('aria-label',bar.title);bars.append(bar);
  }
  cell.classList.add('has-sku-refunds');cell.append(bars,line);cell.title=notice;
 });}
 let queued=false;new MutationObserver(()=>{if(!queued){queued=true;queueMicrotask(()=>{queued=false;render()})}}).observe(document.body,{childList:true,subtree:true});render();
})();
