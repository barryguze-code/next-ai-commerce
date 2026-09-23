(()=>{
 const host=document.querySelector('#sale-price-dialog .marketplace-sync-pause');if(!host)return;
 host.replaceChildren();const content=document.createElement('div'),title=document.createElement('strong'),description=document.createElement('small'),button=document.createElement('button');
 button.type='button';button.className='secondary-button';button.disabled=true;title.textContent='Amazon shelf-life discounts';description.textContent='Checking production publishing…';content.append(title,description,button);host.append(content);
 let current;
 function render(s){current=s;button.hidden=!s.live;button.disabled=false;button.textContent=s.enabled?'Turn off for this account':'Enable for this account';
  description.textContent=!s.live?'Local/read-only: no Amazon prices are sent. Publishing is restricted to the approved production connection.':s.enabled?'On: eligible near-expiry stock uses your sale defaults. Turning off removes only discounts created by this feature. Expired stock stays unavailable.':'Off: no new discounts are published. Previously managed discounts are being removed if needed.';
  const counts=s.statuses.filter(x=>x.status!=='IDLE').map(x=>x.status.replaceAll('_',' ').toLowerCase()+': '+x.count);if(counts.length)description.textContent+=' '+counts.join(' · ')+'.';
 }
 async function load(){try{const r=await fetch('/app/inventory/sale-publishing',{headers:{Accept:'application/json'}});if(!r.ok)throw Error();render(await r.json());}catch{description.textContent='Publishing status unavailable. No setting changed.';button.disabled=true;}}
 button.onclick=async()=>{if(!current?.live)return;const enabled=!current.enabled;
  if(!await window.NextAiConfirm({title:enabled?'Enable Amazon shelf-life discounts?':'Turn off shelf-life discounts?',message:enabled?'Publish eligible sale plans and automatic near-expiry discounts for the approved Amazon connection in this account. Existing unrelated promotions and regular prices will be left unchanged.':'Stop new sale discounts and remove this feature’s active discounts. Regular prices and unrelated Amazon promotions remain unchanged.',accept:enabled?'Enable discounts':'Turn off discounts'}))return;
  button.disabled=true;try{const token=document.querySelector('#sale-price-dialog input[name="_csrf"]');const data=new URLSearchParams({enabled:String(enabled)});if(token)data.set('_csrf',token.value);const r=await fetch('/app/inventory/sale-publishing',{method:'POST',body:data,headers:{Accept:'application/json'}});if(!r.ok)throw Error();render(await r.json());}catch{description.textContent='The setting could not be confirmed. Refresh to check before retrying.';button.disabled=false;}
 };
 new MutationObserver(()=>{if(document.getElementById('sale-price-dialog').open)load();}).observe(document.getElementById('sale-price-dialog'),{attributes:true,attributeFilter:['open']});load();
})();
