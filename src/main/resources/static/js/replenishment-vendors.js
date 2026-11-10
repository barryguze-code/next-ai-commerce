/* Reuse the tenant-scoped vendor directory and its existing validated forms in-place. */
(()=>{
 'use strict';
 const directory=document.querySelector('#rp-vendor-directory'),editor=document.querySelector('#rp-vendor-editor');if(!directory||!editor)return;
 const list=directory.querySelector('[data-vendor-list]'),feedback=directory.querySelector('[data-vendor-feedback]');
 let documentCopy,entries=[],origin,busy=false,baseline='';
 const notify=(message)=>{feedback.textContent=message;feedback.hidden=!message;};
 const parse=html=>new DOMParser().parseFromString(html,'text/html');
 function verifiedDocument(html){const doc=parse(html);if(!doc.querySelector('#vendor-form')||!doc.querySelector('.vendor-directory'))throw Error('Vendors could not be loaded. Check your account access and try again.');return doc;}
 function render(doc){
  documentCopy=doc;entries=[...doc.querySelectorAll('.vendor-row')];list.replaceChildren();
  const table=document.createElement('table');table.className='table-widget rp-vendor-directory-table';const body=document.createElement('tbody');table.append(body);
  entries.forEach(source=>{
   const row=document.createElement('tr');for(const [key,value]of Object.entries(source.dataset))if(['pictureId','pictureType','canEditPicture','image'].includes(key))row.dataset[key]=value;
   const identity=document.createElement('td'),wrap=document.createElement('div');wrap.className='rp-vendor-identity';
   const picture=document.createElement('span');picture.className='table-overview-picture';const initials=document.createElement('b');const name=source.querySelector('.product-cell strong')?.textContent||'';initials.textContent=name.trim().split(/\s+/).slice(0,2).map(p=>p[0]).join('').toUpperCase();picture.append(initials);
   const text=document.createElement('div');const strong=document.createElement('strong');strong.textContent=name;text.append(strong);source.querySelectorAll('.product-cell small').forEach(s=>text.append(s.cloneNode(true)));wrap.append(picture,text);identity.append(wrap);row.append(identity);
   const actions=document.createElement('td'),existing=source.querySelector('button[data-id]');if(existing){const edit=document.createElement('button');edit.type='button';edit.className='secondary-button';edit.textContent='Edit';edit.setAttribute('aria-label','Edit '+name);edit.onclick=()=>openEditor(existing);actions.append(edit);}row.append(actions);body.append(row);
  });
  if(!entries.length){const empty=document.createElement('p');empty.className='table-empty';empty.textContent='No vendors yet';list.append(empty);}else list.append(table);window.NextAiStandardTables?.enhance();
 }
 async function load(){const response=await fetch('/app/vendors',{headers:{Accept:'text/html'},cache:'no-store'});if(!response.ok)throw Error('Vendors could not be loaded. Please try again.');const doc=verifiedDocument(await response.text());render(doc);return doc;}
 async function open(action,id,trigger){if(busy)return;origin=trigger||document.activeElement;notify('');directory.showModal();list.textContent='Loading vendors…';try{await load();if(!directory.open)return;if(action==='add')openEditor();else if(action==='edit'){const matches=entries.map(e=>e.querySelector('button[data-id]')).filter(b=>b&&(b.dataset.id===id||b.dataset.name===id));if(matches.length===1)openEditor(matches[0]);else notify('Choose the vendor to edit.');}}catch(error){list.replaceChildren();notify(error.message);}}
 function closeDirectory(){if(busy)return;directory.close();origin?.focus();}
 async function closeEditor(){if(busy)return;const form=editor.querySelector('form');if(form&&new URLSearchParams(new FormData(form)).toString()!==baseline){const accept=window.NextAiConfirm?await window.NextAiConfirm({title:'Leave without saving?',message:'Your vendor changes have not been saved.',accept:'Leave without saving'}):window.confirm('Leave without saving vendor changes?');if(!accept)return;}editor.close();directory.querySelector('[data-vendor-add]')?.focus();}
 function openEditor(button){
  const source=documentCopy?.querySelector('#vendor-form');if(!source)return;const form=source.cloneNode(true);form.querySelectorAll('[onclick]').forEach(el=>el.removeAttribute('onclick'));form.querySelector('.eyebrow')?.classList.replace('eyebrow','mw-eyebrow');form.querySelectorAll('small,.dialog-header p').forEach(el=>el.remove());
  const id=button?.dataset.id;form.action=id?'/app/vendors/'+encodeURIComponent(id):'/app/catalog/vendors';form.querySelector('#vendor-title').textContent=id?'Edit vendor':'Add vendor';form.querySelector('[name=returnTo]').value='/app/vendors';
  const fields={name:'name',code:'code',currency:'currency',discountRate:'discount',defaultFreightAmount:'freight',status:'status'};
  for(const [field,key]of Object.entries(fields)){const input=form.elements[field];if(button)input.value=button.dataset[key]??input.value;}
  form.querySelector('#status-field').hidden=!id;form.elements.status.setAttribute('data-standard-choice','');form.querySelector('#currency-prefix').textContent=form.elements.currency.value==='USD'?'$':form.elements.currency.value;
  const footer=form.querySelector('.dialog-actions');const error=document.createElement('p');error.className='rp-vendor-save-error';error.setAttribute('role','alert');error.hidden=true;footer.prepend(error);
  form.querySelectorAll('.dialog-close,.dialog-actions button[type=button]').forEach(b=>b.onclick=closeEditor);
  editor.replaceChildren(form);baseline=new URLSearchParams(new FormData(form)).toString();
  form.addEventListener('submit',async event=>{event.preventDefault();if(busy||!form.reportValidity())return;busy=true;error.hidden=true;const submit=form.querySelector('[type=submit]');submit.disabled=true;submit.textContent='Saving…';
   try{const response=await fetch(form.action,{method:'POST',body:new FormData(form),headers:{Accept:'text/html'}});if(!response.ok)throw Error('Vendor could not be saved. Your changes are kept; please retry.');const doc=verifiedDocument(await response.text());const failure=doc.querySelector('[role=alert]');if(failure)throw Error(failure.textContent.trim());const success=doc.querySelector('.notice:not(.error)');if(!success||!/Vendor (added|details updated)/.test(success.textContent))throw Error('Save was not confirmed. Your changes are kept; please retry.');render(doc);editor.close();notify(success.textContent.trim());document.dispatchEvent(new CustomEvent('rp:vendors-updated',{detail:{vendors:entries.map(row=>{const name=row.querySelector('.product-cell strong')?.textContent||'';return {id:row.dataset.pictureId,name};})}}));directory.querySelector('[data-vendor-add]')?.focus();}
   catch(failure){error.textContent=failure.message;error.hidden=false;}finally{busy=false;submit.disabled=false;submit.textContent='Save vendor';}
  });
  editor.showModal();window.NextAiPlatformControls?.enhance(editor);form.elements.name.focus();
 }
 directory.querySelectorAll('[data-vendor-close]').forEach(b=>b.onclick=closeDirectory);directory.querySelector('[data-vendor-add]')?.addEventListener('click',()=>openEditor());
 directory.addEventListener('cancel',event=>{event.preventDefault();closeDirectory();});editor.addEventListener('cancel',event=>{event.preventDefault();closeEditor();});
 document.addEventListener('rp:vendors-open',()=>open());document.addEventListener('platform-choice-manage',event=>{if(event.detail.kind==='vendor')open(event.detail.action,event.detail.id,event.detail.trigger);});
})();
