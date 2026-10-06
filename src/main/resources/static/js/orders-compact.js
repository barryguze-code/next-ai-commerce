(()=>{'use strict';
 const url=new URL(location.href),cookie='orders_fulfillment';
 const saved=document.cookie.split('; ').find(v=>v.startsWith(cookie+'='))?.split('=')[1];
 if(!url.searchParams.has('f_channel')&&['FBM','FBA'].includes(saved)){url.searchParams.set('f_channel',saved);url.searchParams.delete('page');location.replace(url);return;}
 const selected=url.searchParams.get('f_channel')||'';
 let summaryRequest,summaryAt=0;
 const money=n=>new Intl.NumberFormat('en-US',{style:'currency',currency:'USD'}).format(n);
 if(url.searchParams.has('f_channel')&&['','FBM','FBA'].includes(selected))document.cookie=cookie+'='+selected+'; Max-Age=31536000; Path=/; SameSite=Lax'+(location.protocol==='https:'?'; Secure':'');
 function enhance(){
  const period=document.querySelector('.order-summary>article:nth-child(3)');
  if(period&&!period.querySelector('[data-period-profit]')){
   period.classList.add('order-period-summary');
   const details=document.createElement('div');details.className='period-financials';
   details.innerHTML='<small>Est. profit · 30 days</small><b data-period-profit>Calculating…</b><small class="period-refunds" data-period-refunds>Refunds: loading…</small>';
   const volume=document.createElement('small');volume.dataset.periodVolume='';volume.textContent='Orders / units: loading…';
   period.querySelector('div')?.append(volume,details);
  }
  const card=document.querySelector('.order-profit-summary');if(card&&!card.dataset.profitReady){card.dataset.profitReady='true';loadSummary();}
  const heading=document.querySelector('.stream-heading'),note=document.querySelector('.order-toolbar>div>p');
  if(heading&&note){note.classList.add('order-heading-note');heading.append(note);}
  const plus=document.querySelector('.smart-tag-add');
  if(plus&&!plus.parentElement.querySelector('.order-fulfillment-tabs')){
   const nav=document.createElement('nav');nav.className='order-fulfillment-tabs';nav.setAttribute('aria-label','Fulfillment channel');
   for(const [value,label] of [['','All'],['FBM','FBM'],['FBA','FBA']]){const link=document.createElement('a'),next=new URL(location.href);next.searchParams.set('f_channel',value);next.searchParams.delete('page');next.searchParams.delete('goToPage');link.href=next;link.textContent=label;if(value===selected)link.setAttribute('aria-current','page');link.onclick=()=>{document.cookie=cookie+'='+value+'; Max-Age=31536000; Path=/; SameSite=Lax'+(location.protocol==='https:'?'; Secure':'');};nav.append(link);}plus.before(nav);
  }
  const input=document.querySelector('.order-search input[name=q]');
  if(input&&!input.dataset.liveSearch){input.dataset.liveSearch='true';let timer,composing=false;
   const run=()=>{clearTimeout(timer);if(composing)return;timer=setTimeout(()=>{const value=input.value.trim();if(value.length===1||value===(new URL(location.href).searchParams.get('q')||''))return;const next=new URL(location.href);next.searchParams.set('q',value);next.searchParams.delete('page');next.searchParams.delete('goToPage');location.assign(next);},700);};
   input.addEventListener('input',run);input.addEventListener('compositionstart',()=>{composing=true;clearTimeout(timer)});input.addEventListener('compositionend',()=>{composing=false;run()});input.form?.addEventListener('submit',()=>clearTimeout(timer));
  }
 }
 let pending=false;new MutationObserver(()=>{if(pending)return;pending=true;queueMicrotask(()=>{pending=false;enhance()})}).observe(document.querySelector('.orders-workspace'),{childList:true,subtree:true});enhance();
 function loadSummary(){
 if(!summaryRequest||Date.now()-summaryAt>60000){summaryAt=Date.now();summaryRequest=fetch('/app/orders/profit-summary?'+url.searchParams,{headers:{Accept:'application/json'}}).then(r=>{if(!r.ok)throw Error();return r.json()});}
 summaryRequest.then(data=>{
  for(const [key,amountSelector,coverageSelector] of [['today','[data-today-profit]','[data-profit-coverage]'],['filtered','[data-filtered-profit]','[data-filtered-profit-coverage]'],['thirtyDays','[data-period-profit]',null]]){
   const total=data[key],amount=document.querySelector(amountSelector),coverage=document.querySelector(coverageSelector);if(!amount)continue;
   amount.textContent=total.estimated||!total.pending?money(total.amount):'Pending';amount.title=(key==='thirtyDays'?'Store-wide orders purchased in the last 30 days. ':'Whole matching orders. ')+'Current cost estimates before refunds; excludes cancelled orders. '+total.estimated+' estimated · '+total.pending+' pending costs. Cached for up to one minute.';
   if(coverage)coverage.textContent=total.pending?`${total.estimated} estimated · ${total.pending} pending`:'Before refunds · estimated';
   if(key==='thirtyDays'&&total.pending&&total.estimated)amount.textContent+=' *';
  }
  const volume=document.querySelector('[data-period-volume]');if(volume&&data.volumeThirtyDays){volume.textContent=data.volumeThirtyDays.orders.toLocaleString()+' orders / '+data.volumeThirtyDays.units.toLocaleString()+' units';volume.title='All store orders purchased in the last 30 days, including cancelled orders.';}
  const refund=document.querySelector('[data-period-refunds]'),entries=data.refundsThirtyDays||[];
  if(refund){refund.textContent=entries.length?entries.map(e=>{
    const value=e.amount===null||!e.currency?'Amount pending':new Intl.NumberFormat('en-US',{style:'currency',currency:e.currency}).format(-e.amount);
    return '↩ '+value+' · '+e.orders+(e.unknownOrders?' known':'')+(e.orders===1?' order':' orders')+(e.pendingAmounts?' + pending':'');
   }).join(' / '):'Refunds: no imported records';
   refund.title='Store-wide item-price refunds posted in the last 30 days, including refunds for older purchases. Distinct orders per currency. Excludes tax, shipping and fees. Imported records may be incomplete; these amounts have not been subtracted from estimated profit.';
   if(entries.length){refund.setAttribute('role','button');refund.tabIndex=0;refund.onclick=()=>window.NextAiRefundOrders?.({days:30},refund);refund.onkeydown=e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();refund.click();}};}
  }
 }).catch(()=>{summaryRequest=null;document.querySelectorAll('[data-today-profit],[data-filtered-profit],[data-period-profit],[data-period-refunds]').forEach(n=>n.textContent='Temporarily unavailable')});
 }
})();
