(()=>{
  let timer;
  function notify(message){
    let notice=document.querySelector('[data-order-action-notice]');
    if(!notice){notice=document.createElement('div');notice.className='stream-toast';notice.dataset.orderActionNotice='';notice.setAttribute('role','status');document.body.append(notice);}
    notice.textContent=message;notice.classList.add('show');clearTimeout(timer);timer=setTimeout(()=>notice.classList.remove('show'),3500);
  }
  document.addEventListener('click',async event=>{
    const button=event.target.closest('[data-copy-sku]');if(!button)return;
    try{await navigator.clipboard.writeText(button.dataset.copySku);notify('SKU copied');}
    catch(_){notify('Could not copy SKU. Allow clipboard access and try again.');}
  });
  const tip=document.createElement('div');tip.className='sku-instant-tooltip';tip.setAttribute('role','tooltip');tip.hidden=true;document.body.append(tip);
  function showSku(event){const button=event.target.closest('[data-copy-sku]');if(!button)return;tip.replaceChildren(button.querySelector('svg').cloneNode(true),document.createTextNode(button.dataset.copySku));tip.hidden=false;const rect=button.getBoundingClientRect();tip.style.left=Math.max(8,Math.min(rect.left,innerWidth-tip.offsetWidth-8))+'px';tip.style.top=Math.max(8,Math.min(rect.bottom+6,innerHeight-tip.offsetHeight-8))+'px';}
  function showReference(event){const button=event.target.closest('.order-reference-options button');if(!button)return;if(button.title){button.dataset.referenceTip=button.title.replace(/^(ASIN|Item code)\s+/i,'');button.removeAttribute('title');}tip.textContent=button.dataset.referenceTip||'';tip.hidden=false;const rect=button.getBoundingClientRect();tip.style.left=Math.max(8,Math.min(rect.left,innerWidth-tip.offsetWidth-8))+'px';tip.style.top=Math.max(8,Math.min(rect.bottom+6,innerHeight-tip.offsetHeight-8))+'px';}
  document.addEventListener('mouseover',showSku);document.addEventListener('mouseover',showReference);
  document.addEventListener('mouseout',()=>tip.hidden=true);document.addEventListener('focusout',()=>tip.hidden=true);window.addEventListener('scroll',()=>tip.hidden=true,true);
  window.addEventListener('blur',()=>tip.hidden=true);document.addEventListener('click',()=>tip.hidden=true);
  let refreshOnReturn=false;
  window.addEventListener('storage',event=>{if(event.key==='nextai-order-updated')refreshOnReturn=true;});
  window.addEventListener('focus',async()=>{
    if(!refreshOnReturn||window.NextAiOrderActionPending)return;
    window.NextAiOrderActionPending=true;
    try{
      const response=await fetch(location.href,{cache:'no-store',headers:{'X-Order-Stream':'refresh'}});if(!response.ok)throw new Error();
      const incoming=new DOMParser().parseFromString(await response.text(),'text/html').querySelector('[data-order-stream]'),current=document.querySelector('[data-order-stream]');if(!incoming||!current)throw new Error();
      const positions=['.orders-workspace','.order-list.table-widget-scroll'].map(selector=>{const el=document.querySelector(selector);return {selector,top:el?.scrollTop||0,left:el?.scrollLeft||0};});const x=scrollX,y=scrollY;
      current.replaceWith(incoming);window.NextAiTableWidget?.refresh();
      const restore=()=>{positions.forEach(p=>{const el=document.querySelector(p.selector);if(el){el.scrollTop=p.top;el.scrollLeft=p.left;}});window.scrollTo(x,y);};restore();requestAnimationFrame(restore);refreshOnReturn=false;
    }catch(_){notify('Order saved. Refresh Orders to see the latest status.');}finally{window.NextAiOrderActionPending=false;}
  });
  document.addEventListener('submit',async event=>{
    const form=event.target.closest('.platform-pickup-action');
    if(!form||event.defaultPrevented)return;
    event.preventDefault();
    if(window.NextAiOrderActionPending)return;
    window.NextAiOrderActionPending=true;
    const button=form.querySelector('button[type=submit]');
    const body=new FormData(form),waiting=body.get('waiting')==='true';
    button.disabled=true;
    let saved=false;
    try{
      const result=await fetch(form.action,{method:'POST',body,credentials:'same-origin'});
      if(!result.ok||new URL(result.url).pathname==='/login')throw new Error('save');
      saved=true;
      const response=await fetch(location.href,{cache:'no-store',headers:{'X-Order-Stream':'refresh'}});
      if(!response.ok)throw new Error('refresh');
      const incoming=new DOMParser().parseFromString(await response.text(),'text/html').querySelector('[data-order-stream]');
      const current=document.querySelector('[data-order-stream]');
      if(!incoming||!current)throw new Error('refresh');
      // Capture immediately before replacement so scrolling during the request is preserved.
      const selectors=['.orders-workspace','.order-list.table-widget-scroll'];
      const positions=selectors.map(selector=>{const el=document.querySelector(selector);return {selector,top:el?.scrollTop||0,left:el?.scrollLeft||0};});
      const x=window.scrollX,y=window.scrollY;
      current.replaceWith(incoming);
      window.NextAiTableWidget?.refresh();
      const restore=()=>{positions.forEach(({selector,top,left})=>{const el=document.querySelector(selector);if(el){el.scrollTop=top;el.scrollLeft=left;}});window.scrollTo(x,y);};
      restore();
      requestAnimationFrame(restore);
      notify(waiting?'Marked shipped — waiting for pickup':'Pickup mark removed');
    }catch(_){
      notify(saved?'Saved. Could not refresh the table; refresh when ready.':'Could not save. Please try again.');
    }finally{
      button.disabled=false;
      window.NextAiOrderActionPending=false;
    }
  });
})();
