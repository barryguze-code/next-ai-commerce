/* Shared marketplace shortcuts and storage-location picker. Native form values remain authoritative. */
(() => {
  if (window.NextAiPlatformControls) return;
  function shortcuts(root = document) {
    root.querySelectorAll('[data-marketplace-shortcuts]:not([data-shortcuts-ready])').forEach(host => {
      host.dataset.shortcutsReady = 'true';
      if(host.dataset.channel?.toUpperCase()==='SHOPIFY'){
        host.classList.add('marketplace-links','standard-marketplace-shortcuts');
        const add=(value,label)=>{try{const url=new URL(value);if(url.protocol!=='https:'||url.username||url.password||url.port)return;
          if(label==='Open in Shopify Admin'&&!/^[a-z0-9][a-z0-9-]*\.myshopify\.com$/.test(url.hostname))return;
          const link=document.createElement('a');link.href=url.href;link.target='_blank';link.rel='noopener noreferrer';link.title=label;link.setAttribute('aria-label',label);
          if(label==='Open in Shopify Admin'){const img=document.createElement('img');img.src='/images/channels/shopify-bag.png';img.alt='';link.append(img);}else link.textContent='↗';host.append(link);
        }catch(e){/* No untrusted imported link. */}};
        if(host.dataset.shopifyAdmin)add(host.dataset.shopifyAdmin,'Open in Shopify Admin');
        if(host.dataset.shopifyStorefront)add(host.dataset.shopifyStorefront,'Open Shopify storefront');return;
      }
      if (host.dataset.channel && host.dataset.channel.toUpperCase() !== 'AMAZON') return;
      host.classList.add('marketplace-links', 'standard-marketplace-shortcuts');
      const sku = host.dataset.sku || '', asin = host.dataset.asin || '';
      const domain = host.dataset.amazonDomain || 'amazon.com';
      // Domains are supplied by the server; never turn arbitrary imported text into a link.
      if (!/^amazon\.(com|ca|co\.uk|de|fr|it|es|nl|se|pl|com\.be|com\.au|co\.jp|in|com\.mx|com\.br)$/.test(domain)) return;
      const add = (url, label, asset) => {
        const link = document.createElement('a');link.href=url;link.target='_blank';link.rel='noopener noreferrer';
        link.title=label;link.setAttribute('aria-label',label);
        const image=document.createElement('img');image.src=asset==='seller-central'?'/images/platform/table/seller-central.png?v=20260922-26':'/images/platform/table/amazon.com-logo.png?v=20260922-26';image.alt='';
        link.append(image);host.append(link);
      };
      if(asin||sku)add('https://sellercentral.'+domain+'/myinventory/inventory?searchTerm='+encodeURIComponent(asin||sku),'Open '+(asin||sku)+' in Seller Central','seller-central');
      if(asin)add('https://www.'+domain+'/dp/'+encodeURIComponent(asin),'Open '+asin+' on Amazon','amazon-product');
    });
  }
  let activePicker;
  function multipleChoice(select){
    if(select.dataset.locationPickerReady)return;select.dataset.locationPickerReady='true';
    const trigger=document.createElement('button');trigger.type='button';trigger.className='location-picker-trigger standard-choice-trigger';
    const popup=document.createElement('div');popup.className='location-picker-popup standard-choice-popup';popup.hidden=true;popup.setAttribute('role','listbox');popup.setAttribute('aria-multiselectable','true');popup.id='multiple-choice-'+(++locationPicker.counter);
    const label=select.getAttribute('aria-label')||'Options';popup.setAttribute('aria-label',label);trigger.setAttribute('aria-haspopup','listbox');trigger.setAttribute('aria-controls',popup.id);trigger.setAttribute('aria-expanded','false');
    const close=()=>{popup.hidden=true;trigger.setAttribute('aria-expanded','false');if(activePicker?.trigger===trigger)activePicker=null;};
    const position=()=>{const r=trigger.getBoundingClientRect();popup.style.position='fixed';popup.style.left=Math.max(8,Math.min(r.left,innerWidth-Math.max(r.width,220)-8))+'px';popup.style.width=Math.max(r.width,220)+'px';const below=innerHeight-r.bottom-8;popup.style.maxHeight=Math.max(100,Math.min(300,below>100?below:r.top-12))+'px';popup.style.top=(below>100?r.bottom+4:Math.max(8,r.top-304))+'px';};
    const sync=()=>{const chosen=[...select.selectedOptions];trigger.textContent=chosen.length?label+' · '+chosen.length:select.dataset.emptyLabel||'All options';trigger.setAttribute('aria-label',trigger.textContent);trigger.insertAdjacentHTML('beforeend','<svg class="location-choice-chevron" viewBox="0 0 16 16" aria-hidden="true"><path d="m4 6 4 4 4-4"/></svg>');trigger.disabled=select.disabled;popup.replaceChildren();
      const add=(text,selected,action,disabled=false)=>{const b=document.createElement('button');b.type='button';b.setAttribute('role','option');b.setAttribute('aria-selected',String(selected));b.disabled=disabled;b.textContent=text;b.onclick=()=>{action();select.dispatchEvent(new Event('change',{bubbles:true}));sync();[...popup.children].find(n=>n.textContent===text)?.focus();};popup.append(b);};
      add(select.dataset.emptyLabel||'All options',!chosen.length,()=>{for(const o of select.options)o.selected=false;});for(const o of select.options)if(!o.hidden)add(o.textContent,o.selected,()=>{o.selected=!o.selected;},o.disabled);
    };
    trigger.onclick=()=>{if(!popup.hidden){close();return;}activePicker?.close();sync();popup.hidden=false;trigger.setAttribute('aria-expanded','true');activePicker={trigger,popup,close,position};position();popup.querySelector('button')?.focus();};
    popup.onkeydown=e=>{const buttons=[...popup.children],i=buttons.indexOf(document.activeElement);if(e.key==='Escape'){e.preventDefault();e.stopPropagation();close();trigger.focus();}else if(['ArrowDown','ArrowUp','Home','End'].includes(e.key)){e.preventDefault();buttons[e.key==='Home'?0:e.key==='End'?buttons.length-1:(i+(e.key==='ArrowDown'?1:-1)+buttons.length)%buttons.length]?.focus();}else if(e.key==='Tab')close();};
    select.addEventListener('platform-choice-sync',sync);select.addEventListener('change',sync);select.after(trigger);select.hidden=true;(trigger.closest('dialog')||document.body).append(popup);sync();
  }
  function locationPicker(select) {
    if(select.multiple){if(select.hasAttribute('data-standard-choice'))multipleChoice(select);return;}
    if(select.dataset.locationPickerReady)return;
    select.dataset.locationPickerReady='true';
    const trigger=document.createElement('button');trigger.type='button';trigger.className='location-picker-trigger';
    const compact=Boolean(select.closest('.default-location-form,td'));trigger.classList.toggle('is-compact',compact);
    const popup=document.createElement('div');popup.className='location-picker-popup';popup.hidden=true;
    if(select.hasAttribute('data-standard-choice')){trigger.classList.add('standard-choice-trigger');popup.classList.add('standard-choice-popup');}
    let managed=select.dataset.choiceManager;
    if(managed){popup.setAttribute('aria-label',managed==='warehouse'?'Choose warehouse':managed==='vendor'?'Choose vendor':'Choose location');trigger.setAttribute('aria-haspopup','dialog');}
    const conditionChoice=select.matches('[data-standard-choice][data-sku-view-filter="f_condition"]');
    if(conditionChoice){trigger.classList.add('sku-condition-choice-trigger');popup.classList.add('sku-condition-choice-popup');}
    popup.setAttribute('role',managed?'dialog':'listbox');popup.id='location-picker-'+(++locationPicker.counter);
    trigger.setAttribute('aria-haspopup',managed?'dialog':'listbox');trigger.setAttribute('aria-controls',popup.id);trigger.setAttribute('aria-expanded','false');
    const close=(focus=false)=>{popup.hidden=true;trigger.setAttribute('aria-expanded','false');if(activePicker?.trigger===trigger)activePicker=null;if(focus)trigger.focus();};
    const warehouseChoice=Boolean(select.closest('.inventory-warehouse-filter'));
    const warehouseTooltip=()=>{if(!warehouseChoice)return;const text=trigger.querySelector('.warehouse-choice-label');trigger.removeAttribute('title');if(text&&text.scrollWidth>text.clientWidth+1)trigger.dataset.tooltip=text.textContent;else delete trigger.dataset.tooltip;};
    if(warehouseChoice){trigger.addEventListener('pointerover',warehouseTooltip);trigger.addEventListener('focus',warehouseTooltip);if(typeof ResizeObserver!=='undefined')new ResizeObserver(warehouseTooltip).observe(trigger);}
    const sync=()=>{
      const label=select.selectedOptions[0]?.textContent.trim()||'Choose location';
      const code=label.split(' · ')[0],text=document.createElement('span');text.className='location-choice-label'+(warehouseChoice?' warehouse-choice-label':'');text.textContent=compact?code.toUpperCase():label;
      const chevron=document.createElementNS('http://www.w3.org/2000/svg','svg');chevron.classList.add('location-choice-chevron');chevron.setAttribute('viewBox','0 0 16 16');chevron.setAttribute('aria-hidden','true');chevron.setAttribute('focusable','false');const path=document.createElementNS('http://www.w3.org/2000/svg','path');path.setAttribute('d','m4 6 4 4 4-4');chevron.append(path);trigger.replaceChildren(text,chevron);
      if(warehouseChoice){delete trigger.dataset.tooltip;requestAnimationFrame(warehouseTooltip);}else trigger.dataset.tooltip=label;trigger.disabled=select.disabled;
      trigger.setAttribute('aria-label',(select.getAttribute('aria-label')||'Storage location')+': '+label);
    };
    const position=()=>{
      const rect=trigger.getBoundingClientRect(),dialog=select.closest('dialog');
      const bounds=dialog?.getBoundingClientRect();
      const left=Math.max(12,(bounds?.left||0)+12),right=Math.min(innerWidth-12,(bounds?.right||innerWidth)-12);
      const top=Math.max(12,(bounds?.top||0)+12),bottom=Math.min(innerHeight-12,(bounds?.bottom||innerHeight)-12);
      popup.style.width=Math.min(300,right-left)+'px';popup.style.maxHeight=Math.min(300,bottom-top)+'px';
      // A dragged dialog establishes a containing block for fixed descendants.
      // Convert viewport coordinates to that block rather than offsetting twice.
      popup.style.left='0px';popup.style.top='0px';
      const origin=popup.getBoundingClientRect(),height=popup.offsetHeight;
      const targetLeft=Math.max(left,Math.min(rect.left,right-popup.offsetWidth));
      const targetTop=rect.bottom+6+height<=bottom?rect.bottom+6:Math.max(top,Math.min(rect.top-height-6,bottom-height));
      popup.style.left=(targetLeft-origin.left)+'px';popup.style.top=(targetTop-origin.top)+'px';
    };
    const open=()=>{
      managed=select.dataset.choiceManager;if(managed)popup.setAttribute('aria-label',managed==='warehouse'?'Choose warehouse':managed==='vendor'?'Choose vendor':'Choose location');popup.setAttribute('role',managed?'dialog':'listbox');trigger.setAttribute('aria-haspopup',managed?'dialog':'listbox');
      activePicker?.close();popup.replaceChildren();
      [...select.options].filter(option=>!option.hidden).forEach(option=>{
        const choice=document.createElement('button');choice.type='button';choice.setAttribute('role',managed?'button':'option');if(managed){choice.dataset.choiceOption='';choice.setAttribute('aria-pressed',String(option.selected));}
        if(!managed)choice.setAttribute('aria-selected',String(option.selected));choice.disabled=option.disabled;
        const badge=document.createElement('span');badge.className='location-option-code';badge.textContent=option.textContent.trim().split(' · ')[0].toUpperCase();
        const label=document.createElement('span');label.textContent=option.textContent.trim();if(!select.hasAttribute('data-standard-choice'))choice.append(badge);choice.append(label);if(option.dataset.choiceDescription){const description=document.createElement('small');description.className='location-option-description';description.textContent=option.dataset.choiceDescription;description.title=option.title;label.append(description);}if(conditionChoice){const check=document.createElement('span');check.className='sku-choice-check';check.setAttribute('aria-hidden','true');check.textContent='✓';choice.append(check);}
        choice.onclick=()=>{select.value=option.value;sync();close(true);select.dispatchEvent(new Event('change',{bubbles:true}));};
        if(managed&&option.value&&option.dataset.choiceNoEdit!=='true'){const row=document.createElement('div');row.className='managed-choice-row';row.append(choice);const edit=document.createElement('button');edit.type='button';edit.className='managed-choice-edit';edit.textContent='Edit';edit.setAttribute('aria-label','Edit '+option.textContent.trim());edit.onclick=()=>manage('edit',option.value);row.append(edit);popup.append(row);}else popup.append(choice);
      });
      if(managed){const footer=document.createElement('div');footer.className='managed-choice-footer';const add=document.createElement('button');add.type='button';add.textContent=managed==='warehouse'?'+ Add warehouse':managed==='vendor'?'+ Add vendor':'+ Add location';add.onclick=()=>manage('add','');footer.append(add);popup.append(footer);}
      const extra=select.platformChoiceAction;if(extra?.available?.()){const footer=document.createElement('div');footer.className='managed-choice-footer';const add=document.createElement('button');add.type='button';add.className='platform-choice-action';add.disabled=select.disabled||!!extra.disabled?.();const plus=document.createElementNS('http://www.w3.org/2000/svg','svg');plus.setAttribute('viewBox','0 0 24 24');plus.setAttribute('width','20');plus.setAttribute('height','20');plus.setAttribute('fill','none');plus.setAttribute('stroke','currentColor');plus.setAttribute('stroke-width','1.75');plus.setAttribute('stroke-linecap','round');plus.setAttribute('aria-hidden','true');plus.innerHTML='<path d="M12 5v14M5 12h14"></path>';add.append(plus,document.createTextNode(extra.label));add.onclick=()=>{if(select.disabled||extra.disabled?.())return;close();extra.run();};footer.append(add);popup.append(footer);popup.setAttribute('role','dialog');trigger.setAttribute('aria-haspopup','dialog');popup.querySelectorAll('[role=option]').forEach(option=>{option.setAttribute('role','button');option.setAttribute('aria-pressed',option.getAttribute('aria-selected'));option.removeAttribute('aria-selected');});}
      (select.closest('dialog')||document.body).append(popup);popup.hidden=false;
      position();
      trigger.setAttribute('aria-expanded','true');activePicker={trigger,popup,close,position};
      (popup.querySelector('[aria-selected=true]:not(:disabled),[aria-pressed=true]:not(:disabled)')||popup.querySelector('button:not(:disabled)'))?.focus({preventScroll:true});
    };
    const manage=(action,id)=>{close();select.dispatchEvent(new CustomEvent('platform-choice-manage',{bubbles:true,detail:{kind:managed,action,id,trigger}}));};
    popup.addEventListener('keydown',event=>{
      const options=[...popup.querySelectorAll(managed?'[data-choice-option]:not(:disabled)':'button:not(:disabled)')],index=options.indexOf(document.activeElement);
      if(event.key==='Escape'){event.preventDefault();event.stopPropagation();close(true);}
      else if(['ArrowDown','ArrowUp','Home','End'].includes(event.key)){event.preventDefault();const next=event.key==='Home'?0:event.key==='End'?options.length-1:(index+(event.key==='ArrowDown'?1:-1)+options.length)%options.length;options[next]?.focus();}
      else if(event.key==='Tab'&&!managed&&!select.platformChoiceAction){if(conditionChoice)trigger.focus();close();}
    });
    popup.addEventListener('focusout',()=>setTimeout(()=>{if((managed||select.platformChoiceAction)&&activePicker?.popup===popup&&!popup.contains(document.activeElement)&&document.activeElement!==trigger)close();},0));
    trigger.onclick=()=>popup.hidden?open():close();
    trigger.addEventListener('keydown',event=>{if(event.key==='ArrowDown'||event.key==='ArrowUp'){event.preventDefault();open();}});
    select.addEventListener('change',sync);select.addEventListener('platform-choice-sync',sync);select.addEventListener('invalid',event=>{event.preventDefault();trigger.focus();open();});
    select.form?.addEventListener('reset',()=>setTimeout(sync,0));
    new MutationObserver(sync).observe(select,{childList:true,subtree:true,attributes:true,attributeFilter:['disabled','selected']});
    select.after(trigger);select.hidden=true;sync();
  }
  locationPicker.counter=0;
  function enhance(root=document) {
    shortcuts(root);
    root.querySelectorAll('.context-filter>span,.search-field>span').forEach(icon=>{
      if(icon.textContent.trim()!=='⌕')return;
      icon.classList.add('standard-search-icon');icon.setAttribute('aria-hidden','true');
      icon.innerHTML='<svg viewBox="0 0 24 24"><circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 4.5 4.5"/></svg>';
    });
    root.querySelectorAll('select[name="locationId"],select[name="destinationLocationId"],.receipt-location-select,select[data-standard-choice]').forEach(locationPicker);
    root.querySelectorAll('td .location-chip:not(button):not([data-location-badge])').forEach(chip=>{
      const code=chip.querySelector('strong'),detail=chip.querySelector('small');if(!code)return;
      chip.title=[code.textContent,detail?.textContent].filter(Boolean).join(' · ');chip.setAttribute('aria-label',chip.title);
      code.textContent=code.textContent.toUpperCase();if(detail)detail.hidden=true;chip.classList.add('table-location-badge');chip.dataset.locationBadge='true';
    });
  }
  window.NextAiMarketplaceShortcuts={enhance:shortcuts};window.NextAiPlatformControls={enhance};
  // One global listener, not one per catalogue row/location picker.
  document.addEventListener('pointerdown',event=>{if(activePicker&&!activePicker.popup.contains(event.target)&&!activePicker.trigger.contains(event.target))activePicker.close();});
  document.addEventListener('scroll',event=>{if(!event.target.closest?.('.location-picker-popup'))(activePicker?.trigger.closest('dialog')?activePicker.position():activePicker?.close());},true);window.addEventListener('resize',()=>activePicker?.close());
  enhance();
})();
