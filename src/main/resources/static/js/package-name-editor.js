window.NextAiPackageNameEditor=({select,packageName,showPackage,status,csrf,onSaved})=>{
 const normal=name=>String(name).replace(/\s+/g,' ').trim(),key=name=>normal(name).toLowerCase();
 const remember=name=>{let option=[...select.options].find(o=>key(o.value)===key(name));if(!option){option=document.createElement('option');option.value=option.textContent=name;select.append(option);}return option.value;};
 const rename=document.querySelector('#rename-package'),nameDialog=document.querySelector('#packing-name-dialog');
 if(rename&&nameDialog){
  const field=document.querySelector('#packing-rename-value'),summary=document.querySelector('#packing-name-summary'),error=document.querySelector('#packing-name-error'),confirmButton=document.querySelector('#packing-name-confirm'),title=document.querySelector('#packing-name-title');
  let review=null,source='',busy=false;
  const options=document.querySelector('#packing-name-options'),toggle=document.querySelector('#packing-name-toggle');
  let active=-1;
  function closeOptions(){if(!options)return;options.hidden=true;field.setAttribute('aria-expanded','false');field.removeAttribute('aria-activedescendant');active=-1;}
  function openOptions(filter=true){
   if(!options||field.readOnly)return;options.replaceChildren();active=-1;
   [...select.options].filter(o=>o.value!=='__custom'&&key(o.value)!==key(source)&&(!filter||key(o.value).includes(key(field.value)))).forEach((o,index)=>{
    const item=document.createElement('button');item.type='button';item.id='packing-name-option-'+index;item.setAttribute('role','option');item.setAttribute('aria-selected','false');item.tabIndex=-1;item.textContent=o.value;
    item.addEventListener('click',()=>{field.value=o.value;field.dispatchEvent(new Event('input'));closeOptions();field.focus();});options.append(item);
   });
   options.hidden=!options.children.length;field.setAttribute('aria-expanded',String(!options.hidden));
   const rect=field.closest('.packing-name-combo').getBoundingClientRect();options.style.left=Math.max(8,Math.min(rect.left,innerWidth-rect.width-8))+'px';options.style.width=Math.min(rect.width,innerWidth-16)+'px';
   const below=innerHeight-rect.bottom-8,above=rect.top-8,height=Math.min(240,Math.max(below,above));options.style.maxHeight=height+'px';options.style.top=(below>=Math.min(240,above)?rect.bottom+4:Math.max(8,rect.top-height-4))+'px';
  }
  toggle?.addEventListener('click',()=>options.hidden?openOptions(false):closeOptions());
  field.addEventListener('input',()=>openOptions());
  field.addEventListener('keydown',event=>{
   if(event.key==='Escape'&&options&&!options.hidden){event.preventDefault();event.stopPropagation();closeOptions();return;}
   if(['ArrowDown','ArrowUp'].includes(event.key)){event.preventDefault();if(options.hidden)openOptions(false);const items=[...options.children];if(!items.length)return;active=(active+(event.key==='ArrowDown'?1:-1)+items.length)%items.length;items.forEach((item,i)=>item.setAttribute('aria-selected',String(i===active)));field.setAttribute('aria-activedescendant',items[active].id);items[active].scrollIntoView({block:'nearest'});}
   else if(event.key==='Enter'&&options&&!options.hidden&&active>=0){event.preventDefault();options.children[active].click();}else if(event.key==='Tab')closeOptions();
  });
  nameDialog.addEventListener('close',closeOptions);nameDialog.addEventListener('pointerdown',event=>{if(!options?.contains(event.target)&&event.target!==field&&event.target!==toggle&&!toggle?.contains(event.target))closeOptions();});
  window.addEventListener('resize',closeOptions);nameDialog.addEventListener('scroll',closeOptions);

  rename.addEventListener('click',()=>{source=packageName();field.value=source;field.readOnly=false;review=null;summary.textContent='';error.textContent='';title.textContent='Rename package';confirmButton.textContent='Review';nameDialog.showModal();field.focus();field.select();});
  nameDialog.querySelectorAll('[data-name-cancel]').forEach(button=>button.addEventListener('click',()=>{if(!busy){nameDialog.close();rename.focus();}}));
  nameDialog.addEventListener('cancel',event=>{if(busy)event.preventDefault();});
  field.addEventListener('input',()=>{review=null;confirmButton.textContent='Review';});
  confirmButton.addEventListener('click',async()=>{
   if(busy)return;closeOptions();busy=true;confirmButton.disabled=true;error.textContent='';
   try{
    const applying=!!review,form=new URLSearchParams(applying?{source:review.source,destination:review.destination,revision:review.revision,affectedAssignments:review.affectedAssignments}:{source,destination:field.value});
    const headers={'Content-Type':'application/x-www-form-urlencoded',Accept:'application/json'};if(csrf)headers[csrf.dataset.header||'X-CSRF-TOKEN']=csrf.value;
    const response=await fetch('/app/orders/packing-packages/'+(applying?'apply':'review'),{method:'POST',headers,body:form});
    const json=await response.json();if(!response.ok)throw Error(json.detail||json.message||'Could not update package.');
    if(!applying){review=json;field.value=json.destination;field.readOnly=true;title.textContent=json.operation==='MERGE'?'Merge packages':'Rename package';summary.textContent=json.affectedAssignments+' saved assignments: '+json.source+' → '+json.destination+'. '+(json.operation==='MERGE'?'Move these assignments and remove the old package choice. Assignments will use the destination’s applicable package cost. Profit may change.':'Package name changes only. Costs and profit remain unchanged.');confirmButton.textContent=json.operation==='MERGE'?'Merge packages':'Save';}
    else{for(const option of [...select.options])if(key(option.value)===key(json.source))option.remove();select.value=remember(json.destination);showPackage();nameDialog.close();status.textContent=json.operation==='MERGE'?'Packages merged.':'Package renamed.';rename.focus();onSaved?.(json);}
   }catch(problem){error.textContent=problem.message;review=null;field.readOnly=false;confirmButton.textContent='Review';}
   finally{busy=false;confirmButton.disabled=false;}
  });
 }
};
