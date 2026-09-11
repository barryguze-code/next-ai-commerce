/* Shared marketplace shortcuts and storage-location picker. Native form values remain authoritative. */
(() => {
  if (window.NextAiPlatformControls) return;
  function shortcuts(root = document) {
    root.querySelectorAll('[data-marketplace-shortcuts]:not([data-shortcuts-ready])').forEach(host => {
      host.dataset.shortcutsReady = 'true';
      if (host.dataset.channel && host.dataset.channel.toUpperCase() !== 'AMAZON') return;
      host.classList.add('marketplace-links', 'standard-marketplace-shortcuts');
      const sku = host.dataset.sku || '', asin = host.dataset.asin || '';
      const domain = host.dataset.amazonDomain || 'amazon.com';
      // Domains are supplied by the server; never turn arbitrary imported text into a link.
      if (!/^amazon\.(com|ca|co\.uk|de|fr|it|es|nl|se|pl|com\.be|com\.au|co\.jp|in|com\.mx|com\.br)$/.test(domain)) return;
      const add = (url, label, asset) => {
        const link = document.createElement('a');link.href=url;link.target='_blank';link.rel='noopener noreferrer';
        link.title=label;link.setAttribute('aria-label',label);
        const image=document.createElement('img');image.src='/images/channels/'+asset+'.svg';image.alt='';
        link.append(image);host.append(link);
      };
      if(sku)add('https://sellercentral.'+domain+'/myinventory/inventory?searchTerm='+encodeURIComponent(sku),'Open '+sku+' in Seller Central','amazon');
      if(asin)add('https://www.'+domain+'/dp/'+encodeURIComponent(asin),'Open '+asin+' on Amazon','amazon-product');
    });
  }
  let activePicker;
  function locationPicker(select) {
    if(select.dataset.locationPickerReady || select.multiple)return;
    select.dataset.locationPickerReady='true';
    const trigger=document.createElement('button');trigger.type='button';trigger.className='location-picker-trigger';
    const compact=Boolean(select.closest('.default-location-form,td'));trigger.classList.toggle('is-compact',compact);
    const popup=document.createElement('div');popup.className='location-picker-popup';popup.hidden=true;
    popup.setAttribute('role','listbox');popup.id='location-picker-'+(++locationPicker.counter);
    trigger.setAttribute('aria-haspopup','listbox');trigger.setAttribute('aria-controls',popup.id);trigger.setAttribute('aria-expanded','false');
    const close=(focus=false)=>{popup.hidden=true;trigger.setAttribute('aria-expanded','false');if(activePicker?.trigger===trigger)activePicker=null;if(focus)trigger.focus();};
    const sync=()=>{
      const label=select.selectedOptions[0]?.textContent.trim()||'Choose location';
      const code=label.split(' · ')[0];trigger.textContent=compact?code.substring(0,2).toUpperCase():label;
      trigger.title=label;trigger.disabled=select.disabled;
      trigger.setAttribute('aria-label',(select.getAttribute('aria-label')||'Storage location')+': '+label);
    };
    const open=()=>{
      activePicker?.close();popup.replaceChildren();
      [...select.options].filter(option=>!option.hidden).forEach(option=>{
        const choice=document.createElement('button');choice.type='button';choice.setAttribute('role','option');
        choice.setAttribute('aria-selected',String(option.selected));choice.disabled=option.disabled;
        const badge=document.createElement('span');badge.className='location-option-code';badge.textContent=option.textContent.trim().split(' · ')[0].substring(0,2).toUpperCase();
        const label=document.createElement('span');label.textContent=option.textContent.trim();choice.append(badge,label);
        choice.onclick=()=>{select.value=option.value;sync();close(true);select.dispatchEvent(new Event('change',{bubbles:true}));};popup.append(choice);
      });
      (select.closest('dialog')||document.body).append(popup);popup.hidden=false;
      const rect=trigger.getBoundingClientRect(),width=Math.min(300,window.innerWidth-24);
      popup.style.width=width+'px';popup.style.left=Math.max(12,Math.min(rect.left,window.innerWidth-width-12))+'px';
      popup.style.maxHeight=Math.min(300,window.innerHeight-32)+'px';
      popup.style.top=Math.max(12,Math.min(rect.bottom+6,window.innerHeight-popup.offsetHeight-12))+'px';
      trigger.setAttribute('aria-expanded','true');activePicker={trigger,close};
      (popup.querySelector('[aria-selected=true]:not(:disabled)')||popup.querySelector('button:not(:disabled)'))?.focus();
    };
    popup.addEventListener('keydown',event=>{
      const options=[...popup.querySelectorAll('button:not(:disabled)')],index=options.indexOf(document.activeElement);
      if(event.key==='Escape'){event.preventDefault();event.stopPropagation();close(true);}
      else if(['ArrowDown','ArrowUp','Home','End'].includes(event.key)){event.preventDefault();const next=event.key==='Home'?0:event.key==='End'?options.length-1:(index+(event.key==='ArrowDown'?1:-1)+options.length)%options.length;options[next]?.focus();}
      else if(event.key==='Tab')close();
    });
    trigger.onclick=()=>popup.hidden?open():close();
    trigger.addEventListener('keydown',event=>{if(event.key==='ArrowDown'||event.key==='ArrowUp'){event.preventDefault();open();}});
    document.addEventListener('pointerdown',event=>{if(!popup.hidden&&!popup.contains(event.target)&&!trigger.contains(event.target))close();});
    select.addEventListener('change',sync);select.addEventListener('invalid',event=>{event.preventDefault();trigger.focus();open();});
    select.form?.addEventListener('reset',()=>setTimeout(sync,0));
    new MutationObserver(sync).observe(select,{childList:true,subtree:true,attributes:true,attributeFilter:['disabled','selected']});
    select.after(trigger);select.hidden=true;sync();
  }
  locationPicker.counter=0;
  function enhance(root=document) {
    shortcuts(root);
    root.querySelectorAll('select[name="locationId"],select[name="destinationLocationId"],.receipt-location-select').forEach(locationPicker);
    root.querySelectorAll('td .location-chip:not(button):not([data-location-badge])').forEach(chip=>{
      const code=chip.querySelector('strong'),detail=chip.querySelector('small');if(!code)return;
      chip.title=[code.textContent,detail?.textContent].filter(Boolean).join(' · ');chip.setAttribute('aria-label',chip.title);
      code.textContent=code.textContent.slice(0,2).toUpperCase();if(detail)detail.hidden=true;chip.classList.add('table-location-badge');chip.dataset.locationBadge='true';
    });
  }
  window.NextAiMarketplaceShortcuts={enhance:shortcuts};window.NextAiPlatformControls={enhance};
  document.addEventListener('scroll',event=>{if(!event.target.closest?.('.location-picker-popup'))activePicker?.close();},true);window.addEventListener('resize',()=>activePicker?.close());
  enhance();
})();
